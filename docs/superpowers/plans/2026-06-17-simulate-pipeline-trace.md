# Simulate Pipeline Trace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Simulate page with a two-column layout — compose form left, live pipeline trace right — backed by a synchronous simulate endpoint that polls the audit log until all four pipeline stages complete.

**Architecture:** Fix the `AuditEventConsumer` `correlationId` bug so all pipeline events for a simulation share the same `correlationId`. Extend the audit API with a `correlationId` filter. `SimulationService` publishes then polls until all four stages arrive (max 15 s), returning a structured `SimulateTraceResult`. The frontend reveals all stages simultaneously in a `PipelineTrace` component.

**Tech Stack:** Java 21, Spring Boot 4, WebFlux/R2DBC (audit-service), Reactor (`Flux.interval`, `concatMap`, `takeUntil`, `withVirtualTime` in tests), Jackson 3 (`tools.jackson`), React 18, CSS Modules, Vitest + Testing Library.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `emcip-audit-service/.../kafka/AuditEventConsumer.java` | Modify | Fix correlationId: use sourceEventId for downstream events |
| `emcip-audit-service/.../kafka/AuditEventConsumerTest.java` | Modify | Add correlationId assertions |
| `emcip-audit-service/.../repository/AuditEventRepository.java` | Modify | Add `findByCorrelationId` derived method |
| `emcip-audit-service/.../service/AuditService.java` | Modify | Add `findByCorrelationId` delegation |
| `emcip-audit-service/.../controller/AuditController.java` | Modify | Add `correlationId` query param |
| `emcip-audit-service/.../controller/AuditControllerTest.java` | Modify | Test correlationId filter path |
| `emcip-admin-api/.../client/AuditServiceClient.java` | Modify | Add `findByCorrelationId` method |
| `emcip-admin-api/.../service/SimulationService.java` | Modify | Polling logic, `SimulateTraceResult`/`TraceStage` records |
| `emcip-admin-api/.../service/SimulationServiceTest.java` | Modify | Update for new constructor + polling behaviour |
| `emcip-admin-api/.../controller/SimulateController.java` | Modify | Return `SimulateTraceResult` instead of raw `Map` |
| `emcip-admin-api/.../controller/SimulateControllerTest.java` | Modify | Assert new response shape |
| `emcip-admin-ui/.../pages/Simulate/PipelineTrace.jsx` | Create | Trace panel component |
| `emcip-admin-ui/.../pages/Simulate/PipelineTrace.module.css` | Create | Stage row styles |
| `emcip-admin-ui/.../pages/Simulate/Simulate.jsx` | Modify | Two-column grid, wire PipelineTrace |
| `emcip-admin-ui/.../pages/Simulate/Simulate.module.css` | Modify | Add columns grid, remove .pipeline |
| `emcip-admin-ui/.../pages/Simulate/Simulate.test.jsx` | Modify | Update result assertions |

All frontend paths are under `emcip-admin-ui/src/main/frontend/src/`.

---

## Task 1: Fix correlationId in AuditEventConsumer

**Files:**
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java`
- Modify: `emcip-audit-service/src/test/java/io/emcip/audit/service/kafka/AuditEventConsumerTest.java`

- [ ] **Step 1: Add correlationId assertion tests (TDD — write failing tests first)**

Add two new tests to `AuditEventConsumerTest`. These will fail because `correlationId` currently always equals `eventId`:

```java
@Test
void handleTelegramMessage_setsCorrelationIdToOwnEventId() throws Exception {
    TelegramMessageEvent event =
            new TelegramMessageEvent(
                    "evt-root", "2026-06-17T10:00:00Z", null, null,
                    1L, 100L, "user-1", "USER", "hello", 0, null, false,
                    null, null, Map.of(), "", null, null, null);
    String json = objectMapper.writeValueAsString(event);
    ConsumerRecord<String, String> record =
            new ConsumerRecord<>("telegram.raw.messages", 0, 0L, "key", json);
    when(auditService.serializeDetails(any())).thenReturn(Json.of("{}"));
    ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
    when(auditService.save(captor.capture()))
            .thenReturn(Mono.just(AuditEventEntity.builder().id(1L).build()));

    consumer.handleTelegramMessage(record, acknowledgment);

    assertThat(captor.getValue().getCorrelationId()).isEqualTo("evt-root");
}

