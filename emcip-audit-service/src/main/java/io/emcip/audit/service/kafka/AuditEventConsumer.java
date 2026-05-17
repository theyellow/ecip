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
                            .tenantId(tenantUuid)
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (JacksonException e) {
            log.error(
                    "Permanently malformed telegram.raw.messages record at offset {}, skipping: {}",
                    record.offset(),
                    e.getMessage());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error(
                    "Failed to persist audit event for telegram.raw.messages offset {}: {}",
                    record.offset(),
                    e.getMessage(),
                    e);
            throw new RuntimeException(e);
        } finally {
            TenantContext.clear();
        }
    }

    @KafkaListener(
            topics = "messages.classified",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleIntentClassified(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
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
                            .tenantId(tenantUuid)
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (JacksonException e) {
            log.error(
                    "Permanently malformed messages.classified record at offset {}, skipping: {}",
                    record.offset(),
                    e.getMessage());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error(
                    "Failed to persist audit event for messages.classified offset {}: {}",
                    record.offset(),
                    e.getMessage(),
                    e);
            throw new RuntimeException(e);
        } finally {
            TenantContext.clear();
        }
    }

    @KafkaListener(
            topics = "policies.decisions",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handlePolicyDecision(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
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
                            .tenantId(tenantUuid)
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (JacksonException e) {
            log.error(
                    "Permanently malformed policies.decisions record at offset {}, skipping: {}",
                    record.offset(),
                    e.getMessage());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error(
                    "Failed to persist audit event for policies.decisions offset {}: {}",
                    record.offset(),
                    e.getMessage(),
                    e);
            throw new RuntimeException(e);
        } finally {
            TenantContext.clear();
        }
    }

    @KafkaListener(
            topics = "responses.generated",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleResponseGenerated(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
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
                            .tenantId(tenantUuid)
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (JacksonException e) {
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
        } finally {
            TenantContext.clear();
        }
    }

    @KafkaListener(
            topics = "moderation.flags",
            groupId = "emcip-audit-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleModerationFlag(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
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
                            .tenantId(tenantUuid)
                            .createdAt(Instant.now())
                            .build();

            auditService.save(entity).block();
            acknowledgment.acknowledge();

        } catch (JacksonException e) {
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
        } finally {
            TenantContext.clear();
        }
    }
}
