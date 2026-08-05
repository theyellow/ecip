package io.emcip.audit.service.kafka;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.service.AuditService;
import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
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
                EventSchemas.TelegramMessageEvent::eventId,
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
                EventSchemas.IntentClassifiedEvent::sourceEventId,
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
                EventSchemas.PolicyDecisionEvent::sourceEventId,
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
                EventSchemas.ResponseGeneratedEvent::sourceEventId,
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
                EventSchemas.ModerationFlagEvent::sourceEventId,
                e -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("sourceEventId", e.sourceEventId());
                    d.put("flagType", e.flagType());
                    d.put("severity", e.severity());
                    d.put("reason", e.reason());
                    return d;
                });
    }

    @KafkaListener(
            topics = "audit.events",
            groupId = "emcip-audit-service-admin",
            containerFactory = "adminAuditListenerContainerFactory")
    public void handleAdminAuditEvent(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        processAdminAuditEvent(record, acknowledgment);
    }

    /**
     * Persists admin-originated audit events from {@code emcip-admin-api}, preserving their native
     * action/actor/outcome semantics. Deliberately does NOT reuse {@link #processAuditEvent}, which
     * flattens {@code action -> eventType} and {@code outcome -> "PROCESSED"} for the generic
     * domain-topic events — that mapping would destroy the LOGIN_FAILURE/FAILURE distinction admin
     * audit events carry.
     */
    private void processAdminAuditEvent(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        UUID tenantUuid;
        try {
            tenantUuid = TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting admin audit record: {}", e.getMessage());
            acknowledgment.acknowledge();
            return;
        }

        EventSchemas.AuditEvent event =
                objectMapper.readValue(record.value(), EventSchemas.AuditEvent.class);

        AuditEventEntity entity =
                AuditEventEntity.builder()
                        .eventId(event.eventId())
                        .eventType(event.eventType())
                        .correlationId(event.eventId())
                        .sourceService("emcip-admin-api")
                        .action(event.action())
                        .actorType("ADMIN")
                        .actorId(event.actor())
                        .resourceType(event.resourceType())
                        .resourceId(event.resourceId())
                        .outcome(event.outcome())
                        .details(auditService.serializeDetails(event.changes()))
                        .tenantId(tenantUuid)
                        .createdAt(Instant.now())
                        .build();

        try {
            auditService.saveWithChain(entity).block();
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            log.warn("Admin audit event {} already persisted; skipping duplicate", event.eventId());
            acknowledgment.acknowledge();
            return;
        }
        acknowledgment.acknowledge();
    }

    private <T extends EventSchemas.Event> void processAuditEvent(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment,
            Class<T> eventClass,
            String sourceService,
            String resourceType,
            Function<T, String> resourceIdFn,
            Function<T, String> actorIdFn,
            Function<T, String> correlationIdFn,
            Function<T, Map<String, Object>> detailsFn) {

        UUID tenantUuid;
        try {
            tenantUuid = TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting record: {}", e.getMessage());
            acknowledgment.acknowledge();
            return;
        }

        // Parse (JacksonException -> non-retryable -> DLQ) and persist with the hash chain
        // (failure -> retry(backoff) -> DLQ). Both propagate to the container's
        // DefaultErrorHandler;
        // MANUAL_IMMEDIATE only commits on the success path below.
        T event = objectMapper.readValue(record.value(), eventClass);

        AuditEventEntity entity =
                AuditEventEntity.builder()
                        .eventId(event.eventId())
                        .eventType(event.eventType())
                        .correlationId(correlationIdFn.apply(event))
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

        try {
            auditService.saveWithChain(entity).block();
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Redelivered record (e.g. after a rebalance) whose event_id is already persisted —
            // it is already safely audited. Ack + skip so it is not retried/DLQ'd as if it were a
            // genuine failure.
            log.warn("Audit event {} already persisted; skipping duplicate", event.eventId());
            acknowledgment.acknowledge();
            return;
        }
        acknowledgment.acknowledge();
    }
}