@Test
void handleIntentClassified_setsCorrelationIdToSourceEventId() throws Exception {
    io.emcip.common.events.EventSchemas.IntentClassifiedEvent event =
            new io.emcip.common.events.EventSchemas.IntentClassifiedEvent(
                    "cls-001", "2026-06-17T10:00:00Z", null, null,
                    "evt-root",   // sourceEventId — this should become correlationId
                    "SPAM", 0.95, null, java.util.List.of("SPAM"));
    String json = objectMapper.writeValueAsString(event);
    ConsumerRecord<String, String> record =
            new ConsumerRecord<>("messages.classified", 0, 0L, "key", json);
    when(auditService.serializeDetails(any())).thenReturn(Json.of("{}"));
    ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
    when(auditService.save(captor.capture()))
            .thenReturn(Mono.just(AuditEventEntity.builder().id(2L).build()));

    consumer.handleIntentClassified(record, acknowledgment);

    assertThat(captor.getValue().getCorrelationId()).isEqualTo("evt-root");
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd emcip-audit-service && mvn test -pl . -Dtest=AuditEventConsumerTest#handleTelegramMessage_setsCorrelationIdToOwnEventId+handleIntentClassified_setsCorrelationIdToSourceEventId -q 2>&1 | tail -20
```

Expected: both FAIL — `handleIntentClassified_setsCorrelationIdToSourceEventId` fails because correlationId is `cls-001` not `evt-root`.

- [ ] **Step 3: Fix AuditEventConsumer — add correlationIdFn parameter**

Replace the `processAuditEvent` signature (add `correlationIdFn` as the 7th parameter, before `detailsFn`):

```java
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
```

Inside the method body, change:
```java
.correlationId(event.eventId())
```
to:
```java
.correlationId(correlationIdFn.apply(event))
```

Then update every `processAuditEvent(...)` call to pass the correct `correlationIdFn`:

```java
// handleTelegramMessage — root event; own eventId is correct
consumer.handleTelegramMessage calls processAuditEvent with:
    correlationIdFn = EventSchemas.TelegramMessageEvent::eventId

// handleIntentClassified
    correlationIdFn = EventSchemas.IntentClassifiedEvent::sourceEventId

// handlePolicyDecision
    correlationIdFn = EventSchemas.PolicyDecisionEvent::sourceEventId

// handleResponseGenerated
    correlationIdFn = EventSchemas.ResponseGeneratedEvent::sourceEventId

// handleModerationFlag
    correlationIdFn = EventSchemas.ModerationFlagEvent::sourceEventId
```

The five updated handler calls look like:

```java
// handleTelegramMessage
processAuditEvent(
        record, acknowledgment,
        EventSchemas.TelegramMessageEvent.class,
        "emcip-tdlib-adapter", "TelegramMessage",
        e -> e.telegramMessageId() != null ? e.telegramMessageId().toString() : null,
        EventSchemas.TelegramMessageEvent::senderId,
        EventSchemas.TelegramMessageEvent::eventId,   // correlationId = own eventId (root)
        e -> { Map<String, Object> d = new LinkedHashMap<>();
               d.put("telegramMessageId", e.telegramMessageId());
               d.put("chatId", e.chatId());
               d.put("senderId", e.senderId());
               d.put("senderType", e.senderType());
               return d; });

// handleIntentClassified
processAuditEvent(
        record, acknowledgment,
        EventSchemas.IntentClassifiedEvent.class,
        "emcip-intent-classifier", "Intent",
        EventSchemas.IntentClassifiedEvent::sourceEventId,
        e -> null,
        EventSchemas.IntentClassifiedEvent::sourceEventId,  // correlationId = sourceEventId
        e -> { Map<String, Object> d = new LinkedHashMap<>();
               d.put("sourceEventId", e.sourceEventId());
               d.put("intent", e.intent());
               d.put("confidence", e.confidence());
               d.put("matchedRules", e.matchedRules());
               return d; });

// handlePolicyDecision
processAuditEvent(
        record, acknowledgment,
        EventSchemas.PolicyDecisionEvent.class,
        "emcip-policy-engine", "Policy",
        EventSchemas.PolicyDecisionEvent::policyId,
        e -> null,
        EventSchemas.PolicyDecisionEvent::sourceEventId,  // correlationId = sourceEventId
        e -> { Map<String, Object> d = new LinkedHashMap<>();
               d.put("sourceEventId", e.sourceEventId());
               d.put("policyId", e.policyId());
               d.put("decision", e.decision());
               d.put("reason", e.reason());
               d.put("actions", e.actions());
               return d; });

// handleResponseGenerated
processAuditEvent(
        record, acknowledgment,
        EventSchemas.ResponseGeneratedEvent.class,
        "emcip-llm-orchestrator", "LlmResponse",
        EventSchemas.ResponseGeneratedEvent::sourceEventId,
        e -> null,
        EventSchemas.ResponseGeneratedEvent::sourceEventId,  // correlationId = sourceEventId
        e -> { Map<String, Object> d = new LinkedHashMap<>();
               d.put("sourceEventId", e.sourceEventId());
               d.put("modelUsed", e.modelUsed());
               d.put("tokenCount", e.tokenCount());
               return d; });

// handleModerationFlag
processAuditEvent(
        record, acknowledgment,
        EventSchemas.ModerationFlagEvent.class,
        "emcip-moderation-service", "ModerationFlag",
        EventSchemas.ModerationFlagEvent::sourceEventId,
        e -> null,
        EventSchemas.ModerationFlagEvent::sourceEventId,  // correlationId = sourceEventId
        e -> { Map<String, Object> d = new LinkedHashMap<>();
               d.put("sourceEventId", e.sourceEventId());
               d.put("flagType", e.flagType());
               d.put("severity", e.severity());
               d.put("reason", e.reason());
               return d; });
```

- [ ] **Step 4: Run all AuditEventConsumer tests**

```bash
cd emcip-audit-service && mvn test -pl . -Dtest=AuditEventConsumerTest -q 2>&1 | tail -10
```

Expected: all tests PASS.

---

## Task 2: Add correlationId query support to audit-service

**Files:**
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/repository/AuditEventRepository.java`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/controller/AuditController.java`
- Modify: `emcip-audit-service/src/test/java/io/emcip/audit/service/controller/AuditControllerTest.java`

- [ ] **Step 1: Add controller test for correlationId filter (write first)**

Add to `AuditControllerTest`:

```java
@Test
void getEvents_withCorrelationId_returnsMatchingEvents() {
    AuditEventEntity e = new AuditEventEntity();
    e.setEventId("cls-001");
    e.setCorrelationId("evt-root");
    when(auditService.findByCorrelationId("evt-root"))
            .thenReturn(reactor.core.publisher.Flux.just(e));

    client.get()
            .uri("/api/audit/events?correlationId=evt-root")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.items[0].correlationId")
            .isEqualTo("evt-root")
            .jsonPath("$.total")
            .isEqualTo(1);
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd emcip-audit-service && mvn test -pl . -Dtest=AuditControllerTest#getEvents_withCorrelationId_returnsMatchingEvents -q 2>&1 | tail -10
```

Expected: FAIL — `auditService.findByCorrelationId` does not exist yet.

- [ ] **Step 3: Add findByCorrelationId to AuditEventRepository**

Add one line to the repository interface:

```java
Flux<AuditEventEntity> findByCorrelationId(String correlationId);
```

- [ ] **Step 4: Add findByCorrelationId to AuditService**

Add method to `AuditService`:

```java
public Flux<AuditEventEntity> findByCorrelationId(String correlationId) {
    return repository.findByCorrelationId(correlationId);
}
```

No tenant filtering — this is an internal debug path.

- [ ] **Step 5: Add correlationId param to AuditController**

Add `@RequestParam(required = false) String correlationId` to `getEvents`, and add an early-return branch before the existing date-range logic:

```java
@GetMapping("/events")
public Mono<PageResponse<AuditEventEntity>> getEvents(
        @RequestParam(required = false) String eventType,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String correlationId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {

    if (correlationId != null && !correlationId.isBlank()) {
        return auditService.findByCorrelationId(correlationId)
                .collectList()
                .map(items -> new PageResponse<>(items, (long) items.size(), 0, items.size()));
    }

    // existing date-range logic unchanged below
    Instant fromInstant = from != null ? Instant.parse(from) : Instant.now().minus(24, ChronoUnit.HOURS);
    Instant toInstant   = to   != null ? Instant.parse(to)   : Instant.now();
    int effectiveSize   = Math.min(size, 200);
    return auditService.findPage(fromInstant, toInstant, page, effectiveSize, eventType);
}
```

- [ ] **Step 6: Run all audit-service tests**

```bash
cd emcip-audit-service && mvn test -pl . -q 2>&1 | tail -15
```

Expected: all tests PASS.

- [ ] **Step 7: Spotless + commit audit-service**

```bash
cd emcip-audit-service && mvn spotless:apply -q
cd ..
git add emcip-audit-service/src/
git commit -m "$(cat <<'EOF'
fix(audit-service): correlationId chain + findByCorrelationId query

- AuditEventConsumer: downstream events now store sourceEventId as
  correlationId instead of their own eventId — fixes broken correlation
  chain across pipeline stages
- AuditEventRepository: add findByCorrelationId derived method
- AuditService: delegate findByCorrelationId to repository
- AuditController: add ?correlationId= filter to GET /api/audit/events

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Extend AuditServiceClient in admin-api

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java`

- [ ] **Step 1: Add findByCorrelationId method**

Add the following method to `AuditServiceClient`, after `listEvents`:

```java
public Mono<JsonNode> findByCorrelationId(String correlationId) {
    return Mono.deferContextual(
                    ctx -> {
                        String tenantId = ReactorTenantContext.getTenantId(ctx);
                        var spec =
                                webClient
                                        .get()
                                        .uri(
                                                uriBuilder ->
                                                        uriBuilder
                                                                .path("/api/audit/events")
                                                                .queryParam(
                                                                        "correlationId",
                                                                        correlationId)
                                                                .queryParam("size", 20)
                                                                .build());
                        return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                                .retrieve()
                                .bodyToMono(JsonNode.class);
                    })
            .transformDeferred(RetryOperator.of(retry))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .onErrorResume(
                    e -> {
                        log.warn(
                                "audit-service correlationId query failed: {}",
                                e.getMessage());
                        return emptyPage();
                    });
}
```

- [ ] **Step 2: Compile to verify**

```bash
cd emcip-admin-api && mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

---

## Task 4: Refactor SimulationService with polling

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/SimulationService.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/SimulationServiceTest.java`

- [ ] **Step 1: Write new SimulationService tests first**

Replace the entire content of `SimulationServiceTest.java`:

```java
package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.client.AuditServiceClient;
import io.emcip.admin.api.dto.SimulateMessageRequest;
import io.emcip.admin.api.service.SimulationService.SimulateTraceResult;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private AuditServiceClient auditServiceClient;

    private SimulationService simulationService;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        objectMapper = new ObjectMapper();
        simulationService =
                new SimulationService(kafkaTemplate, objectMapper, auditServiceClient);
        SendResult<String, String> sendResult =
                new SendResult<>(null, new RecordMetadata(null, 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    private SimulateMessageRequest request(long chatId) {
        SimulateMessageRequest req = new SimulateMessageRequest();
        req.setChatId(chatId);
        req.setText("hello world");
        return req;
    }

    /** Builds a JsonNode with all four pipeline stage event types present. */
    private JsonNode allStagesJson() {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        for (String type : new String[]{"TelegramMessage", "IntentClassified", "PolicyDecision", "ModerationFlag"}) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("eventType", type);
            item.put("eventId", "fake-id-" + type);
            item.put("correlationId", "sim-uuid");
            item.set("details", objectMapper.createObjectNode()
                    .put("intent", "SPAM")
                    .put("confidence", 0.95)
                    .put("decision", "BLOCK")
                    .put("policyId", "spam-policy")
                    .put("flagType", "SPAM")
                    .put("severity", "HIGH")
                    .put("reason", "test"));
            items.add(item);
        }
        root.put("total", 4);
        return root;
    }

    /** Builds a JsonNode with no items (pipeline not yet complete). */
    private JsonNode emptyStagesJson() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("items");
        root.put("total", 0);
        return root;
    }

    @Test
    void simulate_publishesToKafka() {
        when(auditServiceClient.findByCorrelationId(anyString()))
                .thenReturn(Mono.just(allStagesJson()));

        StepVerifier.withVirtualTime(() -> simulationService.simulate(request(99L)))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(r -> assertThat(r.eventId()).isNotNull())
                .verifyComplete();

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void simulate_returnsAllStagesWhenPipelineCompletes() {
        when(auditServiceClient.findByCorrelationId(anyString()))
                .thenReturn(Mono.just(allStagesJson()));

        StepVerifier.withVirtualTime(() -> simulationService.simulate(request(12345L)))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(
                        result -> {
                            assertThat(result.eventId()).isNotNull();
                            assertThat(result.topic()).isEqualTo(SimulationService.TOPIC);
                            assertThat(result.partial()).isFalse();
                            assertThat(result.stages()).hasSize(4);
                            assertThat(result.stages())
                                    .extracting(SimulationService.TraceStage::stage)
                                    .containsExactlyInAnyOrder(
                                            "PUBLISH", "CLASSIFIER", "POLICY", "MODERATION");
                        })
                .verifyComplete();
    }

    @Test
    void simulate_returnsPartialWhenPipelineTimesOut() {
        when(auditServiceClient.findByCorrelationId(anyString()))
                .thenReturn(Mono.just(emptyStagesJson()));

        StepVerifier.withVirtualTime(() -> simulationService.simulate(request(77L)))
                .thenAwait(Duration.ofSeconds(16))
                .assertNext(
                        result -> {
                            assertThat(result.partial()).isTrue();
                            assertThat(result.stages()).isEmpty();
                        })
                .verifyComplete();
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd emcip-admin-api && mvn test -pl . -Dtest=SimulationServiceTest -q 2>&1 | tail -15
```

Expected: FAIL — `SimulateTraceResult`, `TraceStage`, and the new constructor don't exist yet.

- [ ] **Step 3: Rewrite SimulationService**

Replace the entire content of `SimulationService.java`:

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.client.AuditServiceClient;
import io.emcip.admin.api.dto.SimulateMessageRequest;
import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.ReactorTenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
@Slf4j
@RequiredArgsConstructor
public class SimulationService {

    public static final String TOPIC = "telegram.raw.messages";

    private static final Set<String> EXPECTED_EVENT_TYPES =
            Set.of("TelegramMessage", "IntentClassified", "PolicyDecision", "ModerationFlag");

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AuditServiceClient auditServiceClient;

    public record SimulateTraceResult(
            String eventId, String topic, boolean partial, List<TraceStage> stages) {}

    public record TraceStage(String stage, Map<String, Object> data) {}

    public Mono<SimulateTraceResult> simulate(SimulateMessageRequest req) {
        return Mono.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    String eventId = UUID.randomUUID().toString();
                    String timestamp = Instant.now().toString();

                    EventSchemas.TelegramMessageEvent event =
                            new EventSchemas.TelegramMessageEvent(
                                    eventId,
                                    timestamp,
                                    null,
                                    null,
                                    req.getTelegramMessageId() != null
                                            ? req.getTelegramMessageId()
                                            : System.currentTimeMillis(),
                                    req.getChatId(),
                                    req.getSenderId() != null ? req.getSenderId() : "sim-user",
                                    req.getSenderType() != null ? req.getSenderType() : "USER",
                                    req.getText(),
                                    (int) (System.currentTimeMillis() / 1000),
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null);

                    try {
                        String payload = objectMapper.writeValueAsString(event);
                        ProducerRecord<String, String> record =
                                new ProducerRecord<>(
                                        TOPIC, null, String.valueOf(req.getChatId()), payload);
                        if (tenantId != null) {
                            record.headers()
                                    .add(
                                            "tenant_id",
                                            tenantId.getBytes(StandardCharsets.UTF_8));
                        }
                        kafkaTemplate.send(record);
                    } catch (JacksonException e) {
                        log.error("Failed to serialize simulation event", e);
                        return Mono.<SimulateTraceResult>error(
                                new RuntimeException("Failed to serialize event", e));
                    }

                    return Flux.interval(Duration.ofMillis(500))
                            .take(30)
                            .concatMap(tick -> auditServiceClient.findByCorrelationId(eventId))
                            .takeUntil(this::hasAllStages)
                            .last(emptyPageNode())
                            .map(json -> buildTraceResult(eventId, json));
                });
    }

    private boolean hasAllStages(JsonNode json) {
        JsonNode items = json.path("items");
        if (!items.isArray()) return false;
        Set<String> found = new java.util.HashSet<>();
        items.forEach(item -> {
            String et = item.path("eventType").asText("");
            if (!et.isBlank()) found.add(et);
        });
        return found.containsAll(EXPECTED_EVENT_TYPES);
    }

    private SimulateTraceResult buildTraceResult(String eventId, JsonNode json) {
        JsonNode items = json.path("items");
        List<TraceStage> stages = new ArrayList<>();
        boolean partial = !hasAllStages(json);

        if (items.isArray()) {
            items.forEach(item -> {
                String eventType = item.path("eventType").asText("");
                JsonNode details = item.path("details");
                TraceStage stage = mapToStage(eventType, eventId, details);
                if (stage != null) stages.add(stage);
            });
        }

        return new SimulateTraceResult(eventId, TOPIC, partial, stages);
    }

    private TraceStage mapToStage(String eventType, String eventId, JsonNode details) {
        return switch (eventType) {
            case "TelegramMessage" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("topic", TOPIC);
                data.put("eventId", eventId);
                yield new TraceStage("PUBLISH", data);
            }
            case "IntentClassified" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("intent", details.path("intent").asText("UNKNOWN"));
                data.put("confidence", details.path("confidence").asDouble(0.0));
                data.put("matchedRules", toList(details.path("matchedRules")));
                yield new TraceStage("CLASSIFIER", data);
            }
            case "PolicyDecision" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("policyId", details.path("policyId").asText(""));
                data.put("decision", details.path("decision").asText(""));
                data.put("actions", toList(details.path("actions")));
                data.put("reason", details.path("reason").asText(""));
                yield new TraceStage("POLICY", data);
            }
            case "ModerationFlag" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("flagType", details.path("flagType").asText(""));
                data.put("severity", details.path("severity").asText(""));
                data.put("reason", details.path("reason").asText(""));
                yield new TraceStage("MODERATION", data);
            }
            default -> null;
        };
    }

    private List<String> toList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(n -> result.add(n.asText()));
        return result;
    }

    private JsonNode emptyPageNode() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.putArray("items");
        node.put("total", 0);
        return node;
    }
}
```

- [ ] **Step 4: Run SimulationService tests**

```bash
cd emcip-admin-api && mvn test -pl . -Dtest=SimulationServiceTest -q 2>&1 | tail -15
```

Expected: all 3 tests PASS.

---

## Task 5: Update SimulateController

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/SimulateController.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/SimulateControllerTest.java`

- [ ] **Step 1: Rewrite SimulateControllerTest**

Replace the entire content of `SimulateControllerTest.java`:

```java
package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.dto.SimulateMessageRequest;
import io.emcip.admin.api.service.SimulationService;
import io.emcip.admin.api.service.SimulationService.SimulateTraceResult;
import io.emcip.admin.api.service.SimulationService.TraceStage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SimulateControllerTest {

    @Mock private SimulationService simulationService;

    private SimulateController controller;
    private WebTestClient webTestClient;

    private static final SimulateTraceResult FULL_TRACE =
            new SimulateTraceResult(
                    "test-event-id",
                    SimulationService.TOPIC,
                    false,
                    List.of(
                            new TraceStage("PUBLISH", Map.of("topic", SimulationService.TOPIC, "eventId", "test-event-id")),
                            new TraceStage("CLASSIFIER", Map.of("intent", "SPAM", "confidence", 0.95, "matchedRules", List.of("SPAM"))),
                            new TraceStage("POLICY", Map.of("policyId", "spam-policy", "decision", "BLOCK", "actions", List.of("BLOCK"), "reason", "keyword match")),
                            new TraceStage("MODERATION", Map.of("flagType", "SPAM", "severity", "HIGH", "reason", "blocked by policy"))));

    @BeforeEach
    void setUp() {
        controller = new SimulateController(simulationService);
        webTestClient =
                WebTestClient.bindToController(controller)
                        .controllerAdvice(new GlobalExceptionHandler())
                        .build();
        when(simulationService.simulate(any())).thenReturn(Mono.just(FULL_TRACE));
    }

    private SimulateMessageRequest request(long chatId) {
        SimulateMessageRequest req = new SimulateMessageRequest();
        req.setChatId(chatId);
        req.setText("hello world");
        return req;
    }

    @Test
    void simulateMessage_returnsTraceResult() {
        StepVerifier.create(controller.simulateMessage(request(12345L)))
                .assertNext(
                        result -> {
                            assertThat(result.eventId()).isEqualTo("test-event-id");
                            assertThat(result.partial()).isFalse();
                            assertThat(result.stages()).hasSize(4);
                        })
                .verifyComplete();
    }

    @Test
    void simulateMessage_delegatesToService() {
        controller.simulateMessage(request(99L)).block();
        verify(simulationService).simulate(any());
    }

    @Test
    void simulateMessage_returns202() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(555L))
                .exchange()
                .expectStatus()
                .isAccepted();
    }

    @Test
    void simulateMessage_responseBodyContainsStages() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(666L))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.eventId").isEqualTo("test-event-id")
                .jsonPath("$.partial").isEqualTo(false)
                .jsonPath("$.stages[0].stage").isEqualTo("PUBLISH")
                .jsonPath("$.stages[1].stage").isEqualTo("CLASSIFIER")
                .jsonPath("$.stages[2].stage").isEqualTo("POLICY")
                .jsonPath("$.stages[3].stage").isEqualTo("MODERATION");
    }

    @Test
    void simulate_nullChatId_returns400() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("text", "hello"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
cd emcip-admin-api && mvn test -pl . -Dtest=SimulateControllerTest -q 2>&1 | tail -10
```

Expected: FAIL — controller still returns `Mono<Map<String, Object>>`.

- [ ] **Step 3: Update SimulateController**

Replace the entire content of `SimulateController.java`:

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.SimulateMessageRequest;
import io.emcip.admin.api.service.SimulationService;
import io.emcip.admin.api.service.SimulationService.SimulateTraceResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/simulate")
@Tag(name = "Simulation", description = "Inject test messages through the full pipeline")
@RequiredArgsConstructor
public class SimulateController {

    private final SimulationService simulationService;

    @Operation(summary = "Simulate a Telegram message through the processing pipeline")
    @PostMapping("/message")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SimulateTraceResult> simulateMessage(
            @Valid @RequestBody SimulateMessageRequest req) {
        return simulationService.simulate(req);
    }
}
```

- [ ] **Step 4: Run all admin-api tests**

```bash
cd emcip-admin-api && mvn test -pl . -q 2>&1 | tail -15
```

Expected: all tests PASS.

- [ ] **Step 5: Spotless + commit admin-api**

```bash
cd emcip-admin-api && mvn spotless:apply -q
cd ..
git add emcip-admin-api/src/
git commit -m "$(cat <<'EOF'
feat(admin-api): simulate endpoint returns full pipeline trace

- AuditServiceClient: add findByCorrelationId method
- SimulationService: poll audit log by correlationId after publish,
  return SimulateTraceResult with stages (PUBLISH/CLASSIFIER/POLICY/MODERATION)
  and partial flag for timeout cases
- SimulateController: return SimulateTraceResult directly instead of Map

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Create PipelineTrace component

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Simulate/PipelineTrace.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Simulate/PipelineTrace.module.css`

The frontend source root is `emcip-admin-ui/src/main/frontend/src/`.

- [ ] **Step 1: Create PipelineTrace.module.css**

```css
.panel {
  background: var(--bg-card);
  border: 1px solid var(--border);
  padding: var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.waiting {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  margin: 0;
}

.stages {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.stage {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stageHead {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.stageName {
  font-family: var(--font-display);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.stageSource {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  padding-left: 20px;
}

.stageData {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.timedOut {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--signal-warn-fg);
  padding-left: 20px;
}
```

- [ ] **Step 2: Create PipelineTrace.jsx**

```jsx
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import styles from './PipelineTrace.module.css'

const STAGE_META = [
  { key: 'PUBLISH',    label: 'PUBLISH',           service: 'admin-api',          topic: 'telegram.raw.messages' },
  { key: 'CLASSIFIER', label: 'INTENT CLASSIFIER', service: 'intent-classifier',  topic: 'messages.classified' },
  { key: 'POLICY',     label: 'POLICY ENGINE',     service: 'policy-engine',      topic: 'policies.decisions' },
  { key: 'MODERATION', label: 'MODERATION SERVICE',service: 'moderation-service', topic: 'moderation.flags' },
]

function dotColor(stageKey, data) {
  if (!data) return 'var(--border-strong)'
  if (stageKey === 'PUBLISH') return 'var(--signal-ok-fg)'
  if (stageKey === 'CLASSIFIER') return 'var(--accent)'
  if (stageKey === 'POLICY') {
    const d = (data.decision || '').toUpperCase()
    if (d === 'BLOCK' || d === 'MODERATE') return 'var(--signal-stop-fg)'
    if (d === 'REACT' || d === 'SUMMARIZE') return 'var(--signal-info-fg)'
    return 'var(--signal-mute-fg)'
  }
  if (stageKey === 'MODERATION') {
    const s = (data.severity || '').toUpperCase()
    if (s === 'HIGH') return 'var(--signal-stop-fg)'
    if (s === 'MEDIUM') return 'var(--signal-warn-fg)'
    return 'var(--signal-ok-fg)'
  }
  return 'var(--fg-3)'
}

function stageDataLines(stageKey, data) {
  if (!data) return null
  if (stageKey === 'PUBLISH') {
    return [`eventId: ${data.eventId}`]
  }
  if (stageKey === 'CLASSIFIER') {
    const pct = data.confidence != null ? `${Math.round(data.confidence * 100)}%` : ''
    const rules = Array.isArray(data.matchedRules) && data.matchedRules.length
      ? data.matchedRules.join(', ')
      : null
    return [
      [data.intent, pct].filter(Boolean).join(' \u00b7 '),
      rules ? `rules: ${rules}` : null,
    ].filter(Boolean)
  }
  if (stageKey === 'POLICY') {
    const actions = Array.isArray(data.actions) ? data.actions.join(', ') : data.actions || ''
    return [
      [data.decision, actions].filter(Boolean).join(' \u00b7 '),
      data.policyId ? `policy: ${data.policyId}` : null,
      data.reason ? `reason: ${data.reason}` : null,
    ].filter(Boolean)
  }
  if (stageKey === 'MODERATION') {
    return [
      [data.flagType, data.severity].filter(Boolean).join(' \u00b7 '),
      data.reason ? `reason: ${data.reason}` : null,
    ].filter(Boolean)
  }
  return null
}

function findStageData(stages, key) {
  if (!stages) return null
  const found = stages.find(s => s.stage === key)
  return found ? found.data : null
}

export function PipelineTrace({ result, loading }) {
  return (
    <div className={styles.panel}>
      <SectionLabel>Pipeline Trace</SectionLabel>
      {loading && <p className={styles.waiting}>{'\u25b6'} waiting for pipeline\u2026</p>}
      <div className={styles.stages}>
        {STAGE_META.map(meta => {
          const data = result ? findStageData(result.stages, meta.key) : null
          const timedOut = result && result.partial && !data
          const color = dotColor(meta.key, data)
          const lines = stageDataLines(meta.key, data)
          return (
            <div key={meta.key} className={styles.stage}>
              <div className={styles.stageHead}>
                <span
                  className={styles.dot}
                  style={{ background: color }}
                  aria-hidden="true"
                />
                <span
                  className={styles.stageName}
                  style={{ color: data || timedOut ? 'var(--fg-1)' : 'var(--fg-3)' }}
                >
                  {meta.label}
                </span>
              </div>
              <span className={styles.stageSource}>
                {meta.service} {'\u00b7'} {meta.topic}
              </span>
              {timedOut && (
                <span className={styles.timedOut}>\u2014 timed out \u2014</span>
              )}
              {lines && (
                <div className={styles.stageData}>
                  {lines.map((line, i) => <span key={i}>{line}</span>)}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
```

- [ ] **Step 3: Compile check**

```bash
cd emcip-admin-ui && mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS (or no errors from Maven if the frontend doesn't compile at this step — the frontend build will be verified in Task 7's test run).

---

## Task 7: Update Simulate page to two-column layout

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Simulate/Simulate.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Simulate/Simulate.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Simulate/Simulate.test.jsx`

- [ ] **Step 1: Update Simulate.test.jsx**

Replace the entire content:

```jsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider } from '../../auth/AuthContext'
import { ThemeProvider } from '../../theme/ThemeContext'
import { Simulate } from './Simulate'

beforeEach(() => {
  global.fetch = vi.fn()
})

const wrap = ui => render(<ThemeProvider><AuthProvider>{ui}</AuthProvider></ThemeProvider>)

const FULL_TRACE = {
  eventId: 'abc-123',
  partial: false,
  stages: [
    { stage: 'PUBLISH',    data: { topic: 'telegram.raw.messages', eventId: 'abc-123' } },
    { stage: 'CLASSIFIER', data: { intent: 'SPAM', confidence: 0.95, matchedRules: ['SPAM'] } },
    { stage: 'POLICY',     data: { policyId: 'spam-policy', decision: 'BLOCK', actions: ['BLOCK'], reason: 'keyword match' } },
    { stage: 'MODERATION', data: { flagType: 'SPAM', severity: 'HIGH', reason: 'blocked' } },
  ],
}

test('publishes via POST to /api/simulate/message', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    status: 202,
    json: async () => FULL_TRACE,
  })

  wrap(<Simulate />)

  await userEvent.type(screen.getByLabelText(/chat id/i), '-1001234567890')
  await userEvent.type(screen.getByLabelText(/message text/i), 'Hello world')
  await userEvent.click(screen.getByRole('button', { name: /publish/i }))

  await waitFor(() => {
    const call = fetch.mock.calls.find(c => c[0].includes('/api/simulate/message'))
    expect(call).toBeDefined()
    expect(call[1].method).toBe('POST')
    expect(call[0]).toMatch(/\/api\/simulate\/message$/)
  })
})

test('shows error when fields are empty', async () => {
  wrap(<Simulate />)
  await userEvent.click(screen.getByRole('button', { name: /publish/i }))
  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent(/required/i)
  )
})

test('shows pipeline trace stages after successful publish', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    status: 202,
    json: async () => FULL_TRACE,
  })

  wrap(<Simulate />)

  await userEvent.type(screen.getByLabelText(/chat id/i), '12345')
  await userEvent.type(screen.getByLabelText(/message text/i), 'test spam message')
  await userEvent.click(screen.getByRole('button', { name: /publish/i }))

  await waitFor(() => {
    expect(screen.getByText(/INTENT CLASSIFIER/i)).toBeInTheDocument()
    expect(screen.getByText(/SPAM/)).toBeInTheDocument()
  })
})

test('pipeline trace panel is always visible', () => {
  wrap(<Simulate />)
  expect(screen.getByText(/PIPELINE TRACE/i)).toBeInTheDocument()
  expect(screen.getByText(/PUBLISH/i)).toBeInTheDocument()
})
```

- [ ] **Step 2: Run tests to confirm failures**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Simulate/Simulate.test.jsx 2>&1 | tail -20
```

Expected: `shows pipeline trace stages` and `pipeline trace panel is always visible` FAIL.

- [ ] **Step 3: Update Simulate.module.css**

Replace the entire content:

```css
.pageHeader {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--sp-5);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--rule);
}

.systemId {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.10em;
  text-transform: uppercase;
  color: var(--fg-3);
  margin-top: 6px;
}

.subtitle {
  color: var(--fg-2);
  font-size: 13px;
  margin-bottom: var(--sp-4);
}

.columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-5);
  align-items: start;
}

