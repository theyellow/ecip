package io.emcip.policy.engine.service;

import io.emcip.common.events.EventSchemas;
import io.emcip.policy.engine.condition.ConditionEvaluatorRegistry;
import io.emcip.policy.engine.condition.EvaluationContext;
import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Policy evaluation service implementing deterministic rule-based policy decisions. Evaluates
 * intent classifications against configurable policy rules and persists decisions.
 */
@Service
public class PolicyEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyEvaluationService.class);
    private static final String TOPIC_OUTPUT = "policies.decisions";
    private static final int FLAG_WINDOW_DAYS = 90;

    private static final Set<String> SIGNAL_PARAM_KEYS =
            Set.of(
                    "foreignScriptRatio",
                    "cyrillicRatio",
                    "lookalikeSuspicion",
                    "zeroWidthAbuse",
                    "capsRatio",
                    "emojiOnly",
                    "stickerOnly",
                    "imageOnly",
                    "toxicityHint");

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final PolicyDecisionRepository decisionRepository;
    private final PolicyRuleConfigRepository ruleConfigRepository;
    private final PolicyActionService actionService;
    private final ConditionEvaluatorRegistry conditionRegistry;

    // Default hardcoded rules for fallback
    private final List<DefaultPolicyRule> defaultRules =
            List.of(
                    new DefaultPolicyRule(
                            "policy-001",
                            "SPAM_BLOCK",
                            "SPAM",
                            0.8,
                            null,
                            "BLOCK",
                            "Spam detected with high confidence"),
                    new DefaultPolicyRule(
                            "policy-002",
                            "GREETING_RESPONSE",
                            "GREETING",
                            0.7,
                            null,
                            "RESPOND",
                            "Greeting detected, auto-respond"),
                    new DefaultPolicyRule(
                            "policy-003",
                            "QUESTION_ESCALATE",
                            "QUESTION",
                            0.75,
                            null,
                            "ESCALATE",
                            "Question requires human/AI response"),
                    new DefaultPolicyRule(
                            "policy-004",
                            "COMMAND_EXECUTE",
                            "COMMAND",
                            0.8,
                            null,
                            "EXECUTE",
                            "Execute command if valid"),
                    new DefaultPolicyRule(
                            "policy-005",
                            "MODERATION_CHECK",
                            "*",
                            0.0,
                            0.3,
                            "REVIEW",
                            "Low confidence classification requires review"));

    public PolicyEvaluationService(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            PolicyDecisionRepository decisionRepository,
            PolicyRuleConfigRepository ruleConfigRepository,
            PolicyActionService actionService,
            ConditionEvaluatorRegistry conditionRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.decisionRepository = decisionRepository;
        this.ruleConfigRepository = ruleConfigRepository;
        this.actionService = actionService;
        this.conditionRegistry = conditionRegistry;
    }

    /** Evaluate policies against an intent classification and persist the decision. */
    @Transactional
    public PolicyDecision evaluate(
            EventSchemas.IntentClassifiedEvent classification, UUID tenantId) {
        String decision = "ALLOW";
        String reason = "No policy matched";
        String matchedPolicyId = null;

        // Get active rules from database (ordered by priority)
        List<PolicyRuleConfig> dbRules = ruleConfigRepository.findEffectiveRulesAt(Instant.now());
        EvaluationContext ctx = buildContext(classification);

        List<EvaluatedRule> rulesToEvaluate = new ArrayList<>();
        if (dbRules.isEmpty()) {
            // Fallback to default hardcoded rules
            log.debug("No database rules found, using default rules");
            for (DefaultPolicyRule rule : defaultRules) {
                rulesToEvaluate.add(
                        new EvaluatedRule(
                                rule.id,
                                rule.name,
                                rule.targetIntent,
                                rule.minConfidence,
                                rule.maxConfidence,
                                rule.action,
                                rule.reason,
                                null));
            }
        } else {
            for (PolicyRuleConfig rule : dbRules) {
                rulesToEvaluate.add(
                        new EvaluatedRule(
                                rule.getId(),
                                rule.getName(),
                                rule.getTargetIntent(),
                                rule.getMinConfidence(),
                                rule.getMaxConfidence(),
                                rule.getAction(),
                                rule.getReason(),
                                rule.getConditions()));
            }
        }

        // Evaluate all rules (first match wins based on priority order)
        for (EvaluatedRule rule : rulesToEvaluate) {
            if (matchesRule(rule, ctx)) {
                decision = rule.action;
                reason =
                        (rule.reason != null && !rule.reason.isBlank())
                                ? rule.reason
                                : rule.name + " matched";
                matchedPolicyId = rule.id;
                log.info(
                        "Policy {} matched for event {}: {} -> {}",
                        rule.id,
                        classification.sourceEventId(),
                        classification.intent(),
                        decision);
                break;
            }
        }

        // Persist decision
        PolicyDecision persistedDecision =
                persistDecision(classification, matchedPolicyId, decision, reason, tenantId);

        // Publish to Kafka
        try {
            var decisionEvent =
                    new EventSchemas.PolicyDecisionEvent(
                            persistedDecision.getId(),
                            Instant.now().toString(),
                            EventSchemas.POLICY_DECISION_V1,
                            "PolicyDecision",
                            classification.eventId(),
                            matchedPolicyId != null ? matchedPolicyId : "default",
                            decision,
                            reason,
                            buildDecisionContext(classification),
                            List.of(decision.toLowerCase()),
                            classification.parameters() != null
                                            && classification.parameters().get("messageText")
                                                    instanceof String text
                                    ? text
                                    : null);

            String json = objectMapper.writeValueAsString(decisionEvent);
            ProducerRecord<String, String> producerRecord =
                    new ProducerRecord<>(TOPIC_OUTPUT, null, classification.eventId(), json);
            if (tenantId != null) {
                producerRecord
                        .headers()
                        .add("tenant_id", tenantId.toString().getBytes(StandardCharsets.UTF_8));
            }
            kafkaTemplate.send(producerRecord);
        } catch (Exception e) {
            log.error("Failed to publish policy decision to Kafka: {}", e.getMessage(), e);
        }

        // Execute the policy action
        Map<String, Object> actionContext =
                Map.of(
                        "intent", classification.intent(),
                        "confidence", classification.confidence(),
                        "matchedRules", classification.matchedRules(),
                        "parameters", classification.parameters());
        actionService.executeAction(persistedDecision, actionContext);

        return persistedDecision;
    }

    /** Checks intent + confidence + OR-group conditions. */
    private boolean matchesRule(EvaluatedRule rule, EvaluationContext ctx) {
        boolean intentMatches =
                "*".equals(rule.targetIntent) || rule.targetIntent.equals(ctx.intent());
        boolean confidenceMatches =
                ctx.confidence() >= rule.minConfidence
                        && (rule.maxConfidence == null || ctx.confidence() <= rule.maxConfidence);
        if (!intentMatches || !confidenceMatches) return false;
        return matchesConditions(rule.conditions, ctx);
    }

    /**
     * Evaluates OR-group conditions. Empty/absent groups = always pass (backward compat). Groups
     * are OR'd; conditions within a group are AND'd.
     */
    @SuppressWarnings("unchecked")
    public boolean matchesConditions(Map<String, Object> conditions, EvaluationContext ctx) {
        if (conditions == null) return true;
        Object groupsObj = conditions.get("groups");
        if (groupsObj == null) return true;
        List<Map<String, Object>> groups = (List<Map<String, Object>>) groupsObj;
        if (groups.isEmpty()) return true;
        for (Map<String, Object> group : groups) {
            List<Map<String, Object>> conds =
                    (List<Map<String, Object>>) group.getOrDefault("conditions", List.of());
            boolean groupPasses = conds.stream().allMatch(c -> conditionRegistry.evaluate(c, ctx));
            if (groupPasses) return true;
        }
        return false;
    }

    /** Builds an EvaluationContext from an IntentClassifiedEvent. */
    private EvaluationContext buildContext(EventSchemas.IntentClassifiedEvent event) {
        Map<String, Object> p = event.parameters() != null ? event.parameters() : Map.of();
        String senderId = p.get("senderId") instanceof String s ? s : null;
        int flaggedCount = 0;
        if (senderId != null) {
            try {
                Instant since = Instant.now().minus(FLAG_WINDOW_DAYS, ChronoUnit.DAYS);
                flaggedCount = decisionRepository.countBlockedBySenderSince(senderId, since);
            } catch (Exception e) {
                log.warn("Failed to fetch sender flagged count: {}", e.getMessage());
            }
        }
        return new EvaluationContext(
                event.intent(),
                event.confidence(),
                p.get("language") instanceof String l ? l : "",
                p.get("threadLength") instanceof Number n ? n.intValue() : 0,
                p.get("groupSize") instanceof Number n ? n.intValue() : 0,
                p.get("messageLength") instanceof Number n ? n.intValue() : 0,
                p.get("senderAccountAgeDays") instanceof Number n
                        ? n.intValue()
                        : Integer.MAX_VALUE,
                flaggedCount,
                FLAG_WINDOW_DAYS,
                ZonedDateTime.now());
    }

    private Map<String, Object> buildDecisionContext(
            EventSchemas.IntentClassifiedEvent classification) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("originalIntent", classification.intent());
        ctx.put("confidence", classification.confidence());
        ctx.put("matchedRules", classification.matchedRules());
        Map<String, Object> params =
                classification.parameters() != null ? classification.parameters() : Map.of();
        // Signal scores forwarded to Kafka event for downstream policy consumers
        for (String key : SIGNAL_PARAM_KEYS) {
            if (params.containsKey(key)) ctx.put(key, params.get(key));
        }
        return ctx;
    }

    /** Get all active policy rules (for admin/management purposes). */
    public List<PolicyRuleConfig> getActiveRules() {
        return ruleConfigRepository.findByActiveTrueOrderByPriorityAsc();
    }

    /** Persist the policy decision to the database. */
    private PolicyDecision persistDecision(
            EventSchemas.IntentClassifiedEvent classification,
            String matchedPolicyId,
            String decision,
            String reason,
            UUID tenantId) {
        PolicyDecision policyDecision = new PolicyDecision();
        policyDecision.setTenantId(tenantId);
        policyDecision.setEventId(UUID.randomUUID().toString());
        policyDecision.setSourceEventId(classification.eventId());
        policyDecision.setPolicyId(matchedPolicyId != null ? matchedPolicyId : "default");
        policyDecision.setDecision(decision);
        policyDecision.setReason(reason);
        policyDecision.setOriginalIntent(classification.intent());
        policyDecision.setConfidence(classification.confidence());
        policyDecision.setMatchedRules(Map.of("matchedRules", classification.matchedRules()));
        Map<String, Object> params =
                classification.parameters() != null ? classification.parameters() : Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("intent", classification.intent());
        meta.put("confidence", classification.confidence());
        if (params.containsKey("messageText")) meta.put("messageText", params.get("messageText"));
        if (params.containsKey("chatId")) meta.put("chatId", params.get("chatId"));
        if (params.containsKey("senderId")) meta.put("senderId", params.get("senderId"));
        if (params.containsKey("telegramMessageId"))
            meta.put("telegramMessageId", params.get("telegramMessageId"));
        // Signal scores persisted to DB metadata for audit queries
        for (String key : SIGNAL_PARAM_KEYS) {
            if (params.containsKey(key)) meta.put(key, params.get(key));
        }
        policyDecision.setMetadata(meta);
        policyDecision.setTimestamp(Instant.now());

        return decisionRepository.save(policyDecision);
    }

    /** Returns true if the current time is within a configured time window (HH:mm format, UTC). */
    static boolean matchesTimeWindow(Map<String, Object> conditions, ZonedDateTime now) {
        if (conditions == null) return true;
        String start = (String) conditions.get("timeWindowStart");
        String end = (String) conditions.get("timeWindowEnd");
        if (start == null || end == null) return true;
        int nowMinutes = now.getHour() * 60 + now.getMinute();
        int startMinutes = parseHhmm(start);
        int endMinutes = parseHhmm(end);
        if (startMinutes <= endMinutes) {
            return nowMinutes >= startMinutes && nowMinutes < endMinutes;
        } else {
            return nowMinutes >= startMinutes || nowMinutes < endMinutes;
        }
    }

    /** Returns true if context satisfies context-aware conditions (e.g. minThreadLength). */
    static boolean matchesContextConditions(
            Map<String, Object> conditions, Map<String, Object> context) {
        if (conditions == null) return true;
        Object minLen = conditions.get("minThreadLength");
        if (minLen != null) {
            int required = ((Number) minLen).intValue();
            int actual = ((Number) context.getOrDefault("threadLength", 0)).intValue();
            if (actual < required) return false;
        }
        return true;
    }

    private static int parseHhmm(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    // Internal rule representations
    private record DefaultPolicyRule(
            String id,
            String name,
            String targetIntent,
            Double minConfidence,
            Double maxConfidence,
            String action,
            String reason) {}

    private record EvaluatedRule(
            String id,
            String name,
            String targetIntent,
            Double minConfidence,
            Double maxConfidence,
            String action,
            String reason,
            Map<String, Object> conditions) {}
}
