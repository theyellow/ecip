package io.emcip.llm.orchestrator.service;

import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.common.tenant.TenantContext;
import io.emcip.llm.orchestrator.client.LlmCallResult;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer for policy decisions that trigger LLM orchestration. Implements US-3.2.4: Policy
 * layer as gatekeeper for AI responses. Implements US-3.2.2: Routes decisions through real LLM
 * calls.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PolicyDecisionConsumer {

    private static final String TOPIC = "policies.decisions";
    private static final String RESPONSES_TOPIC = "responses.generated";

    private final ObjectMapper objectMapper;
    private final LlmCallService llmCallService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LlmResponseValidator responseValidator;

    @KafkaListener(
            topics = TOPIC,
            groupId = "llm-orchestrator",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        log.debug(
                "Received policy decision from partition {} offset {}",
                record.partition(),
                record.offset());

        UUID tenantId;
        try {
            tenantId = TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting record: {}", e.getMessage());
            return;
        }
        TenantContext.setTenantId(tenantId.toString());

        try {
            var decisionEvent =
                    objectMapper.readValue(record.value(), EventSchemas.PolicyDecisionEvent.class);

            String decision = decisionEvent.decision();
            String sourceEventId = decisionEvent.sourceEventId();

            log.info(
                    "Processing policy decision for event {}: decision={}",
                    sourceEventId,
                    decision);

            switch (decision) {
                case "RESPOND" -> handleRespondDecision(decisionEvent);
                case "ESCALATE" -> handleEscalateDecision(decisionEvent);
                case "EXECUTE" -> handleExecuteDecision(decisionEvent);
                case "BLOCK", "REVIEW" ->
                        log.info(
                                "Policy decision {} for event {} - skipping AI interaction",
                                decision,
                                sourceEventId);
                case "ALLOW" ->
                        log.debug("Policy ALLOW for event {} - no AI action needed", sourceEventId);
                default ->
                        log.warn(
                                "Unknown policy decision: {} for event {}",
                                decision,
                                sourceEventId);
            }

        } catch (Exception e) {
            log.error("Error processing policy decision: {}", e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    private void handleRespondDecision(EventSchemas.PolicyDecisionEvent event) {
        String userContent = extractUserContent(event.context());
        String conversationId = extractConversationId(event.context());

        Optional<LlmCallResult> result =
                llmCallService.callForTask(
                        "response",
                        "auto_response",
                        userContent,
                        Map.of(),
                        event.sourceEventId(),
                        conversationId);

        result.ifPresent(r -> publishResponse(event, r, "auto_response"));
    }

    private void handleEscalateDecision(EventSchemas.PolicyDecisionEvent event) {
        String userContent = extractUserContent(event.context());
        String conversationId = extractConversationId(event.context());

        Optional<LlmCallResult> result =
                llmCallService.callForTask(
                        "summary",
                        "escalation_summary",
                        userContent,
                        Map.of(),
                        event.sourceEventId(),
                        conversationId);

        result.ifPresent(r -> publishResponse(event, r, "escalation_summary"));
    }

    private void handleExecuteDecision(EventSchemas.PolicyDecisionEvent event) {
        String userContent = extractUserContent(event.context());
        String conversationId = extractConversationId(event.context());

        Optional<LlmCallResult> result =
                llmCallService.callForTask(
                        "command_validation",
                        "command_validation",
                        userContent,
                        Map.of(),
                        event.sourceEventId(),
                        conversationId);

        result.ifPresent(r -> publishResponse(event, r, "command_validation"));
    }

    private void publishResponse(
            EventSchemas.PolicyDecisionEvent event, LlmCallResult result, String templateName) {
        if (!result.success() || result.content() == null) {
            log.warn(
                    "LLM call did not produce content for event {} (request={})",
                    event.sourceEventId(),
                    result.requestId());
            return;
        }

        var validation = responseValidator.validate(result.content(), null);
        if (!validation.valid()) {
            log.warn(
                    "LLM response validation failed for event {}: {}",
                    event.sourceEventId(),
                    validation.reason());
            return;
        }

        EventSchemas.ResponseGeneratedEvent responseEvent =
                new EventSchemas.ResponseGeneratedEvent(
                        UUID.randomUUID().toString(),
                        Instant.now().toString(),
                        EventSchemas.RESPONSE_GENERATED_V1,
                        "ResponseGenerated",
                        event.sourceEventId(),
                        result.content(),
                        result.modelUsed(),
                        null,
                        Map.of(
                                "requestId", result.requestId(),
                                "templateName", templateName,
                                "decision", event.decision()));

        try {
            String payload = objectMapper.writeValueAsString(responseEvent);
            kafkaTemplate
                    .send(RESPONSES_TOPIC, event.sourceEventId(), payload)
                    .whenComplete(
                            (r, ex) -> {
                                if (ex != null) {
                                    log.error(
                                            "Failed to publish response for event {}: {}",
                                            event.sourceEventId(),
                                            ex.getMessage());
                                } else {
                                    log.info(
                                            "Published response for event {} to partition {}",
                                            event.sourceEventId(),
                                            r.getRecordMetadata().partition());
                                }
                            });
        } catch (JacksonException e) {
            log.error(
                    "Failed to serialize ResponseGeneratedEvent for {}: {}",
                    event.sourceEventId(),
                    e.getMessage());
        }
    }

    private String extractUserContent(Map<String, Object> context) {
        if (context == null) {
            return "Process this request";
        }
        Object text = context.get("text");
        if (text != null) {
            return text.toString();
        }
        Object message = context.get("originalMessage");
        return message != null ? message.toString() : "Process this request";
    }

    private String extractConversationId(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object chatId = context.get("chatId");
        return chatId != null ? chatId.toString() : null;
    }
}
