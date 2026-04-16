package io.emcip.policy.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.common.events.EventSchemas;
import io.emcip.common.events.EventSchemas.PolicyDecisionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Policy evaluation service.
 * Implements deterministic rule-based policy decisions.
 */
@Service
public class PolicyEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyEvaluationService.class);
    private static final String TOPIC_OUTPUT = "policies.decisions";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // Simple policies for Phase 2
    private final List<PolicyRule> policies = List.of(
        new PolicyRule("policy-001", "SPAM_BLOCK", 
            (intent, confidence) -> "SPAM".equals(intent) && confidence > 0.8,
            "BLOCK", "Spam detected with high confidence"),
        new PolicyRule("policy-002", "GREETING_RESPONSE",
            (intent, confidence) -> "GREETING".equals(intent) && confidence > 0.7,
            "RESPOND", "Greeting detected, auto-respond"),
        new PolicyRule("policy-003", "QUESTION_ESCALATE",
            (intent, confidence) -> "QUESTION".equals(intent) && confidence > 0.75,
            "ESCALATE", "Question requires human/AI response"),
        new PolicyRule("policy-004", "COMMAND_EXECUTE",
            (intent, confidence) -> "COMMAND".equals(intent) && confidence > 0.8,
            "EXECUTE", "Execute command if valid"),
        new PolicyRule("policy-005", "MODERATION_CHECK",
            (intent, confidence) -> confidence < 0.3,
            "REVIEW", "Low confidence classification requires review")
    );

    public PolicyEvaluationService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluate policies against an intent classification.
     */
    public Mono<PolicyDecisionEvent> evaluate(EventSchemas.IntentClassifiedEvent classification) {
        return Mono.fromCallable(() -> {
            String decision = "ALLOW";
            String reason = "No policy matched";
            String matchedPolicyId = null;

            // Evaluate all policies
            for (PolicyRule policy : policies) {
                if (policy.condition.test(classification.intent(), classification.confidence())) {
                    decision = policy.action;
                    reason = policy.reason;
                    matchedPolicyId = policy.id;
                    log.info("Policy {} matched for event {}: {} -> {}", 
                        policy.id, classification.sourceEventId(), classification.intent(), decision);
                    break; // First match wins (priority order)
                }
            }

            // Create decision event
            var decisionEvent = new PolicyDecisionEvent(
                UUID.randomUUID().toString(),
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
                    "matchedRules", classification.matchedRules()
                ),
                List.of(decision.toLowerCase())
            );

            // Publish to Kafka
            String json = objectMapper.writeValueAsString(decisionEvent);
            kafkaTemplate.send(TOPIC_OUTPUT, classification.eventId(), json);

            return decisionEvent;
        });
    }

    private record PolicyRule(
        String id,
        String name,
        PolicyCondition condition,
        String action,
        String reason
    ) {}

    @FunctionalInterface
    private interface PolicyCondition {
        boolean test(String intent, double confidence);
    }
}
