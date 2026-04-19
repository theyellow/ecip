package io.emcip.llm.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.common.events.EventSchemas;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer for policy decisions that trigger LLM orchestration. Implements US-3.2.4: Policy
 * layer as gatekeeper for AI responses. Only processes events where the policy decision allows AI
 * interaction.
 */
@Service
public class PolicyDecisionConsumer {

    private static final Logger log = LoggerFactory.getLogger(PolicyDecisionConsumer.class);
    private static final String TOPIC = "policies.decisions";

    private final ObjectMapper objectMapper;
    private final LlmOrchestratorService orchestratorService;

    public PolicyDecisionConsumer(
            ObjectMapper objectMapper, LlmOrchestratorService orchestratorService) {
        this.objectMapper = objectMapper;
        this.orchestratorService = orchestratorService;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = "llm-orchestrator",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        log.debug(
                "Received policy decision from partition {} offset {}",
                record.partition(),
                record.offset());

        try {
            var decisionEvent =
                    objectMapper.readValue(record.value(), EventSchemas.PolicyDecisionEvent.class);

            String decision = decisionEvent.decision();
            String sourceEventId = decisionEvent.sourceEventId();

            log.info(
                    "Processing policy decision for event {}: decision={}",
                    sourceEventId,
                    decision);

            // Only proceed with AI interaction for specific decisions
            switch (decision) {
                case "RESPOND":
                    handleRespondDecision(decisionEvent);
                    break;
                case "ESCALATE":
                    handleEscalateDecision(decisionEvent);
                    break;
                case "EXECUTE":
                    handleExecuteDecision(decisionEvent);
                    break;
                case "BLOCK":
                case "REVIEW":
                    log.info(
                            "Policy decision {} for event {} - skipping AI interaction",
                            decision,
                            sourceEventId);
                    break;
                case "ALLOW":
                    log.debug("Policy ALLOW for event {} - no AI action needed", sourceEventId);
                    break;
                default:
                    log.warn("Unknown policy decision: {} for event {}", decision, sourceEventId);
            }

        } catch (Exception e) {
            log.error("Error processing policy decision: {}", e.getMessage(), e);
        }
    }

    /** Handle RESPOND decision - generate auto-response using LLM. */
    private void handleRespondDecision(EventSchemas.PolicyDecisionEvent event) {
        log.info("Triggering auto-response generation for event {}", event.sourceEventId());

        // Get context from the decision event
        Map<String, Object> context = event.context();
        String originalIntent =
                context.containsKey("originalIntent")
                        ? context.get("originalIntent").toString()
                        : "UNKNOWN";

        // Select appropriate model and template for response generation
        var modelOpt = orchestratorService.selectModelForTask("response");
        var templateOpt = orchestratorService.getPromptTemplate("auto_response");

        if (modelOpt.isPresent() && templateOpt.isPresent()) {
            log.info(
                    "Prepared LLM request for auto-response: model={}, template={}",
                    modelOpt.get().getModelKey(),
                    templateOpt.get().getName());
            // Actual LLM call would be made here in a full implementation
        } else {
            log.warn("Cannot generate auto-response - missing model or template configuration");
        }
    }

    /** Handle ESCALATE decision - prepare for human/AI escalation. */
    private void handleEscalateDecision(EventSchemas.PolicyDecisionEvent event) {
        log.info("Preparing escalation for event {}", event.sourceEventId());

        // For escalations, we might want to generate a summary using a smaller model
        var modelOpt = orchestratorService.selectModelForTask("summary");

        if (modelOpt.isPresent()) {
            log.info("Selected summary model for escalation: {}", modelOpt.get().getModelKey());
            // Generate context summary for human review
        }
    }

    /** Handle EXECUTE decision - execute command with LLM assistance if needed. */
    private void handleExecuteDecision(EventSchemas.PolicyDecisionEvent event) {
        log.info("Processing command execution for event {}", event.sourceEventId());

        Map<String, Object> context = event.context();

        // For commands, we might validate or enhance with LLM
        var modelOpt = orchestratorService.selectModelForTask("command_validation");

        if (modelOpt.isPresent()) {
            log.info("Selected validation model for command: {}", modelOpt.get().getModelKey());
        }
    }
}
