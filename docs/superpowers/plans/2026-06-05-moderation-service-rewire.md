# Moderation Service Rewire Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewire moderation-service to consume `policies.decisions` instead of `telegram.raw.messages` by enriching `PolicyDecisionEvent` with `messageText`.

**Architecture:** Add `messageText` (top-level String field) to `PolicyDecisionEvent` in emcip-core. `PolicyEvaluationService` extracts it from `IntentClassifiedEvent.parameters` when building the event. `ModerationEventConsumer` is replaced by `PolicyDecisionConsumer` which reads from `policies.decisions` and applies keyword/regex rules to `event.messageText()`.

**Tech Stack:** Java 21, Spring Boot 4, Spring Kafka, Jackson 3 (tools.jackson), JUnit 5, Mockito, Awaitility

---

## File map

| Action | File |
|--------|------|
| Modify | `emcip-core/src/main/java/io/emcip/common/events/EventSchemas.java` |
| Modify | `emcip-policy-engine/src/main/java/io/emcip/policy/engine/service/PolicyEvaluationService.java` |
| Modify | `emcip-policy-engine/src/test/java/io/emcip/policy/engine/service/PolicyEvaluationServiceTest.java` |
| Delete | `emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/ModerationEventConsumer.java` |
| Create | `emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/PolicyDecisionConsumer.java` |
| Delete | `emcip-moderation-service/src/test/java/io/emcip/moderation/service/kafka/ModerationEventConsumerTest.java` |
| Create | `emcip-moderation-service/src/test/java/io/emcip/moderation/service/kafka/PolicyDecisionConsumerTest.java` |
| Modify | `emcip-moderation-service/src/test/java/io/emcip/moderation/service/ModerationFlowIT.java` |
| Modify | `documentation/diagrams/kafka-topic-flow.puml` |
| Modify | `docs/superpowers/BACKLOG.md` |

---

## Task 1: Enrich `PolicyDecisionEvent` and update `PolicyEvaluationService`

**Files:**
- Modify: `emcip-core/src/main/java/io/emcip/common/events/EventSchemas.java`
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/service/PolicyEvaluationService.java`
- Modify: `emcip-policy-engine/src/test/java/io/emcip/policy/engine/service/PolicyEvaluationServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add this test to `PolicyEvaluationServiceTest` after `shouldPublishEventToKafka()`. Also update `createClassification()` to include `messageText` in parameters.

Replace the existing `createClassification` helper at the bottom of `PolicyEvaluationServiceTest`:

```java
private EventSchemas.IntentClassifiedEvent createClassification(
        String intent, double confidence) {
    return new EventSchemas.IntentClassifiedEvent(
            "evt-classify-001",
            Instant.now().toString(),
            EventSchemas.INTENT_CLASSIFIED_V1,
            "IntentClassified",
            "evt-test-001",
            intent,
            confidence,
            Map.of("param1", "value1", "messageText", "buy now click here"),
            List.of("rule1", "rule2"));
}
```

Add this new test:

```java
@Test
@DisplayName("Should include messageText from parameters in published Kafka event")
void shouldIncludeMessageTextInPublishedEvent() throws Exception {
    when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
            .thenReturn(Collections.emptyList());
    when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var classification =
            new EventSchemas.IntentClassifiedEvent(
                    "evt-classify-002",
                    Instant.now().toString(),
                    EventSchemas.INTENT_CLASSIFIED_V1,
                    "IntentClassified",
                    "evt-test-002",
                    "SPAM",
                    0.9,
                    Map.of("messageText", "buy now click here"),
                    List.of());

    policyService.evaluate(classification, null);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate, atLeastOnce()).send(captor.capture());

    String json = captor.getValue().value();
    assertThat(json).contains("messageText");
    assertThat(json).contains("buy now click here");
}
```

Add the missing import at the top of the test file:
```java
import static org.mockito.Mockito.atLeastOnce;
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd emcip-policy-engine && mvn test -pl . -Dtest=PolicyEvaluationServiceTest#shouldIncludeMessageTextInPublishedEvent -q 2>&1 | tail -20
```

Expected: compilation failure or test failure (field `messageText` does not exist yet).

- [ ] **Step 3: Add `messageText` field to `PolicyDecisionEvent` in `EventSchemas.java`**

