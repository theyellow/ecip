# US-26.4 Knowledge Extraction Pipeline — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the knowledge extraction pipeline with DLQ error handling, metadata preservation, ontology-driven LLM prompts, and result validation.

**Architecture:** Four targeted changes to three files in `emcip-knowledge-engine`: `KafkaConfig` gains a `DefaultErrorHandler` + DLT recoverer; `KnowledgeMessageConsumer` removes silent exception swallowing and threads five metadata fields to `processMessage()`; `KnowledgeExtractionService.processMessage()` accepts the metadata params, stores them in `KnowledgeDocument.metadata`, passes full `List<ConceptType>` / `List<RelationshipType>` to the LLM client, and filters invalid results; `LlmOrchestratorClient.extract()` builds a structured prompt with descriptions and directionality. An integration test exercises the full service against real Postgres via Testcontainers.

**Tech Stack:** Spring Kafka `DefaultErrorHandler` / `DeadLetterPublishingRecoverer`, JPA/Hibernate, Testcontainers (`pgvector/pgvector:pg16`), MockWebServer (okhttp3), Mockito.

**Spec:** `docs/superpowers/specs/2026-06-16-knowledge-extraction-pipeline-design.md`

---

## File Map

| File | Change |
|------|--------|
| `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/KafkaConfig.java` | Add `DefaultErrorHandler` bean; wire into container factory |
| `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumer.java` | Remove try/catch (keep try/finally); pass metadata to `processMessage()` |
| `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java` | Add 5 metadata params; set `doc.metadata`; pass full type lists to LLM client; validate results |
| `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java` | Change `extract()` signature to `List<ConceptType>`, `List<RelationshipType>`; build structured prompt |
| `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumerTest.java` | Add propagation test; update verify call for new `processMessage()` signature |
| `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeExtractionServiceTest.java` | Add metadata and validation tests; update existing test for new signatures |
| `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/client/LlmOrchestratorClientTest.java` | Update `extract()` test; add prompt content assertion |
| `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/KnowledgeExtractionIntegrationTest.java` | New: end-to-end test against real Postgres |

---

### Task 1: DLQ — DefaultErrorHandler wiring

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/KafkaConfig.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumer.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumerTest.java`

- [ ] **Step 1: Write failing test — exception propagates from consume()**

Add to `KnowledgeMessageConsumerTest.java` (new method, new imports):

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
```

```java
@Test
void shouldPropagateExceptionFromExtractionService() throws Exception {
    UUID tenantId = UUID.randomUUID();
    String eventJson =
            """
            {
              "eventId": "evt-err",
              "timestamp": "2026-06-16T10:00:00Z",
              "schemaVersion": "1.0.0",
              "eventType": "TelegramMessage",
              "telegramMessageId": 99,
              "chatId": 200,
              "senderId": "111",
              "senderType": "USER",
              "text": "trigger failure",
              "date": 1718272800,
              "isOutgoing": false,
              "senderDisplayName": "FailUser",
              "chatTitle": "FailGroup"
            }
            """;
    var record = new ConsumerRecord<>("knowledge.raw.messages", 0, 0L, "200", eventJson);
    record.headers().add("tenant_id", tenantId.toString().getBytes());

    doThrow(new RuntimeException("LLM failure"))
            .when(extractionService)
            .processMessage(any(), any(), any());

    assertThatThrownBy(() -> consumer.consume(record))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("LLM failure");
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=KnowledgeMessageConsumerTest#shouldPropagateExceptionFromExtractionService -am 2>&1 | tail -20
```

Expected: FAIL — consumer currently swallows the exception so `assertThatThrownBy` block completes without throwing.

- [ ] **Step 3: Remove try/catch from consumer, keep try/finally**

Replace the entire `consume()` method body in `KnowledgeMessageConsumer.java`:

```java
@KafkaListener(
        topics = "knowledge.raw.messages",
        groupId = "knowledge-engine",
        containerFactory = "kafkaListenerContainerFactory")
public void consume(ConsumerRecord<String, String> record) {
    UUID tenantId = extractTenantId(record);

    try {
        TenantContext.setTenantId(tenantId != null ? tenantId.toString() : null);

        EventSchemas.TelegramMessageEvent event =
                objectMapper.readValue(record.value(), EventSchemas.TelegramMessageEvent.class);

        if (event.text() == null || event.text().isBlank()) {
            log.debug("Skipping non-text message: {}", event.telegramMessageId());
            return;
        }

        String sourceRef = String.format("tg:%d:%d", event.chatId(), event.telegramMessageId());

        extractionService.processMessage(event.text(), sourceRef, tenantId);

        eventPublisher.publishExtractionComplete(sourceRef, tenantId);

        log.info(
                "Processed knowledge message: chat={}, msg={}",
                event.chatId(),
                event.telegramMessageId());

    } finally {
        TenantContext.clear();
    }
}
```

