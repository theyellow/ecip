package io.emcip.audit.service.kafka;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.service.AuditService;
import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.common.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
            topics = "telegram.raw.messages",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleTelegramMessage(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        processAuditEvent(
                record,
                acknowledgment,
                EventSchemas.TelegramMessageEvent.class,
                "emcip-tdlib-adapter",
                "TelegramMessage",
                e -> e.telegramMessageId() != null ? e.telegramMessageId().toString() : null,
                EventSchemas.TelegramMessageEvent::senderId,
                e -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("telegramMessageId", e.telegramMessageId());
                    d.put("chatId", e.chatId());
                    d.put("senderId", e.senderId());
                    d.put("senderType", e.senderType());
                    return d;
                });
    }

    @KafkaListener(
            topics = "messages.classified",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleIntentClassified(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        processAuditEvent(
                record,
                acknowledgment,
                EventSchemas.IntentClassifiedEvent.class,
                "emcip-intent-classifier",
                "Intent",
                EventSchemas.IntentClassifiedEvent::sourceEventId,
                e -> null,
                e -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("sourceEventId", e.sourceEventId());
                    d.put("intent", e.intent());
                    d.put("confidence", e.confidence());
                    d.put("matchedRules", e.matchedRules());
                    return d;
                });
    }

    @KafkaListener(
            topics = "policies.decisions",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handlePolicyDecision(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        processAuditEvent(
                record,
                acknowledgment,
                EventSchemas.PolicyDecisionEvent.class,
                "emcip-policy-engine",
                "Policy",
                EventSchemas.PolicyDecisionEvent::policyId,
                e -> null,
                e -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("sourceEventId", e.sourceEventId());
                    d.put("policyId", e.policyId());
                    d.put("decision", e.decision());
                    d.put("reason", e.reason());
                    d.put("actions", e.actions());
                    return d;
                });
    }

    @KafkaListener(
            topics = "responses.generated",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleResponseGenerated(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        processAuditEvent(
                record,
                acknowledgment,
                EventSchemas.ResponseGeneratedEvent.class,
                "emcip-llm-orchestrator",
                "LlmResponse",
                EventSchemas.ResponseGeneratedEvent::sourceEventId,
                e -> null,
                e -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("sourceEventId", e.sourceEventId());
                    d.put("modelUsed", e.modelUsed());
                    d.put("tokenCount", e.tokenCount());
                    return d;
                });
    }

    @KafkaListener(
            topics = "moderation.flags",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleModerationFlag(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        processAuditEvent(
                record,
                acknowledgment,
                EventSchemas.ModerationFlagEvent.class,
                "emcip-moderation-service",
                "ModerationFlag",
                EventSchemas.ModerationFlagEvent::sourceEventId,
                e -> null,
                e -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("sourceEventId", e.sourceEventId());
                    d.put("flagType", e.flagType());
                    d.put("severity", e.severity());
                    d.put("reason", e.reason());
                    return d;
                });
    }

    private <T extends EventSchemas.Event> void processAuditEvent(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment,
            Class<T> eventClass,
            String sourceService,
            String resourceType,
            Function<T, String> resourceIdFn,
            Function<T, String> actorIdFn,
            Function<T, Map<String, Object>> detailsFn) {
        try {
            TenantAwareKafkaSupport.bindTenantFromRecord(record);
            String tenantIdStr = TenantContext.getTenantId();
            UUID tenantUuid = tenantIdStr != null ? UUID.fromString(tenantIdStr) : null;
            if (tenantUuid == null) {
                log.warn(
                        "No tenant_id header on record offset {} topic {} — saving with null"
                                + " tenant",
                        record.offset(),
                        record.topic());
            }

            T event = objectMapper.readValue(record.value(), eventClass);

            AuditEventEntity entity =
                    AuditEventEntity.builder()
                            .eventId(event.eventId())
                            .eventType(event.eventType())
                            .correlationId(event.eventId())
                            .sourceService(sourceService)
                            .action(event.eventType())
                            .actorType("SYSTEM")
                            .actorId(actorIdFn.apply(event))
                            .resourceType(resourceType)
                            .resourceId(resourceIdFn.apply(event))
                            .outcome("PROCESSED")
                            .details(auditService.serializeDetails(detailsFn.apply(event)))
                            .tenantId(tenantUuid)
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (JacksonException e) {
            log.error(
                    "Permanently malformed {} record at offset {}, skipping: {}",
                    record.topic(),
                    record.offset(),
                    e.getMessage());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error(
                    "Failed to persist audit event for {} offset {}: {}",
                    record.topic(),
                    record.offset(),
                    e.getMessage(),
                    e);
            throw new RuntimeException(e);
        } finally {
            TenantContext.clear();
        }
    }
}