Replace the `PolicyDecisionEvent` record (lines 94–115) with:

```java
/** Policy engine decision. */
public record PolicyDecisionEvent(
        String eventId,
        String timestamp,
        String schemaVersion,
        String eventType,
        String sourceEventId,
        String policyId,
        String decision,
        String reason,
        java.util.Map<String, Object> context,
        java.util.List<String> actions,
        String messageText)
        implements Event {

    public PolicyDecisionEvent {
        if (schemaVersion == null) {
            schemaVersion = POLICY_DECISION_V1;
        }
        if (eventType == null) {
            eventType = "PolicyDecision";
        }
    }
}
```

- [ ] **Step 4: Fix the broken constructor call in `PolicyEvaluationService`**

The `PolicyDecisionEvent` constructor call (around line 159) currently has 10 arguments. Add `messageText` as the 11th:

```java
var decisionEvent =
        new EventSchemas.PolicyDecisionEvent(
                persistedDecision.getId(),
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
                        "matchedRules", classification.matchedRules()),
                List.of(decision.toLowerCase()),
                classification.parameters() != null
                        ? (String) classification.parameters().getOrDefault("messageText", null)
                        : null);
```

- [ ] **Step 5: Run all policy-engine tests**

```bash
cd emcip-policy-engine && mvn test -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`. All existing tests pass; new test passes.

- [ ] **Step 6: Run all emcip-core tests**

```bash
cd emcip-core && mvn test -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Run Spotless on emcip-core and emcip-policy-engine**

```bash
cd emcip-core && mvn spotless:apply -q && mvn spotless:check -q 2>&1 | tail -5
cd emcip-policy-engine && mvn spotless:apply -q && mvn spotless:check -q 2>&1 | tail -5
```

Expected: `0 were changed to be clean` for each.

- [ ] **Step 8: Commit**

```bash
git add emcip-core/src/main/java/io/emcip/common/events/EventSchemas.java \
        emcip-policy-engine/src/main/java/io/emcip/policy/engine/service/PolicyEvaluationService.java \
        emcip-policy-engine/src/test/java/io/emcip/policy/engine/service/PolicyEvaluationServiceTest.java