- [ ] **Step 4: Add DefaultErrorHandler bean to KafkaConfig**

Add imports to `KafkaConfig.java`:

```java
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
```

Add new bean after `kafkaTemplate()`:

```java
@Bean
public DefaultErrorHandler knowledgeErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
    var recoverer =
            new DeadLetterPublishingRecoverer(
                    kafkaTemplate,
                    (record, ex) ->
                            new TopicPartition(
                                    "knowledge.raw.messages.DLT", record.partition()));
    var backOff = new FixedBackOff(1_000L, 3L);
    return new DefaultErrorHandler(recoverer, backOff);
}
```

- [ ] **Step 5: Wire error handler into container factory**

Replace `kafkaListenerContainerFactory()` in `KafkaConfig.java`:

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
        DefaultErrorHandler knowledgeErrorHandler) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    factory.setCommonErrorHandler(knowledgeErrorHandler);
    return factory;
}
```

- [ ] **Step 6: Run all consumer tests**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=KnowledgeMessageConsumerTest -am 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 7: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/KafkaConfig.java \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumer.java \
  emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumerTest.java
git commit -m "feat(knowledge): DLQ error handler, remove silent exception swallowing in consumer"
```

---

### Task 2: Metadata threading

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumer.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeExtractionServiceTest.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumerTest.java`

**Background:** `EventSchemas.TelegramMessageEvent` (in `emcip-core`) has: `chatId` (Long), `senderId` (String), `senderDisplayName` (String), `chatTitle` (String), `date` (Integer, unix timestamp). We pass these five as additional params to `processMessage()` and store them in `KnowledgeDocument.metadata` (existing `Map<String,Object>` JSONB field).

- [ ] **Step 1: Write failing test — metadata stored on KnowledgeDocument**

Add to `KnowledgeExtractionServiceTest.java`:

```java
import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;
```

```java
@Test
void shouldPopulateMetadataOnDocument() {
    UUID tenantId = UUID.randomUUID();
    String text = "Alice met Bob";
    String sourceRef = "tg:100:42";

    when(llmClient.embed(text)).thenReturn(new float[]{0.1f});
    when(documentRepository.save(any()))
            .thenAnswer(inv -> {
                KnowledgeDocument doc = inv.getArgument(0);
                doc.setId(UUID.randomUUID());
                return doc;
            });
    when(llmClient.extract(eq(text), any(), any()))
            .thenReturn(new ExtractionResult(List.of(), List.of()));

    service.processMessage(
            text, sourceRef, tenantId, 100L, "999", "TestUser", "TestGroup", 1718272800);

    ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
    verify(documentRepository).save(captor.capture());
    KnowledgeDocument saved = captor.getValue();
    assertThat(saved.getMetadata()).isNotNull();
    assertThat(saved.getMetadata()).containsEntry("chatId", 100L);
    assertThat(saved.getMetadata()).containsEntry("senderId", "999");
    assertThat(saved.getMetadata()).containsEntry("senderDisplayName", "TestUser");
    assertThat(saved.getMetadata()).containsEntry("chatTitle", "TestGroup");
    assertThat(saved.getMetadata()).containsEntry("messageDate", 1718272800);
}
```

- [ ] **Step 2: Run test — verify it fails (compile error: wrong arg count)**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=KnowledgeExtractionServiceTest#shouldPopulateMetadataOnDocument -am 2>&1 | tail -20
```

Expected: FAIL — compile error because `processMessage()` only accepts 3 args.

- [ ] **Step 3: Update `processMessage()` signature and populate metadata**

Replace the full `processMessage()` method in `KnowledgeExtractionService.java`.

First, add import: `import java.util.Map;`

