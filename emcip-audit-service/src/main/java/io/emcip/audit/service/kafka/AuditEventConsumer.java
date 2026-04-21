package io.emcip.audit.service.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.service.AuditService;
import io.emcip.common.events.EventSchemas;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that receives events from all major EMCIP topics and persists them as audit
 * records. Uses manual acknowledgment so the offset is committed only after a successful save.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditEventConsumer {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "telegram.messages",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleTelegramMessage(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            EventSchemas.TelegramMessageEvent event =
                    objectMapper.readValue(record.value(), EventSchemas.TelegramMessageEvent.class);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("telegramMessageId", event.telegramMessageId());
            details.put("chatId", event.chatId());
            details.put("senderId", event.senderId());
            details.put("senderType", event.senderType());

            AuditEventEntity entity =
                    AuditEventEntity.builder()
                            .eventId(event.eventId())
                            .eventType(event.eventType())
                            .correlationId(event.eventId())
                            .sourceService("emcip-tdlib-adapter")
                            .action(event.eventType())
                            .actorType("SYSTEM")
                            .actorId(event.senderId())
                            .resourceType("TelegramMessage")
                            .resourceId(
                                    event.telegramMessageId() != null
                                            ? event.telegramMessageId().toString()
                                            : null)
                            .outcome("PROCESSED")
                            .details(auditService.serializeDetails(details))
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error(
                    "Permanently malformed telegram.messages record at offset {}, skipping: {}",
                    record.offset(),
                    e.getMessage());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error(
                    "Failed to persist audit event for telegram.messages offset {}: {}",
                    record.offset(),
                    e.getMessage(),
                    e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(
            topics = "intent.classified",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleIntentClassified(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            EventSchemas.IntentClassifiedEvent event =
                    objectMapper.readValue(
                            record.value(), EventSchemas.IntentClassifiedEvent.class);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sourceEventId", event.sourceEventId());
            details.put("intent", event.intent());
            details.put("confidence", event.confidence());
            details.put("matchedRules", event.matchedRules());

            AuditEventEntity entity =
                    AuditEventEntity.builder()
                            .eventId(event.eventId())
                            .eventType(event.eventType())
                            .correlationId(event.eventId())
                            .sourceService("emcip-intent-classifier")
                            .action(event.eventType())
                            .actorType("SYSTEM")
                            .resourceType("Intent")
                            .resourceId(event.sourceEventId())
                            .outcome("PROCESSED")
                            .details(auditService.serializeDetails(details))
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error(
                    "Permanently malformed intent.classified record at offset {}, skipping: {}",
                    record.offset(),
                    e.getMessage());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error(
                    "Failed to persist audit event for intent.classified offset {}: {}",
                    record.offset(),
                    e.getMessage(),
                    e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(
            topics = "policy.decisions",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handlePolicyDecision(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            EventSchemas.PolicyDecisionEvent event =
                    objectMapper.readValue(record.value(), EventSchemas.PolicyDecisionEvent.class);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sourceEventId", event.sourceEventId());
            details.put("policyId", event.policyId());
            details.put("decision", event.decision());
            details.put("reason", event.reason());
            details.put("actions", event.actions());

            AuditEventEntity entity =
                    AuditEventEntity.builder()
                            .eventId(event.eventId())
                            .eventType(event.eventType())
                            .correlationId(event.eventId())
                            .sourceService("emcip-policy-engine")
                            .action(event.eventType())
                            .actorType("SYSTEM")
                            .resourceType("Policy")
                            .resourceId(event.policyId())
                            .outcome("PROCESSED")
                            .details(auditService.serializeDetails(details))
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error(
                    "Permanently malformed policy.decisions record at offset {}, skipping: {}",
                    record.offset(),
                    e.getMessage());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error(
                    "Failed to persist audit event for policy.decisions offset {}: {}",
                    record.offset(),
                    e.getMessage(),
                    e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(
            topics = "responses.generated",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleResponseGenerated(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            EventSchemas.ResponseGeneratedEvent event =
                    objectMapper.readValue(
                            record.value(), EventSchemas.ResponseGeneratedEvent.class);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sourceEventId", event.sourceEventId());
            details.put("modelUsed", event.modelUsed());
            details.put("tokenCount", event.tokenCount());

            AuditEventEntity entity =
                    AuditEventEntity.builder()
                            .eventId(event.eventId())
                            .eventType(event.eventType())
                            .correlationId(event.eventId())
                            .sourceService("emcip-llm-orchestrator")
                            .action(event.eventType())
                            .actorType("SYSTEM")
                            .resourceType("LlmResponse")
                            .resourceId(event.sourceEventId())
                            .outcome("PROCESSED")
                            .details(auditService.serializeDetails(details))
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error(
                    "Permanently malformed responses.generated record at offset {}, skipping: {}",
                    record.offset(),
                    e.getMessage());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error(
                    "Failed to persist audit event for responses.generated offset {}: {}",
                    record.offset(),
                    e.getMessage(),
                    e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(
            topics = "moderation.flags",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleModerationFlag(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            EventSchemas.ModerationFlagEvent event =
                    objectMapper.readValue(record.value(), EventSchemas.ModerationFlagEvent.class);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sourceEventId", event.sourceEventId());
            details.put("flagType", event.flagType());
            details.put("severity", event.severity());
            details.put("reason", event.reason());

            AuditEventEntity entity =
                    AuditEventEntity.builder()
                            .eventId(event.eventId())
                            .eventType(event.eventType())
                            .correlationId(event.eventId())
                            .sourceService("emcip-moderation-service")
                            .action(event.eventType())
                            .actorType("SYSTEM")
                            .resourceType("ModerationFlag")
                            .resourceId(event.sourceEventId())
                            .outcome("PROCESSED")
                            .details(auditService.serializeDetails(details))
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error(
                    "Permanently malformed moderation.flags record at offset {}, skipping: {}",
                    record.offset(),
                    e.getMessage());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error(
                    "Failed to persist audit event for moderation.flags offset {}: {}",
                    record.offset(),
                    e.getMessage(),
                    e);
            throw new RuntimeException(e);
        }
    }
}
