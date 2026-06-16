# US-26.4 Knowledge Extraction Pipeline — Design Spec

> **Status:** Approved
> **Date:** 2026-06-16
> **Backlog item:** #26.4

---

## Context

US-26.1/26.2/26.3 (PR #122/#123) delivered the structural foundation: pgvector + Apache AGE extensions, the `knowledge-engine` service skeleton, ontology tables (`concept_types`, `relationship_types`), and `KnowledgeExtractionService` with its pipeline stubs. The consumer, LLM client, and service already exist but have four meaningful gaps:

1. No DLQ — exceptions are swallowed silently.
2. Message metadata (chatId, sender, group) is discarded after routing; never persisted to `KnowledgeDocument`.
3. `LlmOrchestratorClient.extract()` sends a flat comma-string of type names; the LLM has no descriptions or directionality to guide extraction.
4. LLM results are used without validation; unknown types or malformed entities pass through unchecked.

This spec hardens those four gaps. It does not change the entity-resolution or graph-write steps (those belong to US-26.5).

---

## Architecture

The pipeline remains: Kafka consumer → `KnowledgeExtractionService` → LLM extract → vector embed → graph write. Changes are scoped to:

- `KafkaConfig` — add `DefaultErrorHandler` with DLQ
- `KnowledgeMessageConsumer` — remove try/catch; thread metadata through
- `KnowledgeExtractionService.processMessage()` — accept metadata params; persist them into `KnowledgeDocument.metadata`
- `LlmOrchestratorClient.extract()` — accept full `List<ConceptType>` / `List<RelationshipType>` objects; build structured prompt
- `KnowledgeExtractionService` validation — filter extracted results against known types; log and skip unknown/malformed

---

## 1. DLQ

**Config class:** `knowledge-engine/src/main/java/io/emcip/knowledge/config/KafkaConfig.java`

Add a `DefaultErrorHandler` bean wired to a `DeadLetterPublishingRecoverer`:

```java
@Bean
public DefaultErrorHandler knowledgeErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
    var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
        (record, ex) -> new TopicPartition("knowledge.raw.messages.DLT", record.partition()));
    var backOff = new FixedBackOff(1_000L, 3L);
    return new DefaultErrorHandler(recoverer, backOff);
}
```

Wire it into the `ConcurrentKafkaListenerContainerFactory`:

```java
factory.setCommonErrorHandler(knowledgeErrorHandler(kafkaTemplate));
```

**Consumer:** `KnowledgeMessageConsumer.java` — remove the `try/catch` block entirely. Let exceptions propagate; the container's error handler owns retry and DLQ routing.

**DLT topic:** `knowledge.raw.messages.DLT` — created automatically by `DeadLetterPublishingRecoverer` (Kafka auto-create enabled in dev; add explicit topic bean for production parity).

---

## 2. Metadata Preservation

**Event source:** `TelegramMessageEvent` already carries `chatId`, `senderId`, `senderDisplayName`, `chatTitle`, `messageDate` (epoch millis).

**Consumer change:** Extract those fields from the event and pass them to `processMessage()`:

```java
extractionService.processMessage(
    event.text(),
    sourceRef,
    tenantId,
    event.chatId(),
    event.senderId(),
    event.senderDisplayName(),
    event.chatTitle(),
    event.messageDate()
);
```

**Service signature:**

```java
public void processMessage(
    String text,
    String sourceRef,
    String tenantId,
    Long chatId,
    Long senderId,
    String senderDisplayName,
    String chatTitle,
    Long messageDate
)
```

**KnowledgeDocument persistence:** Populate `metadata` (existing `Map<String,Object>` JSONB field) before save:

```java
doc.setMetadata(Map.of(
    "chatId",            chatId,
    "senderId",          senderId,
    "senderDisplayName", senderDisplayName,
    "chatTitle",         chatTitle,
    "messageDate",       messageDate
));
```

---

## 3. Ontology-Driven LLM Prompt

**Current:** `LlmOrchestratorClient.extract()` accepts `String conceptTypes, String relationshipTypes` (comma-joined names only).

**New signature:**

```java
public ExtractionResult extract(String text, List<ConceptType> conceptTypes, List<RelationshipType> relationshipTypes)
```

**Prompt structure** (built inside the method):

```
Extract structured knowledge from the text below.

CONCEPT TYPES:
- <name>: <description>
  Properties: <comma-joined property names, or "none">

RELATIONSHIP TYPES:
- <name>: <description>
  Direction: <sourceTypes> → <targetTypes>

TEXT:
<text>

Return JSON:
{
  "entities": [{"type": "<ConceptType name>", "label": "<text>", "properties": {}}],
  "relationships": [{"type": "<RelationshipType name>", "source": "<label>", "target": "<label>", "properties": {}}]
}
```

**Caller change:** `KnowledgeExtractionService` loads `conceptTypeRepository.findAll()` and `relationshipTypeRepository.findAll()` once per extraction and passes the full lists.

---

## 4. Result Validation

After parsing the LLM JSON response, validate before any downstream write:

**Entity validation** — skip (log WARN) if:
- `type` is null or blank
- `label` is null or blank
- `type` does not match any `ConceptType.name` in the loaded list

**Relationship validation** — skip (log WARN) if:
- `type` is null or blank
- `source` or `target` is null or blank
- `type` does not match any `RelationshipType.name` in the loaded list

Log format:
```
log.warn("Skipping invalid entity: type={}, label={}", entity.type(), entity.label());
log.warn("Skipping invalid relationship: type={}, source={}, target={}", rel.type(), rel.source(), rel.target());
```

No exception is thrown for invalid entries; the valid remainder of the batch is processed normally.

---

## 5. Integration Test

**Location:** `knowledge-engine/src/test/java/io/emcip/knowledge/KnowledgeExtractionIntegrationTest.java`

**Harness:** `@IntegrationTest` + `TestcontainersInitializer` (existing setup — `pgvector/pgvector:pg16`, Kafka at `localhost:14003`).

**Test scenario:**

1. Seed one `ConceptType` (e.g. `PERSON`) and one `RelationshipType` (e.g. `KNOWS`) via repository.
2. Stub `LlmOrchestratorClient.extract()` to return a canned `ExtractionResult` with one entity.
3. Publish a `TelegramMessageEvent` to `knowledge.raw.messages` via `KafkaTemplate`.
4. `await().atMost(10, SECONDS)` until `knowledgeDocumentRepository.count() == 1`.
5. Assert:
   - `doc.sourceRef()` matches the expected `msg:<chatId>:<messageId>` pattern.
   - `doc.metadata()` is non-null and contains key `"chatId"`.
   - `doc.tenantId()` equals the test tenant.

**DLQ test scenario:**

1. Stub `LlmOrchestratorClient.extract()` to throw `RuntimeException`.
2. Publish event.
3. `await().atMost(15, SECONDS)` until DLT topic has 1 record (consume via `KafkaConsumer` in test).
4. Assert `knowledgeDocumentRepository.count() == 0`.

---

## Out of Scope

- Entity resolution / deduplication → US-26.5
- Live message fork → US-26.6
- Bulk backfill → US-26.7
- Document ingestion → US-26.8
- Knowledge query API → US-26.9