```java
@Transactional
public void processMessage(
        String text,
        String sourceRef,
        UUID tenantId,
        Long chatId,
        String senderId,
        String senderDisplayName,
        String chatTitle,
        Integer messageDate) {
    if (text == null || text.isBlank()) {
        log.debug("Skipping empty message: {}", sourceRef);
        return;
    }

    // Step 1: Store raw content as KnowledgeDocument
    KnowledgeDocument doc = new KnowledgeDocument();
    doc.setTenantId(tenantId);
    doc.setSourceType("CHAT_MESSAGE");
    doc.setSourceRef(sourceRef);
    doc.setContent(text);
    doc.setChunkIndex(0);
    doc.setMetadata(
            Map.of(
                    "chatId", chatId,
                    "senderId", senderId,
                    "senderDisplayName", senderDisplayName,
                    "chatTitle", chatTitle,
                    "messageDate", messageDate));
    KnowledgeDocument saved = documentRepository.save(doc);

    // Step 2: Generate and store embedding
    float[] embedding = llmClient.embed(text);
    if (embedding.length > 0) {
        vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
    }

    // Step 3: LLM-based entity/relationship extraction
    String conceptTypes =
            ontologyService.getAllConceptTypes().stream()
                    .map(ct -> ct.getName())
                    .collect(Collectors.joining(","));
    String relationshipTypes =
            ontologyService.getAllRelationshipTypes().stream()
                    .map(rt -> rt.getName())
                    .collect(Collectors.joining(","));

    ExtractionResult result = llmClient.extract(text, conceptTypes, relationshipTypes);

    // Step 4: Entity resolution + graph storage
    for (ExtractedEntity entity : result.entities()) {
        entityResolutionService.resolve(entity.label(), entity.type(), tenantId);
    }

    for (ExtractedRelationship rel : result.relationships()) {
        UUID sourceId =
                entityResolutionService.resolve(rel.source(), inferType(rel, true), tenantId);
        UUID targetId =
                entityResolutionService.resolve(rel.target(), inferType(rel, false), tenantId);

        graphRepository.createRelationship(
                rel.type(), sourceId, targetId, rel.properties(), saved.getId());
    }

    log.info(
            "Processed message {}: {} entities, {} relationships",
            sourceRef,
            result.entities().size(),
            result.relationships().size());
}
```

Note: the `extract()` call still uses the old `String, String` signature here — Task 3 changes it.

- [ ] **Step 4: Update consumer to pass metadata fields**

Replace the `consume()` method in `KnowledgeMessageConsumer.java`:

```java
@KafkaListener(
        topics = "knowledge.raw.messages",
        groupId = "knowledge-engine",
        containerFactory = "kafkaListenerContainerFactory")
public void consume(ConsumerRecord<String, String> record) {
    UUID tenantId = extractTenantId(record);

    try {
        TenantContext.setTenantId(tenantId != null ? tenantId.toString() : null);

        EventSchemas.TelegramMessageEvent event =
                objectMapper.readValue(record.value(), EventSchemas.TelegramMessageEvent.class);

        if (event.text() == null || event.text().isBlank()) {
            log.debug("Skipping non-text message: {}", event.telegramMessageId());
            return;
        }

        String sourceRef = String.format("tg:%d:%d", event.chatId(), event.telegramMessageId());

        extractionService.processMessage(
                event.text(),
                sourceRef,
                tenantId,
                event.chatId(),
                event.senderId(),
                event.senderDisplayName(),
                event.chatTitle(),
                event.date());

        eventPublisher.publishExtractionComplete(sourceRef, tenantId);

        log.info(
                "Processed knowledge message: chat={}, msg={}",
                event.chatId(),
                event.telegramMessageId());

    } finally {
        TenantContext.clear();
    }
}
```

- [ ] **Step 5: Update existing consumer test for new signature**

In `KnowledgeMessageConsumerTest.java`, update the `verify()` call in `shouldProcessTelegramMessageEvent()`:

```java
verify(extractionService)
        .processMessage(
                eq("AI is transforming everything"),
                eq("tg:100:42"),
                eq(tenantId),
                eq(100L),
                eq("999"),
                eq("TestUser"),
                eq("TestGroup"),
                eq(1718272800));
```

Also update the `doThrow()` stub in `shouldPropagateExceptionFromExtractionService()`:

```java
doThrow(new RuntimeException("LLM failure"))
        .when(extractionService)
        .processMessage(any(), any(), any(), any(), any(), any(), any(), any());
```

