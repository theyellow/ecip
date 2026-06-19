# US-26.9 — Knowledge Query API

**Date**: 2026-06-19
**Status**: Approved
**Backlog item**: #26.9
**Size**: M

---

## 1. Overview

Exposes the knowledge-engine's search and graph-exploration capabilities to operators via the Admin-UI. Operators can query the knowledge base using natural language with three modes (VECTOR, GRAPH, HYBRID), browse extracted entities (Topics, Persons) as a side-effect of search results, and expand any entity to see its graph neighbors inline.

The search foundation (service + controller + repositories) is partially built. This story fixes the placeholder similarity scoring, adds a real pgvector score query, wires the Admin-API proxy, and builds the Search tab on the existing Knowledge page.

---

## 2. Architecture & Data Flow

```
Admin-UI (Knowledge page — Search tab)
    │  POST /api/admin/knowledge/search
    │  GET  /api/admin/knowledge/graph/topics
    │  GET  /api/admin/knowledge/graph/persons
    │  GET  /api/admin/knowledge/graph/node/{id}/neighbors
    ▼
Admin-API: KnowledgeSearchProxyController
    │  WebClient + @CircuitBreaker("knowledge")
    ▼
Knowledge-Engine: KnowledgeSearchController → KnowledgeQueryService
    │  VectorSearchRepository.search() → real cosine similarity scores
    │  GraphRepository.findNodesByType() / findConnected()
    ▼
PostgreSQL (pgvector cosine distance, Apache AGE graph)
```

---

## 3. Knowledge-Engine

### 3.1 New: `SearchResult<T>` record

```java
package io.emcip.knowledge.engine.model;

public record SearchResult<T>(T item, double score) {}
```

Carries a pgvector cosine similarity score alongside the result item. Used only in the repository layer — the service maps it to the existing `SearchResponse` DTOs.

### 3.2 Modified: `VectorSearchRepository` + `PgVectorSearchRepositoryImpl`

Change `search()` signature:

```java
// Before
List<KnowledgeDocument> search(float[] queryEmbedding, int topK, UUID tenantId);

// After
List<SearchResult<KnowledgeDocument>> search(float[] queryEmbedding, int topK, UUID tenantId);
```

Native SQL in the implementation adds the cosine similarity as a computed column:

```sql
SELECT kd.*, 1 - (kd.embedding <=> CAST(:embedding AS vector)) AS score
FROM ke_knowledge_documents kd
WHERE (:tenantId IS NULL OR kd.tenant_id = :tenantId OR kd.tenant_id IS NULL)
ORDER BY kd.embedding <=> CAST(:embedding AS vector)
LIMIT :topK
```

Map each row to `new SearchResult<>(document, rs.getDouble("score"))`.

### 3.3 Modified: `KnowledgeQueryService`

Replace the placeholder scoring formula `1.0 - (i * 0.05)` with real scores from the repository:

```java
List<SearchResult<KnowledgeDocument>> scored = vectorSearchRepository.search(
        queryEmbedding, request.limit(), request.tenantId());
for (SearchResult<KnowledgeDocument> sr : scored) {
    documentResults.add(new DocumentResult(sr.item(), sr.score()));
}
```

For graph results (GRAPH / HYBRID mode): score defaults to `0.5` when no corresponding document score is available. The existing `findNodesByType` + `findConnected` calls are unchanged.

### 3.4 Modified: `SearchRequest`

Add `@NotBlank` validation on `query` field:

```java
public record SearchRequest(
        @NotBlank String query,
        SearchType searchType,
        UUID tenantId,
        List<String> conceptTypes,
        List<String> sourceTypes,
        int limit) { ... }
```

Add `@Valid` on `@RequestBody` in `KnowledgeSearchController.search()`.

### 3.5 Tests

**`PgVectorSearchRepositoryTest`** (Testcontainers — already used in project):
- Insert two `KnowledgeDocument` rows with known embeddings
- Call `search()` with a query embedding close to one of them
- Assert the closer document scores higher, scores are in `(0, 1]`

**`KnowledgeQueryServiceTest`** (unit — already exists, extend):
- Mock repository returns `List<SearchResult<KnowledgeDocument>>` with explicit scores
- Assert `SearchResponse.documentResults()` carries those scores (not placeholder)
- Add: HYBRID mode → both `graphResults` and `documentResults` populated
- Add: GRAPH-only mode → `vectorSearchRepository.search()` not called

---

## 4. Admin-API

### 4.1 New: `KnowledgeSearchProxyController`

Pure proxy — no business logic. Mirrors `DocumentIngestionProxyController` and `BackfillProxyController`.

```java
@RestController
@RequestMapping("/api/admin/knowledge")
@Slf4j
public class KnowledgeSearchProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;

    // Manual constructor injection with @Qualifier("knowledgeWebClient")

    @PostMapping("/search")
    public Mono<ResponseEntity<String>> search(@RequestBody String body) { ... }

    @GetMapping("/graph/topics")
    public Mono<ResponseEntity<String>> getTopics(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) { ... }

    @GetMapping("/graph/persons")
    public Mono<ResponseEntity<String>> getPersons(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) { ... }

    @GetMapping("/graph/node/{id}/neighbors")
    public Mono<ResponseEntity<String>> getNeighbors(
            @PathVariable UUID id,
            @RequestParam(required = false) String relationshipType,
            @RequestParam(defaultValue = "1") int depth) { ... }
}
```

