# SC5: AuditEventConsumer Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract a single generic `processAuditEvent` helper from the 5 nearly-identical `@KafkaListener` methods in `AuditEventConsumer`, cutting the class from 359 lines to ~130 without changing any observable behaviour.

**Architecture:** Each handler delegates to a private generic method that takes lambdas for the per-event differences (`resourceIdFn`, `actorIdFn`, `detailsFn`). All shared logic (tenant binding, JSON parse, entity build, save, ack, error handling, cleanup) lives in one place.

**Tech Stack:** Java 21, Spring Boot 4, Spring Kafka, JUnit 5 + Mockito

---

## Context

### What is duplicated

`AuditEventConsumer` has 5 `@KafkaListener` methods. They share ~80% of their code — the only differences are:

| Handler | `eventClass` | `sourceService` | `resourceType` | `resourceId` | `actorId` |
|---------|-------------|-----------------|----------------|--------------|-----------|
| `handleTelegramMessage` | `TelegramMessageEvent` | `emcip-tdlib-adapter` | `TelegramMessage` | `telegramMessageId.toString()` | `senderId()` |
| `handleIntentClassified` | `IntentClassifiedEvent` | `emcip-intent-classifier` | `Intent` | `sourceEventId()` | null |
| `handlePolicyDecision` | `PolicyDecisionEvent` | `emcip-policy-engine` | `Policy` | `policyId()` | null |
| `handleResponseGenerated` | `ResponseGeneratedEvent` | `emcip-llm-orchestrator` | `LlmResponse` | `sourceEventId()` | null |
| `handleModerationFlag` | `ModerationFlagEvent` | `emcip-moderation-service` | `ModerationFlag` | `sourceEventId()` | null |

And a `details` Map with different fields per event.

### Existing tests

`AuditEventConsumerTest` already covers 4 cases via `handleTelegramMessage` and `handleModerationFlag`:
- valid event → save + ack
- malformed JSON → skip + ack (no save)
- save throws → propagate, no ack
- correct `sourceService` for moderation

These tests must still pass after the refactor.

### Run tests
```bash
mvn test -pl emcip-audit-service -q 2>&1 | tail -10
```

### Apply Spotless
```bash
mvn spotless:apply -pl emcip-audit-service
```

---

## File Structure

**Modify:**

| File | Change |
|------|--------|
| `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java` | Extract `processAuditEvent` generic helper; each handler becomes a 1-liner |
| `emcip-audit-service/src/test/java/io/emcip/audit/service/kafka/AuditEventConsumerTest.java` | Add 1 new test via a second handler to confirm generic path works for non-Telegram events |

---

## Task 1: Add a second-handler test, then extract the generic helper

**Files:**
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java`
- Modify: `emcip-audit-service/src/test/java/io/emcip/audit/service/kafka/AuditEventConsumerTest.java`

- [ ] **Step 1: Read both files**

```bash
cat -n /home/ben/Development/ecip/emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java
cat -n /home/ben/Development/ecip/emcip-audit-service/src/test/java/io/emcip/audit/service/kafka/AuditEventConsumerTest.java
```

- [ ] **Step 2: Add a failing test for a second handler**

Read `AuditEventConsumerTest.java`. Add this test before the closing `}`:

```java
    // --- handleIntentClassified ---

    @Test
    void handleIntentClassified_validEvent_savesWithCorrectSourceService() throws Exception {
        io.emcip.common.events.EventSchemas.IntentClassifiedEvent event =
                new io.emcip.common.events.EventSchemas.IntentClassifiedEvent(
                        "cls-001",
                        "2026-05-19T10:00:00Z",
                        null,
                        null,
                        "evt-001",
                        "GREETING",
                        0.95,
                        java.util.List.of("greeting-rule"));
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("messages.classified", 0, 0L, "key", json);

        when(auditService.serializeDetails(any())).thenReturn(Json.of("{\"detail\":\"value\"}"));
        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        when(auditService.save(captor.capture()))
                .thenReturn(Mono.just(AuditEventEntity.builder().id(3L).build()));

        consumer.handleIntentClassified(record, acknowledgment);

        assertThat(captor.getValue().getSourceService()).isEqualTo("emcip-intent-classifier");
        assertThat(captor.getValue().getResourceType()).isEqualTo("Intent");
        assertThat(captor.getValue().getResourceId()).isEqualTo("evt-001");
        assertThat(captor.getValue().getActorId()).isNull();
        verify(acknowledgment).acknowledge();
    }
```

You need to add `import io.emcip.common.events.EventSchemas.IntentClassifiedEvent;` at the top (check the exact record constructor signature by reading `EventSchemas.java` at `emcip-core/src/main/java/io/emcip/common/events/EventSchemas.java` first if unsure about the constructor parameters).

- [ ] **Step 3: Run test to confirm it passes (it should — handler already exists)**

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-audit-service -Dtest=AuditEventConsumerTest#handleIntentClassified_validEvent_savesWithCorrectSourceService -q 2>&1 | tail -5
```

Expected: PASS (the handler already works; this test is our regression safety net for the refactor).

- [ ] **Step 4: Run full test suite to confirm clean baseline**

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-audit-service -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 5: Replace `AuditEventConsumer.java` with the refactored version**

Replace the full file with:

```java
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
```

- [ ] **Step 6: Run the full test suite**

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-audit-service -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 0 failures. All 5 existing tests + 1 new test pass.

- [ ] **Step 7: Verify line count reduced**

```bash
wc -l /home/ben/Development/ecip/emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java
```

Expected: ~130 lines (down from 359).

- [ ] **Step 8: Apply Spotless and commit**

```bash
cd /home/ben/Development/ecip && mvn spotless:apply -pl emcip-audit-service
git add emcip-audit-service/
git commit -m "refactor(audit-service): extract generic processAuditEvent helper, cut AuditEventConsumer from 359 to ~130 lines"
```

---

## Self-Review

### Spec coverage

| Requirement | Task |
|-------------|------|
| 5 handlers reduced to 1-liner delegates | Task 1 Step 5 ✅ |
| Shared logic in one `processAuditEvent` method | Task 1 Step 5 ✅ |
| All existing tests pass | Task 1 Step 6 ✅ |
| New test for second handler confirms generic path | Task 1 Steps 2–3 ✅ |
| Behaviour identical to before | Same error handling, same entity fields, same ack logic ✅ |

### Out of scope

- Changing `AuditEventConsumer` from ThreadLocal to Reactor Context — Kafka consumers run on dedicated listener threads; ThreadLocal is correct here.
- Adding tests for the 3 remaining handlers — the existing 4 tests + the new one cover all code paths.

### Placeholder scan

None — all steps contain complete code.

### Type consistency

- `EventSchemas.Event` — base interface with `eventId()` and `eventType()` — used consistently in `processAuditEvent`.
- `Function<T, String>` — same type for both `resourceIdFn` and `actorIdFn`.
- `Function<T, Map<String, Object>>` — for `detailsFn`. All 5 callers pass a lambda returning `LinkedHashMap` which is-a `Map`. ✅