- [ ] **Step 6: Update existing extraction service test for new signature**

In `KnowledgeExtractionServiceTest.java`, update `shouldStoreDocumentAndExtractEntities()` — change the `service.processMessage(...)` call:

```java
service.processMessage(text, sourceRef, tenantId, 100L, "999", "TestUser", "TestGroup", 1718272800);
```

- [ ] **Step 7: Run all tests**

```bash
mvn test -pl emcip-knowledge-engine -am 2>&1 | tail -30
```

Expected: all tests PASS.

- [ ] **Step 8: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumer.java \
  emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeExtractionServiceTest.java \
  emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumerTest.java
git commit -m "feat(knowledge): thread message metadata through processMessage, persist to KnowledgeDocument"
```

---

### Task 3: Ontology-driven LLM prompt

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/client/LlmOrchestratorClientTest.java`

**Background:** Currently `extract()` takes two comma-joined name strings. We change the signature to accept the full entity objects so the prompt can include descriptions and directionality.

`ConceptType` fields used: `getName()`, `getDescription()`, `getProperties()` (List of Maps with `"key"` entry).
`RelationshipType` fields used: `getName()`, `getDescription()`, `getSourceTypes()`, `getTargetTypes()`.

- [ ] **Step 1: Write failing test — prompt contains descriptions and directionality**

Add to `LlmOrchestratorClientTest.java`:

```java
import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import java.util.List;
```

```java
@Test
void shouldBuildOntologyDrivenPromptWithDescriptions() throws Exception {
    mockWebServer.enqueue(
            new MockResponse()
                    .setBody(
                            """
                            {"success":true,"analysis":"{\\"entities\\":[],\\"relationships\\":[]}","model":"test"}
                            """)
                    .addHeader("Content-Type", "application/json"));

    ConceptType person = new ConceptType();
    person.setName("PERSON");
    person.setDescription("A human individual");
    person.setShared(false);

    RelationshipType knows = new RelationshipType();
    knows.setName("KNOWS");
    knows.setDescription("One person knows another");
    knows.setSourceTypes(List.of("PERSON"));
    knows.setTargetTypes(List.of("PERSON"));

    client.extract("Alice knows Bob", List.of(person), List.of(knows));

    var request = mockWebServer.takeRequest();
    String body = request.getBody().readUtf8();
    assertThat(body).contains("PERSON");
    assertThat(body).contains("A human individual");
    assertThat(body).contains("KNOWS");
    assertThat(body).contains("One person knows another");
    assertThat(body).contains("PERSON \\u2192 PERSON");
    assertThat(body).contains("EXTRACT");
}
```

Note: `→` is U+2192. In the JSON-encoded request body, this encodes to `\u2192`. Use `assertThat(body).contains("PERSON")` and separate assertion for direction: `assertThat(body).contains("PERSON").contains("PERSON")` — or simply check raw arrow char. Actually the arrow `→` may or may not be encoded depending on Jackson's character escaping settings. To be safe, assert both sides separately:

```java
assertThat(body).contains("\"PERSON → PERSON\"").or().contains("\"PERSON \\u2192 PERSON\"");
```

Simpler: just assert the direction string appears in the built prompt string, and check it reaches the body via the `prompt` key:

```java
assertThat(body).contains("KNOWS");
assertThat(body).contains("One person knows another");
// Direction line appears as "Direction: PERSON → PERSON"
assertThat(body).containsAnyOf("Direction: PERSON", "Direction\\u003a PERSON");
```

Actually the simplest robust check: the prompt is in the `prompt` field of the JSON body. The key assertions are that the description text appears somewhere. Use:

```java
assertThat(body).contains("A human individual");
assertThat(body).contains("One person knows another");
```

These strings are plain ASCII so no encoding issues.

