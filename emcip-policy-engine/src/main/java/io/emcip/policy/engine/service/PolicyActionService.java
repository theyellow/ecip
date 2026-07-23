package io.emcip.policy.engine.service;

import io.emcip.policy.engine.entity.PolicyDecision;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Service for executing policy decisions by publishing action events to appropriate topics. Handles
 * outcomes: BLOCK, RESPOND, ESCALATE, EXECUTE, REVIEW, ALLOW.
 */
@Service
public class PolicyActionService {

    private static final Logger log = LoggerFactory.getLogger(PolicyActionService.class);

    // Kafka topics for different action types
    public static final String TOPIC_MODERATION = "moderation.actions";
    public static final String TOPIC_RESPONSES = "responses.pending";
    public static final String TOPIC_ESCALATION = "escalation.human";
    public static final String TOPIC_COMMANDS = "commands.execute";
    public static final String TOPIC_REVIEW = "review.pending";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PolicyActionService(
            KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute the action determined by the policy decision.
     *
     * @param decision The policy decision containing the action to execute
     * @param context Additional context from the original classification
     */
    public void executeAction(PolicyDecision decision, Map<String, Object> context) {
        String action = decision.getDecision();
        String sourceEventId = decision.getSourceEventId();

        log.info("Executing policy action {} for event {}", action, sourceEventId);

        try {
            switch (action) {
                case "BLOCK":
                    executeBlock(decision, context);
                    break;
                case "RESPOND":
                    executeRespond(decision, context);
                    break;
                case "ESCALATE":
                    executeEscalate(decision, context);
                    break;
                case "EXECUTE":
                    executeCommand(decision, context);
                    break;
                case "REVIEW":
                    executeReview(decision, context);
                    break;
                case "ALLOW":
                    executeAllow(decision);
                    break;
                case "FLAG":
                    executeFlag(decision, context);
                    break;
                default:
                    log.warn("Unknown action type: {}. No action taken.", action);
            }
        } catch (Exception e) {
            log.error(
                    "Failed to execute action {} for event {}: {}",
                    action,
                    sourceEventId,
                    e.getMessage(),
                    e);
        }
    }

    /** BLOCK: Publish to moderation topic for blocking action. */
    private void executeBlock(PolicyDecision decision, Map<String, Object> context) {
        Map<String, Object> actionEvent = new java.util.HashMap<>();
        actionEvent.put("eventId", UUID.randomUUID().toString());
        actionEvent.put("timestamp", Instant.now().toString());
        actionEvent.put("actionType", "BLOCK");
        actionEvent.put("sourceEventId", decision.getSourceEventId());
        actionEvent.put("decisionId", decision.getId());
        actionEvent.put("reason", decision.getReason());
        actionEvent.put("context", context);
        actionEvent.put("severity", "HIGH");

        publishToTopic(TOPIC_MODERATION, decision.getSourceEventId(), actionEvent);
        log.info(
                "Published BLOCK action to {} for event {}",
                TOPIC_MODERATION,
                decision.getSourceEventId());
    }

    /** RESPOND: Publish to responses topic for auto-response generation. */
    private void executeRespond(PolicyDecision decision, Map<String, Object> context) {
        Map<String, Object> actionEvent = new java.util.HashMap<>();
        actionEvent.put("eventId", UUID.randomUUID().toString());
        actionEvent.put("timestamp", Instant.now().toString());
        actionEvent.put("actionType", "AUTO_RESPOND");
        actionEvent.put("sourceEventId", decision.getSourceEventId());
        actionEvent.put("decisionId", decision.getId());
        actionEvent.put("intent", decision.getOriginalIntent());
        actionEvent.put("confidence", decision.getConfidence());
        actionEvent.put("context", context);
        actionEvent.put("responseStrategy", "greeting");

        publishToTopic(TOPIC_RESPONSES, decision.getSourceEventId(), actionEvent);
        log.info(
                "Published RESPOND action to {} for event {}",
                TOPIC_RESPONSES,
                decision.getSourceEventId());
    }

    /** ESCALATE: Publish to escalation topic for human/AI review. */
    private void executeEscalate(PolicyDecision decision, Map<String, Object> context) {
        Map<String, Object> actionEvent = new java.util.HashMap<>();
        actionEvent.put("eventId", UUID.randomUUID().toString());
        actionEvent.put("timestamp", Instant.now().toString());
        actionEvent.put("actionType", "ESCALATE");
        actionEvent.put("sourceEventId", decision.getSourceEventId());
        actionEvent.put("decisionId", decision.getId());
        actionEvent.put("intent", decision.getOriginalIntent());
        actionEvent.put("confidence", decision.getConfidence());
        actionEvent.put("reason", decision.getReason());
        actionEvent.put("context", context);
        actionEvent.put("escalationLevel", "HUMAN_REVIEW");
        actionEvent.put("priority", calculatePriority(decision));

        publishToTopic(TOPIC_ESCALATION, decision.getSourceEventId(), actionEvent);
        log.info(
                "Published ESCALATE action to {} for event {}",
                TOPIC_ESCALATION,
                decision.getSourceEventId());
    }

    /** EXECUTE: Publish to commands topic for command execution. */
    private void executeCommand(PolicyDecision decision, Map<String, Object> context) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params =
                (Map<String, Object>) context.getOrDefault("parameters", Map.of());

        Map<String, Object> actionEvent = new java.util.HashMap<>();
        actionEvent.put("eventId", UUID.randomUUID().toString());
        actionEvent.put("timestamp", Instant.now().toString());
        actionEvent.put("actionType", "EXECUTE_COMMAND");
        actionEvent.put("sourceEventId", decision.getSourceEventId());
        actionEvent.put("decisionId", decision.getId());
        actionEvent.put("command", extractCommand(params));
        actionEvent.put("parameters", params);
        actionEvent.put("context", context);

        publishToTopic(TOPIC_COMMANDS, decision.getSourceEventId(), actionEvent);
        log.info(
                "Published EXECUTE action to {} for event {}",
                TOPIC_COMMANDS,
                decision.getSourceEventId());
    }

