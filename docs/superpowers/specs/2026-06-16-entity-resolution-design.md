# US-26.5 Entity Resolution — Design Spec

> **Status:** Approved
> **Date:** 2026-06-16
> **Backlog item:** #26.5

---

## Context

`EntityResolutionService.resolve()` currently implements two levels: exact label match against the graph and alias table lookup. When both miss it creates a new node unconditionally. This causes duplicate graph nodes for near-synonyms ("AI" vs "Artificial Intelligence" vs "Artificial intelligence") that aren't captured in the alias table.

US-26.4 added the extraction pipeline. US-26.5 hardens the resolution step by adding embedding-based similarity as a third level, and an operator review queue for borderline cases.

The `ke_graph_node_embeddings` shadow table already exists in the schema (migration 008) but is never written to or queried. This story populates it lazily and queries it during resolution.

---

## Architecture

Resolution levels (in order):

1. **Exact match** — normalized lowercase label against graph (existing)
2. **Alias table** — `ke_entity_aliases` lookup (existing)
3. **Embedding similarity** — lazy embed → query `ke_graph_node_embeddings` top-1 nearest neighbour of same concept type + tenant
   - Score ≥ `mergeThreshold` (default 0.92) → merge (return existing node ID)
   - Score ≥ `flagThreshold` (default 0.80) and < `mergeThreshold` → create new node + write `ResolutionFlag` row → return new node ID
   - Score < `flagThreshold` or no neighbours → create new node silently → return new node ID
4. **Fallback — create new** — same as today

Embedding failures (`llmClient.embed()` throws or returns `float[0]`) are non-fatal: skip Level 3 entirely, fall through to Level 4. Failed flag writes are non-fatal and logged WARN.

---

## New Infrastructure

### `ResolutionFlag` entity + `ResolutionFlagRepository`

Table: `ke_resolution_flags`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | generated |
| `candidate_label` | VARCHAR(500) | the incoming label |
| `candidate_node_id` | UUID | newly created node |
| `similar_label` | VARCHAR(500) | the near-match in the graph |
| `similar_node_id` | UUID | the near-match node ID |
| `concept_type` | VARCHAR(100) | |
| `similarity_score` | DOUBLE PRECISION | cosine similarity value |
| `tenant_id` | UUID | nullable (shared knowledge) |
| `status` | VARCHAR(20) | DEFAULT 'PENDING' |
| `created_at` | TIMESTAMP | not null |

`ResolutionFlagRepository` — JPA, extends `JpaRepository<ResolutionFlag, UUID>`.

### `GraphNodeEmbeddingRepository`

JDBC-based, wraps `ke_graph_node_embeddings`.

Methods:
```java
Optional<float[]> findEmbedding(String label, String conceptType, UUID tenantId);
void storeEmbedding(String label, String conceptType, UUID tenantId, float[] embedding);
// Returns top-1 nearest neighbour; empty if table has no rows for this type/tenant
Optional<NodeSimilarityResult> findNearestNeighbour(float[] embedding, String conceptType, UUID tenantId);
```

`NodeSimilarityResult` — record:
```java
record NodeSimilarityResult(UUID nodeId, String label, double score) {}
```

The `findNearestNeighbour` SQL:
```sql
SELECT node_id, label, 1 - (embedding <=> ?::vector) AS score
FROM ke_graph_node_embeddings
WHERE concept_type = ?
  AND (tenant_id = ? OR tenant_id IS NULL)
  AND embedding IS NOT NULL
ORDER BY embedding <=> ?::vector
LIMIT 1
```

The `storeEmbedding` SQL uses `INSERT ... ON CONFLICT (label, concept_type, tenant_id) DO UPDATE SET embedding = EXCLUDED.embedding` (requires a unique constraint added in the migration).

### `ResolutionConfig`

```java
@ConfigurationProperties(prefix = "knowledge.resolution")
public record ResolutionConfig(
    double mergeThreshold,   // default 0.92
    double flagThreshold     // default 0.80
) {}
```