- [ ] **Step 2: Run test — verify it fails (compile error: current extract() takes String args)**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=LlmOrchestratorClientTest#shouldBuildOntologyDrivenPromptWithDescriptions -am 2>&1 | tail -20
```

Expected: FAIL — compile error, `extract()` signature mismatch.

- [ ] **Step 3: Update `extract()` in LlmOrchestratorClient**

Add imports:

```java
import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import java.util.stream.Collectors;
```

Remove the old `extract(String text, String conceptTypes, String relationshipTypes)` method and replace with:

```java
public ExtractionResult extract(
        String text,
        List<ConceptType> conceptTypes,
        List<RelationshipType> relationshipTypes) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("Extract structured knowledge from the text below.\n\n");

    prompt.append("CONCEPT TYPES:\n");
    for (ConceptType ct : conceptTypes) {
        prompt.append("- ")
                .append(ct.getName())
                .append(": ")
                .append(ct.getDescription() != null ? ct.getDescription() : "")
                .append("\n");
        if (ct.getProperties() != null && !ct.getProperties().isEmpty()) {
            String propNames =
                    ct.getProperties().stream()
                            .map(p -> (String) p.get("key"))
                            .filter(k -> k != null)
                            .collect(Collectors.joining(", "));
            prompt.append("  Properties: ")
                    .append(propNames.isEmpty() ? "none" : propNames)
                    .append("\n");
        }
    }

    prompt.append("\nRELATIONSHIP TYPES:\n");
    for (RelationshipType rt : relationshipTypes) {
        prompt.append("- ")
                .append(rt.getName())
                .append(": ")
                .append(rt.getDescription() != null ? rt.getDescription() : "")
                .append("\n");
        String src =
                rt.getSourceTypes() != null ? String.join(", ", rt.getSourceTypes()) : "any";
        String tgt =
                rt.getTargetTypes() != null ? String.join(", ", rt.getTargetTypes()) : "any";
        prompt.append("  Direction: ").append(src).append(" → ").append(tgt).append("\n");
    }

    prompt.append("\nTEXT:\n")
            .append(text)
            .append(
                    """

                    \nReturn JSON:
                    {
                      "entities": [{"type": "<ConceptType name>", "label": "<text>", "properties": {}}],
                      "relationships": [{"type": "<RelationshipType name>", "source": "<label>", \
                    "target": "<label>", "properties": {}}]
                    }
                    """);

    Map<String, String> request = Map.of("prompt", prompt.toString(), "taskType", "EXTRACT");

    var response =
            restClient
                    .post()
                    .uri("/api/analyse")
                    .body(request)
                    .retrieve()
                    .body(AnalyseResponse.class);

    if (response == null || !response.success()) {
        log.error("Extraction failed: {}", response);
        return new ExtractionResult(List.of(), List.of());
    }

    return parseExtractionResult(response.analysis());
}
```

- [ ] **Step 4: Update extraction service to pass full type lists**

In `KnowledgeExtractionService.java`, add imports:

```java
import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import java.util.List;
```

Replace the "Step 3" block in `processMessage()`:

```java
// Step 3: LLM-based entity/relationship extraction
List<ConceptType> conceptTypes = ontologyService.getAllConceptTypes();
List<RelationshipType> relTypes = ontologyService.getAllRelationshipTypes();

ExtractionResult result = llmClient.extract(text, conceptTypes, relTypes);
```

Keep `import java.util.stream.Collectors;` — it will be used again in Task 4.

- [ ] **Step 5: Update existing LlmOrchestratorClientTest to use new signature**

Replace `shouldExtractEntitiesFromText()`:

```java
@Test
void shouldExtractEntitiesFromText() throws Exception {
    mockWebServer.enqueue(
            new MockResponse()
                    .setBody(
                            """
{"success":true,"analysis":"{\\"entities\\":[{\\"type\\":\\"Person\\",\\"label\\":\\"Alice\\"},{\\"type\\":\\"Topic\\",\\"label\\":\\"AI\\"}],\\"relationships\\":[{\\"type\\":\\"DISCUSSES\\",\\"source\\":\\"Alice\\",\\"target\\":\\"AI\\"}]}","model":"test-model"}
""")
                    .addHeader("Content-Type", "application/json"));

    ConceptType person = new ConceptType();
    person.setName("Person");
    person.setDescription("A human");
    person.setShared(false);
    ConceptType topic = new ConceptType();
    topic.setName("Topic");
    topic.setDescription("A subject");
    topic.setShared(false);
    RelationshipType discusses = new RelationshipType();
    discusses.setName("DISCUSSES");
    discusses.setDescription("Connects a person to a topic");
    discusses.setSourceTypes(List.of("Person"));
    discusses.setTargetTypes(List.of("Topic"));

    var result =
            client.extract(
                    "Alice discussed AI in the chat",
                    List.of(person, topic),
                    List.of(discusses));

    assertThat(result).isNotNull();
    assertThat(result.entities()).isNotEmpty();
    assertThat(result.relationships()).isNotEmpty();
}
```

- [ ] **Step 6: Run all tests**

The `KnowledgeExtractionServiceTest` mocks use `any()` for the 2nd and 3rd args of `extract()` — these still match after the signature change. No changes needed there.

```bash
mvn test -pl emcip-knowledge-engine -am 2>&1 | tail -30
```

Expected: all tests PASS.

- [ ] **Step 7: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java \
  emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/client/LlmOrchestratorClientTest.java
git commit -m "feat(knowledge): ontology-driven extraction prompt with descriptions and directionality"
```