All four return `Mono<ResponseEntity<String>>`. Errors → 503 via `CircuitBreakerOperator`.

### 4.2 Test: `KnowledgeSearchProxyControllerTest`

- WireMock: `POST /api/knowledge/search` → 200 JSON, assert forwarded correctly
- WireMock: `GET /api/knowledge/graph/topics` → 200 JSON
- WireMock: `GET /api/knowledge/graph/persons` → 200 JSON
- WireMock: `GET /api/knowledge/graph/node/{id}/neighbors` → 200 JSON
- Knowledge-engine 500 → assert 503 returned to caller

---

## 5. Admin-UI

### 5.1 Modified: `knowledge.js`

Add four new API methods to `knowledgeApi(request, rawFetch)`:

```js
search: (query, searchType = 'HYBRID', tenantId, conceptTypes, limit = 20) =>
  request('/api/admin/knowledge/search', {
    method: 'POST',
    body: JSON.stringify({ query, searchType, tenantId: tenantId ?? null,
                           conceptTypes: conceptTypes ?? null, limit }),
  }),

graphTopics: (tenantId, limit = 50) => {
  const params = new URLSearchParams({ limit })
  if (tenantId) params.append('tenantId', tenantId)
  return request(`/api/admin/knowledge/graph/topics?${params}`)
},

graphPersons: (tenantId, limit = 50) => {
  const params = new URLSearchParams({ limit })
  if (tenantId) params.append('tenantId', tenantId)
  return request(`/api/admin/knowledge/graph/persons?${params}`)
},

graphNeighbors: (nodeId, relationshipType, depth = 1) => {
  const params = new URLSearchParams({ depth })
  if (relationshipType) params.append('relationshipType', relationshipType)
  return request(`/api/admin/knowledge/graph/node/${encodeURIComponent(nodeId)}/neighbors?${params}`)
},
```

### 5.2 Modified: `KnowledgePage.jsx`

Replace the current page structure with a three-item segmented control at the top:

```
[ Search ]  [ Ingestion Jobs ]
```

(Two tabs — Ingestion Jobs tab is unchanged from US-26.8.)

**Search tab layout:**

```
┌─ query input ────────────────────────────────┐ [Search]
│ HYBRID ▾  │  Tenant ▾
└──────────────────────────────────────────────┘

┌─ ENTITIES ──────────────┐  ┌─ PASSAGES ──────────────┐
│  PERSON · 0.94          │  │  "The EU AI Act will..." │
│  Alice Meyer            │  │  0.91 · Tech Debate      │
│  → HOLDS_OPINION · AI   │  │  2026-06-12              │
│  → MEMBER_OF · Forum    │  ├─────────────────────────┤
├─────────────────────────┤  │  "AI governance needs..."│
│  TOPIC · 0.88           │  │  0.88 · Policy Group     │
│  AI Policy              │  └─────────────────────────┘
│  → DISCUSSED_IN · 3 grp │
└─────────────────────────┘

▼ (entity clicked — neighbor expansion panel below both columns)
┌─ ALICE MEYER · NEIGHBORS ───────────────────────────────┐
│  [AI Policy · Topic]  [Tech Forum · Group]  [EU Reg · T] │
└──────────────────────────────────────────────────────────┘
```

**State:**
- `activeTab`: `'search'` | `'jobs'`
- `query`, `searchType` (`'HYBRID'`), `tenantId`
- `results`: `{ graphResults: [], documentResults: [] } | null`
- `loading`, `error`
- `expandedNodeId`: UUID | null — which entity's neighbors are shown
- `neighbors`: `GraphNode[]` — fetched on entity click via `api.graphNeighbors(nodeId)`

**Entity card click:** calls `api.graphNeighbors(node.id)`, sets `expandedNodeId`. If same node clicked again, collapses (sets to null). Neighbor panel renders below the two columns as a chip row.

**Search type segmented control:** VECTOR / GRAPH / HYBRID (three segments). Default: HYBRID.

**Empty states:**
- Before first search: "Enter a query to search the knowledge base."
- No results: "No results found. Try a different query or search type."
- Error: error message from API.

### 5.3 New: `KnowledgePage.module.css` additions

New CSS classes (additions only — existing classes from US-26.8 unchanged):

- `.searchBar` — flex row: input + type selector + search button
- `.typeSelector` — segmented control strip (VECTOR / GRAPH / HYBRID)
- `.resultsGrid` — `display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4)`
- `.entityCard`, `.passageCard` — bordered cards, `border-radius: 0`
- `.entityConnections` — mono 11px connection lines below entity name
- `.neighborPanel` — expansion panel spanning full width below the grid; chip row
- `.neighborChip` — `border: 1px solid var(--border)`, mono 11px, no radius
- `.scoreTag` — mono 10px, `color: var(--signal-ok-fg)` for high scores (≥ 0.85), `var(--fg-3)` otherwise

---

## 6. Out of Scope

- Time range and sourceType filters on `SearchRequest` (fields exist, UI filter controls deferred to 26.10)
- Pagination of search results (limit param is sufficient for 26.9)
- Saving / bookmarking search results
- Node detail page (neighbor expansion inline is sufficient; a dedicated node page is a 27.x concern)
- Graph visualization (force-directed graph rendering — Deep Research Agent, #27)