    /** REVIEW: Publish to review topic for flagged content review. */
    private void executeReview(PolicyDecision decision, Map<String, Object> context) {
        Map<String, Object> actionEvent = new java.util.HashMap<>();
        actionEvent.put("eventId", UUID.randomUUID().toString());
        actionEvent.put("timestamp", Instant.now().toString());
        actionEvent.put("actionType", "REVIEW");
        actionEvent.put("sourceEventId", decision.getSourceEventId());
        actionEvent.put("decisionId", decision.getId());
        actionEvent.put("intent", decision.getOriginalIntent());
        actionEvent.put("confidence", decision.getConfidence());
        actionEvent.put("reason", decision.getReason());
        actionEvent.put("context", context);
        actionEvent.put("reviewQueue", "LOW_CONFIDENCE");
        actionEvent.put("flaggedAt", Instant.now().toString());

        publishToTopic(TOPIC_REVIEW, decision.getSourceEventId(), actionEvent);
        log.info(
                "Published REVIEW action to {} for event {}",
                TOPIC_REVIEW,
                decision.getSourceEventId());
    }

    /** FLAG: Publish to moderation topic for human review without blocking. */
    private void executeFlag(PolicyDecision decision, Map<String, Object> context) {
        Map<String, Object> actionEvent = new java.util.HashMap<>();
        actionEvent.put("eventId", UUID.randomUUID().toString());
        actionEvent.put("timestamp", Instant.now().toString());
        actionEvent.put("actionType", "FLAG");
        actionEvent.put("sourceEventId", decision.getSourceEventId());
        actionEvent.put("decisionId", decision.getId());
        actionEvent.put("reason", decision.getReason());
        actionEvent.put("context", context);
        actionEvent.put("severity", "MEDIUM");

        publishToTopic(TOPIC_MODERATION, decision.getSourceEventId(), actionEvent);
        log.info(
                "Published FLAG action to {} for event {}",
                TOPIC_MODERATION,
                decision.getSourceEventId());
    }

    /** ALLOW: Log but take no restrictive action. */
    private void executeAllow(PolicyDecision decision) {
        log.info(
                "ALLOW action for event {} - no restrictions applied. Intent: {}, Confidence: {}",
                decision.getSourceEventId(),
                decision.getOriginalIntent(),
                decision.getConfidence());
        // No Kafka message needed for ALLOW - just pass through
    }

    /** Publish an action event to the specified Kafka topic. */
    private void publishToTopic(String topic, String key, Map<String, Object> event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, key, json);
        } catch (Exception e) {
            log.error("Failed to publish to topic {}: {}", topic, e.getMessage(), e);
            throw new RuntimeException("Failed to publish action event", e);
        }
    }

    /** Calculate escalation priority based on confidence and intent. */
    private String calculatePriority(PolicyDecision decision) {
        double confidence = decision.getConfidence();
        String intent = decision.getOriginalIntent();

        if (confidence > 0.9) {
            return "HIGH";
        } else if (confidence > 0.7 || "URGENT".equals(intent)) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }

    /** Extract command from parameters if available. */
    private String extractCommand(Map<String, Object> params) {
        if (params.containsKey("command")) {
            return params.get("command").toString();
        }
        return "unknown";
    }
}