---

### Task 4: Result validation

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeExtractionServiceTest.java`

**Background:** After getting the LLM result, build `Set<String>` of known concept/relationship names from the already-loaded lists and filter out invalid entries before entity resolution.

- [ ] **Step 1: Write failing tests — invalid entries are skipped**

Add to `KnowledgeExtractionServiceTest.java`:

```java
import static org.mockito.Mockito.verifyNoInteractions;
import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
```

Add helper that stubs the standard save + embed behaviour used in all three new tests:

```java
private void stubSaveAndEmbed(String text) {
    when(llmClient.embed(text)).thenReturn(new float[]{0.1f});
    when(documentRepository.save(any()))
            .thenAnswer(inv -> {
                KnowledgeDocument doc = inv.getArgument(0);
                doc.setId(UUID.randomUUID());
                return doc;
            });
}

private ConceptType personType() {
    ConceptType ct = new ConceptType();
    ct.setName("PERSON");
    ct.setDescription("A human");
    ct.setShared(false);
    return ct;
}
```

Add test methods:

```java
@Test
void shouldSkipEntityWithNullType() {
    UUID tenantId = UUID.randomUUID();
    String text = "entity with null type";
    stubSaveAndEmbed(text);
    when(ontologyService.getAllConceptTypes()).thenReturn(List.of());
    when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of());
    when(llmClient.extract(any(), any(), any()))
            .thenReturn(new ExtractionResult(
                    List.of(new ExtractedEntity(null, "Alice", Map.of())),
                    List.of()));

    service.processMessage(text, "tg:1:1", tenantId, 1L, "1", "Alice", "G", 0);

    verifyNoInteractions(entityResolutionService);
    verifyNoInteractions(graphRepository);
}

@Test
void shouldSkipEntityWithUnknownType() {
    UUID tenantId = UUID.randomUUID();
    String text = "entity with unknown type";
    stubSaveAndEmbed(text);
    when(ontologyService.getAllConceptTypes()).thenReturn(List.of(personType()));
    when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of());
    when(llmClient.extract(any(), any(), any()))
            .thenReturn(new ExtractionResult(
                    List.of(new ExtractedEntity("INVENTED_TYPE", "Alice", Map.of())),
                    List.of()));

    service.processMessage(text, "tg:1:1", tenantId, 1L, "1", "Alice", "G", 0);

    verifyNoInteractions(entityResolutionService);
    verifyNoInteractions(graphRepository);
}

@Test
void shouldSkipRelationshipWithUnknownType() {
    UUID tenantId = UUID.randomUUID();
    String text = "relationship with unknown type";
    stubSaveAndEmbed(text);
    RelationshipType knows = new RelationshipType();
    knows.setName("KNOWS");
    knows.setDescription("Knows");
    knows.setSourceTypes(List.of("PERSON"));
    knows.setTargetTypes(List.of("PERSON"));
    when(ontologyService.getAllConceptTypes()).thenReturn(List.of(personType()));
    when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of(knows));
    UUID aliceId = UUID.randomUUID();
    when(entityResolutionService.resolve(eq("Alice"), eq("PERSON"), eq(tenantId)))
            .thenReturn(aliceId);
    when(llmClient.extract(any(), any(), any()))
            .thenReturn(new ExtractionResult(
                    List.of(new ExtractedEntity("PERSON", "Alice", Map.of())),
                    List.of(new ExtractedRelationship("INVENTED_REL", "Alice", "Bob", Map.of()))));

    service.processMessage(text, "tg:1:1", tenantId, 1L, "1", "Alice", "G", 0);

    verifyNoInteractions(graphRepository);
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
mvn test -pl emcip-knowledge-engine -Dtest="KnowledgeExtractionServiceTest#shouldSkipEntityWithNullType+shouldSkipEntityWithUnknownType+shouldSkipRelationshipWithUnknownType" -am 2>&1 | tail -30
```

Expected: FAIL — currently no validation, invalid entities/relationships pass through and `entityResolutionService` / `graphRepository` are called.

- [ ] **Step 3: Add validation filter to KnowledgeExtractionService**

Add imports:

```java
import java.util.Set;
```

(`java.util.stream.Collectors` and `java.util.List` are already imported after Tasks 2–3.)

In `processMessage()`, after `ExtractionResult result = llmClient.extract(text, conceptTypes, relTypes);` insert:

```java
// Build known-type sets for validation
Set<String> knownConceptNames =
        conceptTypes.stream().map(ConceptType::getName).collect(Collectors.toSet());