Defaults in `application.properties`:
```properties
knowledge.resolution.merge-threshold=0.92
knowledge.resolution.flag-threshold=0.80
```

---

## Modified: `EntityResolutionService`

New dependencies injected: `GraphNodeEmbeddingRepository`, `ResolutionFlagRepository`, `ResolutionConfig`.

Updated `resolve()` method — Level 3 inserted between alias check and new-node creation:

```
// Level 3: Embedding similarity
Optional<float[]> existingEmbedding = nodeEmbeddingRepository.findEmbedding(normalized, conceptType, tenantId);
float[] embedding;
if (existingEmbedding.isPresent()) {
    embedding = existingEmbedding.get();
} else {
    embedding = llmClient.embed(normalized);
    if (embedding.length > 0) {
        nodeEmbeddingRepository.storeEmbedding(normalized, conceptType, tenantId, embedding);
    }
}

if (embedding.length > 0) {
    Optional<NodeSimilarityResult> nearest = nodeEmbeddingRepository.findNearestNeighbour(embedding, conceptType, tenantId);
    if (nearest.isPresent()) {
        double score = nearest.get().score();
        if (score >= config.mergeThreshold()) {
            log.debug("Entity merged by similarity: {} -> {} (score={})", label, nearest.get().label(), score);
            return nearest.get().nodeId();
        } else if (score >= config.flagThreshold()) {
            // Create new node, write flag
            GraphNode newNode = graphRepository.createNode(conceptType, normalized, Map.of(), tenantId);
            writeFlagSafely(label, newNode.id(), nearest.get(), conceptType, score, tenantId);
            return newNode.id();
        }
    }
}

// Level 4: Create new
GraphNode newNode = graphRepository.createNode(...);
return newNode.id();
```

`writeFlagSafely()` — private method, wraps the flag write in try/catch, logs WARN on failure.

---

## Liquibase Migration (new)

File: `009-create-resolution-flags.xml`

1. Create `ke_resolution_flags` table with all columns above
2. Add `status` index: `idx_ke_res_flags_status_tenant` on `(status, tenant_id)`
3. Add unique constraint on `ke_graph_node_embeddings(label, concept_type, tenant_id)` (required for upsert)

---

## Backlog

A new item `43` is added to BACKLOG §2:

> **#43 — Entity resolution review UI** (S) — Admin page listing PENDING `ke_resolution_flags` rows. Operator can merge (delete candidate node, reroute relationships) or dismiss (mark as REVIEWED). Needs 26.5.

---

## Testing

### Unit tests (`EntityResolutionServiceTest`)

- Score ≥ merge threshold → existing node ID returned, no flag written
- Score in grey zone → new node created, `ResolutionFlag` saved
- Score < flag threshold → new node created, no flag written
- `llmClient.embed()` throws → skips similarity, creates new node (non-fatal)
- `llmClient.embed()` returns `float[0]` → skips similarity, creates new node

### Integration test (`EntityResolutionIntegrationTest`)

`@IntegrationTest` + Testcontainers (`pgvector/pgvector:pg16`). `GraphRepository` (`AgeGraphRepository`) mocked with `@MockitoBean` (AGE not available in test image).

Scenario:
1. Insert a row directly into `ke_graph_node_embeddings` with a known embedding for label "artificial intelligence", type "TOPIC", test tenantId
2. Stub `llmClient.embed("ai")` to return a vector with cosine similarity > 0.92 to the seeded embedding
3. Stub `graphRepository.createNode(...)` as `@MockitoBean` (returns a test node)
4. Call `service.resolve("AI", "TOPIC", tenantId)`
5. Assert returned ID equals the seeded node ID (merge path)
6. Assert `resolutionFlagRepository.count() == 0` (no flag for above-threshold merge)

---

## Out of Scope

- Operator review UI → backlog item #43
- Bulk re-resolution of existing nodes → not needed yet
- Multi-candidate comparison → top-1 is sufficient for now