.card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  padding: var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.field label {
  font-family: var(--font-body);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fg-2);
}

.input {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  width: 100%;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
  box-sizing: border-box;
  resize: vertical;
}

.input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}
```

Note: `.result`, `.success`, and `.pipeline` classes are removed; result display moves into `PipelineTrace`.

- [ ] **Step 4: Update Simulate.jsx**

Replace the entire content:

```jsx
import { useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { simulateApi } from '../../api/simulate'
import { Button } from '../../components/Button/Button'
import { PipelineTrace } from './PipelineTrace'
import styles from './Simulate.module.css'

export function Simulate() {
  const api = simulateApi(useAuthRequest())
  const [form, setForm] = useState({ chatId: '', senderId: 'sim-user', senderType: 'USER', text: '' })
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  const publish = async () => {
    if (!form.chatId || !form.text) { setError('Chat ID and Message Text are required'); return }
    setError(''); setResult(null); setLoading(true)
    try {
      const res = await api.publish({ ...form, chatId: parseInt(form.chatId, 10) })
      setResult(res)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <div className={styles.pageHeader}>
        <div>
          <h2>Simulate Event</h2>
          <div className={styles.systemId}>{'\u25B6'} intent-classifier {'\u00b7'} trace mode</div>
        </div>
      </div>

      <p className={styles.subtitle}>Publish a test message into the processing pipeline.</p>

      <div className={styles.columns}>
        <div className={styles.card}>
          <div className={styles.field}>
            <label htmlFor="chatId">Chat ID *</label>
            <input id="chatId" type="number" value={form.chatId}
              onChange={e => set('chatId', e.target.value)} className={styles.input} />
          </div>
          <div className={styles.field}>
            <label htmlFor="senderId">Sender ID</label>
            <input id="senderId" type="text" value={form.senderId}
              onChange={e => set('senderId', e.target.value)} className={styles.input} />
          </div>
          <div className={styles.field}>
            <label htmlFor="senderType">Sender Type</label>
            <select id="senderType" value={form.senderType}
              onChange={e => set('senderType', e.target.value)} className={styles.input}>
              {['USER', 'BOT', 'ADMIN'].map(t => <option key={t}>{t}</option>)}
            </select>
          </div>
          <div className={styles.field}>
            <label htmlFor="text">Message Text *</label>
            <textarea id="text" value={form.text} onChange={e => set('text', e.target.value)}
              className={styles.input} rows={4} />
          </div>
          {error && (
            <p role="alert" style={{
              color: 'var(--signal-stop-fg)',
              background: 'rgba(248,113,113,0.08)',
              border: '1px solid rgba(248,113,113,0.25)',
              padding: '8px 12px',
              fontFamily: 'var(--font-mono)',
              fontSize: '12px',
            }}>{error}</p>
          )}
          <Button onClick={publish} disabled={loading}>
            {loading ? 'Publishing\u2026' : '\u25b6 Publish Message'}
          </Button>
        </div>

        <PipelineTrace result={result} loading={loading} />
      </div>
    </>
  )
}
```

- [ ] **Step 5: Run all Simulate tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Simulate/ 2>&1 | tail -20
```

Expected: all 4 tests PASS.

- [ ] **Step 6: Spotless (Java files only) — frontend has no spotless**

```bash
cd /home/ben/Development/ecip
mvn spotless:check -pl emcip-admin-ui -q 2>&1 | tail -5
```

Expected: no Java files changed, check passes.

- [ ] **Step 7: Commit frontend**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/Simulate/
git commit -m "$(cat <<'EOF'
feat(admin-ui): simulate page — two-column layout with pipeline trace

- Two-column grid: compose form left, PipelineTrace panel right
- PipelineTrace: 4 stage rows (PUBLISH/CLASSIFIER/POLICY/MODERATION),
  idle/loading/result states, dot color by outcome/severity
- Stages reveal simultaneously when simulate endpoint responds
- Removed static pipeline list and raw JSON result dump

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Checklist

- [x] **Spec: two-column layout** → Task 7 (`Simulate.jsx` columns grid + `PipelineTrace`)
- [x] **Spec: correlationId bug fix** → Task 1 (all 5 handlers updated)
- [x] **Spec: findByCorrelationId in repository + service** → Task 2
- [x] **Spec: correlationId filter on AuditController** → Task 2
- [x] **Spec: AuditServiceClient.findByCorrelationId** → Task 3
- [x] **Spec: SimulationService polling + SimulateTraceResult** → Task 4
- [x] **Spec: SimulateController updated response** → Task 5
- [x] **Spec: PipelineTrace component with idle/loading/result states** → Task 6
- [x] **Spec: dot color mapping** → Task 6 (`dotColor` function)
- [x] **Spec: partial: true shows "— timed out —"** → Task 6 (`timedOut` branch)
- [x] **Spec: ▶ waiting for pipeline… (no emoji)** → Task 6 (uses `\u25b6`)
- [x] **Type consistency:** `SimulateTraceResult` and `TraceStage` defined in Task 4, imported in Task 5 controller test ✓
- [x] **No TBDs or placeholders in any step**