Set<String> knownRelNames =
        relTypes.stream().map(RelationshipType::getName).collect(Collectors.toSet());

// Validate and filter entities
List<ExtractedEntity> validEntities =
        result.entities().stream()
                .filter(
                        e -> {
                            if (e.type() == null
                                    || e.type().isBlank()
                                    || e.label() == null
                                    || e.label().isBlank()) {
                                log.warn(
                                        "Skipping invalid entity: type={}, label={}",
                                        e.type(),
                                        e.label());
                                return false;
                            }
                            if (!knownConceptNames.contains(e.type())) {
                                log.warn(
                                        "Skipping entity with unknown type: type={}, label={}",
                                        e.type(),
                                        e.label());
                                return false;
                            }
                            return true;
                        })
                .toList();

// Validate and filter relationships
List<ExtractedRelationship> validRelationships =
        result.relationships().stream()
                .filter(
                        r -> {
                            if (r.type() == null
                                    || r.type().isBlank()
                                    || r.source() == null
                                    || r.source().isBlank()
                                    || r.target() == null
                                    || r.target().isBlank()) {
                                log.warn(
                                        "Skipping invalid relationship: type={}, source={}, target={}",
                                        r.type(),
                                        r.source(),
                                        r.target());
                                return false;
                            }
                            if (!knownRelNames.contains(r.type())) {
                                log.warn(
                                        "Skipping relationship with unknown type: type={}, source={}, target={}",
                                        r.type(),
                                        r.source(),
                                        r.target());
                                return false;
                            }
                            return true;
                        })
                .toList();
```

Then replace the "Step 4" entity/relationship loops to iterate over `validEntities` and `validRelationships`:

```java
// Step 4: Entity resolution + graph storage
for (ExtractedEntity entity : validEntities) {
    entityResolutionService.resolve(entity.label(), entity.type(), tenantId);
}

for (ExtractedRelationship rel : validRelationships) {
    UUID sourceId =
            entityResolutionService.resolve(rel.source(), inferType(rel, true), tenantId);
    UUID targetId =
            entityResolutionService.resolve(rel.target(), inferType(rel, false), tenantId);

    graphRepository.createRelationship(
            rel.type(), sourceId, targetId, rel.properties(), saved.getId());
}

log.info(
        "Processed message {}: {} entities, {} relationships",
        sourceRef,
        validEntities.size(),
        validRelationships.size());
```

- [ ] **Step 4: Update existing `shouldStoreDocumentAndExtractEntities` for validation**

The test uses types `"Person"`, `"Topic"`, `"DISCUSSES"`. After validation, these must appear in the known-type sets. Add stubs in the test:

```java
ConceptType person = new ConceptType();
person.setName("Person");
person.setDescription("A human");
person.setShared(false);
ConceptType topic = new ConceptType();
topic.setName("Topic");
topic.setDescription("A subject");
topic.setShared(false);
when(ontologyService.getAllConceptTypes()).thenReturn(List.of(person, topic));

RelationshipType discusses = new RelationshipType();
discusses.setName("DISCUSSES");
discusses.setDescription("Discusses");
discusses.setSourceTypes(List.of("Person"));
discusses.setTargetTypes(List.of("Topic"));
when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of(discusses));
```

Add these stubs before the `service.processMessage(...)` call. The test already passes `any(), any()` for the type lists in the `extract()` mock, so no change there.

- [ ] **Step 5: Run all tests**

```bash
mvn test -pl emcip-knowledge-engine -am 2>&1 | tail -30
```

Expected: all tests PASS.

- [ ] **Step 6: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java \
  emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeExtractionServiceTest.java
git commit -m "feat(knowledge): validate extraction results against ontology, skip unknown types"
```