git commit -m "feat(policy-engine): enrich PolicyDecisionEvent with messageText (#34)"
```

---

## Task 2: Replace `ModerationEventConsumer` with `PolicyDecisionConsumer`

**Files:**
- Delete: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/ModerationEventConsumer.java`
- Create: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/PolicyDecisionConsumer.java`
- Delete: `emcip-moderation-service/src/test/java/io/emcip/moderation/service/kafka/ModerationEventConsumerTest.java`
- Create: `emcip-moderation-service/src/test/java/io/emcip/moderation/service/kafka/PolicyDecisionConsumerTest.java`
- Modify: `emcip-moderation-service/src/test/java/io/emcip/moderation/service/ModerationFlowIT.java`

- [ ] **Step 1: Write `PolicyDecisionConsumerTest`**

Create `emcip-moderation-service/src/test/java/io/emcip/moderation/service/kafka/PolicyDecisionConsumerTest.java`:

```java
package io.emcip.moderation.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.common.events.EventSchemas.PolicyDecisionEvent;
import io.emcip.moderation.service.service.RuleEvaluationService;
import io.emcip.moderation.service.service.RuleEvaluationService.EvaluationResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PolicyDecisionConsumerTest {

    @Mock private RuleEvaluationService ruleEvaluationService;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private Acknowledgment acknowledgment;

    private PolicyDecisionConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        consumer = new PolicyDecisionConsumer(ruleEvaluationService, kafkaTemplate);
        objectMapper = new ObjectMapper();
    }

    private ConsumerRecord<String, String> toRecord(String key, String value) {
        return new ConsumerRecord<>("policies.decisions", 0, 0L, key, value);
    }

    private String policyDecisionJson(String sourceEventId, String messageText) throws Exception {
        PolicyDecisionEvent event =
                new PolicyDecisionEvent(
                        UUID.randomUUID().toString(),
                        "2026-06-05T10:00:00Z",
                        null,
                        null,
                        sourceEventId,
                        "policy-001",
                        "BLOCK",
                        "Spam detected",
                        Map.of("originalIntent", "SPAM", "confidence", 0.9,
                               "matchedRules", List.of()),
                        List.of("block"),
                        messageText);
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void consume_messageMatchingKeywordRule_sendsFlagAndAcknowledges() throws Exception {
        String json = policyDecisionJson("evt-001", "this message contains spam");
        EvaluationResult matchResult =
                new EvaluationResult("keyword-spam", "HIGH", "FLAG", "KEYWORD");
        when(ruleEvaluationService.evaluate(eq("this message contains spam"), any()))
                .thenReturn(Optional.of(matchResult));

        consumer.consume(toRecord("evt-001", json), acknowledgment);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        assertThat(recordCaptor.getValue().topic()).isEqualTo("moderation.flags");
        assertThat(recordCaptor.getValue().key()).isEqualTo("evt-001");
        assertThat(recordCaptor.getValue().value()).contains("ModerationFlag");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_messageMatchingNoRules_doesNotSendAndAcknowledges() throws Exception {
        String json = policyDecisionJson("evt-002", "a perfectly clean message");
        when(ruleEvaluationService.evaluate(eq("a perfectly clean message"), any()))
                .thenReturn(Optional.empty());

        consumer.consume(toRecord("evt-002", json), acknowledgment);

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_nullMessageText_skipsEvaluationAndAcknowledges() throws Exception {
        String json = policyDecisionJson("evt-003", null);

        consumer.consume(toRecord("evt-003", json), acknowledgment);

        verify(ruleEvaluationService, never()).evaluate(any(), any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_malformedJson_propagatesException() {
        assertThatThrownBy(
                        () ->
                                consumer.consume(
                                        toRecord("bad-key", "{ not valid json %%% }"),
                                        acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void consume_kafkaTemplateSendFails_propagatesExceptionWithoutAck() throws Exception {
        String json = policyDecisionJson("evt-004", "spam content here");
        EvaluationResult matchResult =
                new EvaluationResult("keyword-spam", "HIGH", "FLAG", "KEYWORD");
        when(ruleEvaluationService.evaluate(eq("spam content here"), any()))
                .thenReturn(Optional.of(matchResult));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new RuntimeException("Kafka unavailable"));

        assertThatThrownBy(() -> consumer.consume(toRecord("evt-004", json), acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(acknowledgment, never()).acknowledge();
    }
}
```

- [ ] **Step 2: Run the new test to confirm it fails**

```bash
cd emcip-moderation-service && mvn test -pl . -Dtest=PolicyDecisionConsumerTest -q 2>&1 | tail -20
```

Expected: compilation failure — `PolicyDecisionConsumer` does not exist yet.

- [ ] **Step 3: Create `PolicyDecisionConsumer`**

Create `emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/PolicyDecisionConsumer.java`:

```java
package io.emcip.moderation.service.kafka;

import io.emcip.common.events.EventSchemas.ModerationFlagEvent;
import io.emcip.common.events.EventSchemas.PolicyDecisionEvent;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.common.tenant.TenantContext;
import io.emcip.moderation.service.service.RuleEvaluationService;
import io.emcip.moderation.service.service.RuleEvaluationService.EvaluationResult;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class PolicyDecisionConsumer {

    private static final String MODERATION_FLAGS_TOPIC = "moderation.flags";

    private final RuleEvaluationService ruleEvaluationService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PolicyDecisionConsumer(
            RuleEvaluationService ruleEvaluationService,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.ruleEvaluationService = ruleEvaluationService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @KafkaListener(
            topics = "policies.decisions",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            TenantAwareKafkaSupport.bindTenantFromRecord(record);
            String tenantId = TenantContext.getTenantId();

            PolicyDecisionEvent event =
                    objectMapper.readValue(record.value(), PolicyDecisionEvent.class);

            String text = event.messageText();
            if (text == null || text.isBlank()) {
                log.debug(
                        "No messageText in policy decision {}, skipping moderation evaluation",
                        event.sourceEventId());
                acknowledgment.acknowledge();
                return;
            }

            Optional<EvaluationResult> result = ruleEvaluationService.evaluate(text, tenantId);

            if (result.isPresent()) {
                EvaluationResult match = result.get();
                log.info(
                        "Moderation rule '{}' matched for event {}: severity={}, action={}",
                        match.ruleName(),
                        event.sourceEventId(),
                        match.severity(),
                        match.action());

                ModerationFlagEvent flagEvent =
                        new ModerationFlagEvent(
                                UUID.randomUUID().toString(),
                                Instant.now().toString(),
                                null,
                                null,
                                event.sourceEventId(),
                                match.ruleType(),
                                match.severity(),
                                "Rule matched: " + match.ruleName(),
                                Map.of("action", match.action(), "ruleName", match.ruleName()));

                String flagJson = objectMapper.writeValueAsString(flagEvent);
                ProducerRecord<String, String> kafkaRecord =
                        new ProducerRecord<>(
                                MODERATION_FLAGS_TOPIC, event.sourceEventId(), flagJson);
                TenantAwareKafkaSupport.addTenantHeader(kafkaRecord);
                kafkaTemplate.send(kafkaRecord);
                log.debug(
                        "Published ModerationFlagEvent to {} for source event {}",
                        MODERATION_FLAGS_TOPIC,
                        event.sourceEventId());
            }

            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process policy decision: {}", record.value(), e);
            throw new RuntimeException(e);
        } finally {
            TenantContext.clear();
        }
    }
}
```

- [ ] **Step 4: Run the new unit tests**

```bash
cd emcip-moderation-service && mvn test -pl . -Dtest=PolicyDecisionConsumerTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all 5 tests pass.

- [ ] **Step 5: Delete the old consumer and its test**

```bash
rm emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/ModerationEventConsumer.java
rm emcip-moderation-service/src/test/java/io/emcip/moderation/service/kafka/ModerationEventConsumerTest.java
```

- [ ] **Step 6: Update `ModerationFlowIT` to use `policies.decisions`**

Replace the entire contents of `ModerationFlowIT.java` with:

```java
package io.emcip.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.common.events.EventSchemas.PolicyDecisionEvent;
import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ModerationFlowIT extends AbstractModerationIntegrationTest {

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired private ModerationRuleRepository ruleRepository;

    @Test
    void policyDecision_matchingKeywordRule_producesModerationFlagEvent() throws Exception {
        // Arrange: insert an enabled keyword rule
        ModerationRule rule =
                ModerationRule.builder()
                        .name("spam-detection-it")
                        .ruleType("KEYWORD")
                        .pattern("spam_it_test_keyword_99")
                        .severity("HIGH")
                        .action("FLAG")
                        .enabled(true)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .tenantId(UUID.fromString(TENANT_ID))
                        .build();

        await().atMost(Duration.ofSeconds(30))
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(
                        () -> {
                            try {
                                ruleRepository.save(rule).block();
                                return true;
                            } catch (Exception e) {
                                return false;
                            }
                        });

        // Arrange: build PolicyDecisionEvent with matching messageText
        PolicyDecisionEvent event =
                new PolicyDecisionEvent(
                        "evt-mod-flow-001",
                        Instant.now().toString(),
                        null,
                        null,
                        "evt-mod-flow-001",
                        "policy-001",
                        "BLOCK",
                        "Spam detected",
                        Map.of("originalIntent", "SPAM", "confidence", 0.95,
                               "matchedRules", List.of()),
                        List.of("block"),
                        "this message contains spam_it_test_keyword_99");
        String json = new ObjectMapper().writeValueAsString(event);

        // Arrange: subscribe to output topic
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProps.put(
                ConsumerConfig.GROUP_ID_CONFIG, "test-mod-flow-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        Consumer<String, String> testConsumer =
                new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
        testConsumer.subscribe(Collections.singletonList("moderation.flags"));

        // Act: publish to policies.decisions with tenant header
        ProducerRecord<String, String> producerRecord =
                new ProducerRecord<>("policies.decisions", "evt-mod-flow-001", json);
        producerRecord
                .headers()
                .add("tenant_id", TENANT_ID.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(producerRecord).get();

        // Assert: ModerationFlagEvent appears on moderation.flags within 15 seconds
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, String> records =
                                    testConsumer.poll(Duration.ofMillis(500));
                            assertThat(records.count()).isGreaterThan(0);
                            String value = records.iterator().next().value();
                            assertThat(value).contains("ModerationFlag");
                            assertThat(value).contains("evt-mod-flow-001");
                        });

        testConsumer.close();
    }
}
```

- [ ] **Step 7: Run all moderation-service tests**

```bash
cd emcip-moderation-service && mvn test -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`. `PolicyDecisionConsumerTest` (5 tests), `RuleEvaluationServiceTest` (4 tests) all pass. IT may be skipped if Docker unavailable — that is acceptable.

- [ ] **Step 8: Run Spotless on moderation-service**

```bash
cd emcip-moderation-service && mvn spotless:apply -q && mvn spotless:check -q 2>&1 | tail -5
```

Expected: `0 were changed to be clean`.

- [ ] **Step 9: Commit**

```bash
git add \
  emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/PolicyDecisionConsumer.java \
  emcip-moderation-service/src/test/java/io/emcip/moderation/service/kafka/PolicyDecisionConsumerTest.java \
  emcip-moderation-service/src/test/java/io/emcip/moderation/service/ModerationFlowIT.java
git rm \
  emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/ModerationEventConsumer.java \
  emcip-moderation-service/src/test/java/io/emcip/moderation/service/kafka/ModerationEventConsumerTest.java
git commit -m "feat(moderation-service): consume policies.decisions instead of telegram.raw.messages (#34)"
```

---

## Task 3: Update diagrams and backlog

**Files:**
- Modify: `documentation/diagrams/kafka-topic-flow.puml`
- Modify: `docs/superpowers/BACKLOG.md`

- [ ] **Step 1: Update `kafka-topic-flow.puml`**

Make three changes to the file:

**Change 1** — remove `t_raw --> mod` from the fan-out section:

```
' --- Fan-out from telegram.raw.messages ---
t_raw --> clf   : consume
t_raw --> mod   : consume          ← DELETE this line
t_raw --> ctx   : consume (thread tracking)
t_raw --> audit : consume (observability)
```

**Change 2** — add `mod` as a consumer of `t_decisions` and update the stage 3 comment:

Replace:
```
' --- Stage 3: LLM action (only for RESPOND / ESCALATE / EXECUTE decisions) ---
t_decisions --> llm   : consume
t_decisions --> audit : consume (observability)
```
With:
```
' --- Stage 3: LLM action + moderation keyword check ---
t_decisions --> llm   : consume
t_decisions --> mod   : consume (keyword/regex on messageText)
t_decisions --> audit : consume (observability)
```

**Change 3** — update the `t_raw` note (4 → 3 consumers) and the `t_decisions` note:

Replace:
```
note bottom of t_raw
  <b>Fan-out root</b>
  4 parallel consumers.
  All pipeline processing
  starts here.
end note
```
With:
```
note bottom of t_raw
  <b>Fan-out root</b>
  3 parallel consumers.
  All pipeline processing
  starts here.
end note
```

Replace:
```
note top of t_decisions
  <b>Policy decision</b>
  llm-orchestrator checks the
  <i>decision</i> field and only calls
  LiteLLM for RESPOND / ESCALATE /
  EXECUTE. Others are logged only.
end note
```
With:
```
note top of t_decisions
  <b>Policy decision (+messageText)</b>
  llm-orchestrator calls LiteLLM only
  for RESPOND / ESCALATE / EXECUTE.
  moderation-service applies keyword/
  regex rules to the enriched
  messageText field.
end note
```

- [ ] **Step 2: Update `BACKLOG.md`**

Mark item #34 as done:

```markdown
| 34 | **Architecture: rewire moderation-service off `telegram.raw.messages`** | M | ✅ 2026-06-05. `PolicyDecisionEvent` enriched with top-level `messageText` (extracted from `IntentClassifiedEvent.parameters` in policy-engine). `ModerationEventConsumer` replaced by `PolicyDecisionConsumer` consuming `policies.decisions`. `kafka-topic-flow.puml` updated. |
```

Also update the header date line.

- [ ] **Step 3: Commit**

```bash
git add documentation/diagrams/kafka-topic-flow.puml docs/superpowers/BACKLOG.md
git commit -m "docs: update kafka topology diagram and backlog for moderation-service rewire (#34)"
```
