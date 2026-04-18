package io.emcip.policy.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.common.events.EventSchemas;
import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Policy evaluation service implementing deterministic rule-based policy decisions.
 * Evaluates intent classifications against configurable policy rules and persists decisions.
 */
@Service
public class PolicyEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyEvaluationService.class);
    private static final String TOPIC_OUTPUT = "policies.decisions";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final PolicyDecisionRepository decisionRepository;
    private final PolicyRuleConfigRepository ruleConfigRepository;

    // Default hardcoded rules for fallback
    private final List<DefaultPolicyRule> defaultRules = List.of(
            new DefaultPolicyRule("policy-001", "SPAM_BLOCK",
                    "SPAM", 0.8, null, "BLOCK", "Spam detected with high confidence"),
            new DefaultPolicyRule("policy-002", "GREETING_RESPONSE",
                    "GREETING", 0.7, null, "RESPOND", "Greeting detected, auto-respond"),
            new DefaultPolicyRule("policy-003", "QUESTION_ESCALATE",
                    "QUESTION", 0.75, null, "ESCALATE", "Question requires human/AI response"),
            new DefaultPolicyRule("policy-004", "COMMAND_EXECUTE",
                    "COMMAND", 0.8, null, "EXECUTE", "Execute command if valid"),
            new DefaultPolicyRule("policy-005", "MODERATION_CHECK",
                    "*", 0.0, 0.3, "REVIEW", "Low confidence classification requires review")
    );

    public PolicyEvaluationService(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            PolicyDecisionRepository decisionRepository,
            PolicyRuleConfigRepository ruleConfigRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.decisionRepository = decisionRepository;
        this.ruleConfigRepository = ruleConfigRepository;
    }

    /**
     * Evaluate policies against an intent classification and persist the decision.
     */
    @Transactional
    public PolicyDecision evaluate(EventSchemas.IntentClassifiedEvent classification) {
        String decision = "ALLOW";
        String reason = "No policy matched";
        String matchedPolicyId = null;

        // Get active rules from database (ordered by priority)
        List<PolicyRuleConfig> dbRules = ruleConfigRepository.findByActiveTrueOrderByPriorityAsc();
        List<EvaluatedRule> rulesToEvaluate = new ArrayList<>();

        if (dbRules.isEmpty()) {
            // Fallback to default hardcoded rules
            log.debug("No database rules found, using default rules");
            for (DefaultPolicyRule rule : defaultRules) {
                rulesToEvaluate.add(new EvaluatedRule(rule.id, rule.name, rule.targetIntent,
                        rule.minConfidence, rule.maxConfidence, rule.action, rule.reason));
            }
        } else {
            for (PolicyRuleConfig rule : dbRules) {
                rulesToEvaluate.add(new EvaluatedRule(rule.getId(), rule.getName(), rule.getTargetIntent(),
                        rule.getMinConfidence(), rule.getMaxConfidence(), rule.getAction(), rule.getReason()));
            }
        }

        // Evaluate all rules (first match wins based on priority order)
        for (EvaluatedRule rule : rulesToEvaluate) {
            if (matchesRule(rule, classification.intent(), classification.confidence())) {
                decision = rule.action;
                reason = rule.reason;
                matchedPolicyId = rule.id;
                log.info("Policy {} matched for event {}: {} -> {}",
                        rule.id, classification.sourceEventId(), classification.intent(), decision);
                break;
            }
        }

        // Persist decision
        PolicyDecision persistedDecision = persistDecision(classification, matchedPolicyId, decision, reason);

        // Publish to Kafka
        try {
            var decisionEvent = new EventSchemas.PolicyDecisionEvent(
                    persistedDecision.getId(),
                    Instant.now().toString(),
                    EventSchemas.POLICY_DECISION_V1,
                    "PolicyDecision",
                    classification.eventId(),
                    matchedPolicyId != null ? matchedPolicyId : "default",
                    decision,
                    reason,
                    Map.of(
                            "originalIntent", classification.intent(),
                            "confidence", classification.confidence(),
                            "matchedRules", classification.matchedRules()),
                    List.of(decision.toLowerCase()));

            String json = objectMapper.writeValueAsString(decisionEvent);
            kafkaTemplate.send(TOPIC_OUTPUT, classification.eventId(), json);
        } catch (Exception e) {
            log.error("Failed to publish policy decision to Kafka: {}", e.getMessage(), e);
        }

        return persistedDecision;
    }

    /**
     * Check if a rule matches the given intent and confidence.
     */
    private boolean matchesRule(EvaluatedRule rule, String intent, double confidence) {
        // Check intent match ("*" matches any)
        boolean intentMatches = "*".equals(rule.targetIntent) || rule.targetIntent.equals(intent);

        // Check confidence range
        boolean confidenceMatches = confidence >= rule.minConfidence &&
                (rule.maxConfidence == null || confidence <= rule.maxConfidence);

        return intentMatches && confidenceMatches;
    }

    /**
     * Persist the policy decision to the database.
     */
    private PolicyDecision persistDecision(EventSchemas.IntentClassifiedEvent classification,
                                          String matchedPolicyId, String decision, String reason) {
        PolicyDecision policyDecision = new PolicyDecision();
        policyDecision.setEventId(UUID.randomUUID().toString());
        policyDecision.setSourceEventId(classification.eventId());
        policyDecision.setPolicyId(matchedPolicyId != null ? matchedPolicyId : "default");
        policyDecision.setDecision(decision);
        policyDecision.setReason(reason);
        policyDecision.setOriginalIntent(classification.intent());
        policyDecision.setConfidence(classification.confidence());
        policyDecision.setMatchedRules(Map.of("matchedRules", classification.matchedRules()));
        policyDecision.setMetadata(Map.of(
                "intent", classification.intent(),
                "confidence", classification.confidence()
        ));
        policyDecision.setTimestamp(Instant.now());

        return decisionRepository.save(policyDecision);
    }

    /**
     * Get all active policy rules (for admin/management purposes).
     */
    public List<PolicyRuleConfig> getActiveRules() {
        return ruleConfigRepository.findByActiveTrueOrderByPriorityAsc();
    }

    // Internal rule representations
    private record DefaultPolicyRule(String id, String name, String targetIntent,
                                     Double minConfidence, Double maxConfidence,
                                     String action, String reason) {}

    private record EvaluatedRule(String id, String name, String targetIntent,
                                 Double minConfidence, Double maxConfidence,
                                 String action, String reason) {}
}