---

### Task 5: Integration test

**Files:**
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/KnowledgeExtractionIntegrationTest.java`

**Background:**
- `@IntegrationTest` starts a full Spring context with real Postgres via Testcontainers (`pgvector/pgvector:pg16`) and Kafka pointing to `localhost:14003`.
- We call `extractionService.processMessage()` directly (bypass Kafka) to test the service + DB path.
- `LlmOrchestratorClient` is replaced with `@MockitoBean` — no real LLM call.
- `llmClient.embed()` returns `new float[0]` so the service skips `vectorSearchRepository.storeEmbedding()` (avoids 1536-dim constraint in test).
- `@MockitoBean` is `org.springframework.test.context.bean.override.mockito.MockitoBean` (Spring Boot 4 / Spring Framework 6.2+).

- [ ] **Step 1: Write the integration test**

Create `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/KnowledgeExtractionIntegrationTest.java`:

```java
package io.emcip.knowledge.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedEntity;
import io.emcip.knowledge.engine.repository.ConceptTypeRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.service.KnowledgeExtractionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@IntegrationTest
class KnowledgeExtractionIntegrationTest {

    @Autowired private KnowledgeExtractionService extractionService;
    @Autowired private KnowledgeDocumentRepository documentRepository;
    @Autowired private ConceptTypeRepository conceptTypeRepository;

    @MockitoBean private LlmOrchestratorClient llmClient;

    @BeforeEach
    void clean() {
        documentRepository.deleteAll();
        conceptTypeRepository.deleteAll();
    }

    @Test
    void processMessage_persistsDocumentWithMetadata() {
        ConceptType person = new ConceptType();
        person.setName("PERSON");
        person.setDescription("A human individual");
        person.setShared(false);
        conceptTypeRepository.save(person);

        // Return empty float[] so storeEmbedding is skipped (avoids vector(1536) constraint)
        when(llmClient.embed(any())).thenReturn(new float[0]);
        when(llmClient.extract(any(), any(), any()))
                .thenReturn(
                        new ExtractionResult(
                                List.of(new ExtractedEntity("PERSON", "Alice", Map.of())),
                                List.of()));

        UUID tenantId = UUID.randomUUID();
        extractionService.processMessage(
                "Alice met Bob at the summit",
                "tg:100:42",
                tenantId,
                100L,
                "999",
                "Alice Smith",
                "TestGroup",
                1718272800);

        List<KnowledgeDocument> docs = documentRepository.findAll();
        assertThat(docs).hasSize(1);

        KnowledgeDocument doc = docs.get(0);
        assertThat(doc.getSourceRef()).isEqualTo("tg:100:42");
        assertThat(doc.getTenantId()).isEqualTo(tenantId);
        assertThat(doc.getMetadata()).isNotNull();
        assertThat(doc.getMetadata()).containsEntry("chatId", 100L);
        assertThat(doc.getMetadata()).containsEntry("chatTitle", "TestGroup");
        assertThat(doc.getMetadata()).containsEntry("senderDisplayName", "Alice Smith");
    }
}
```

- [ ] **Step 2: Run the integration test**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=KnowledgeExtractionIntegrationTest -am 2>&1 | tail -40
```

Expected: PASS. Testcontainers starts a `pgvector/pgvector:pg16` Postgres, Liquibase runs migrations, service saves a `KnowledgeDocument` with metadata, assertion passes.

**If the test fails with a Kafka context error** (consumer cannot connect to `localhost:14003` at startup), add the following to `emcip-knowledge-engine/src/test/resources/application-test.properties` (create if it doesn't exist):

```properties
spring.kafka.listener.auto-startup=false
```

This prevents Kafka listener containers from starting during the Spring context init, so tests that don't need Kafka can run without it.

- [ ] **Step 3: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add \
  emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/KnowledgeExtractionIntegrationTest.java
# If application-test.properties was created:
# git add emcip-knowledge-engine/src/test/resources/application-test.properties
git commit -m "test(knowledge): integration test for processMessage metadata persistence"
```

---

## Final check

After all tasks complete, run the full module test suite:

```bash
mvn test -pl emcip-knowledge-engine -am 2>&1 | tail -20
```

Expected: all tests PASS, `BUILD SUCCESS`.
