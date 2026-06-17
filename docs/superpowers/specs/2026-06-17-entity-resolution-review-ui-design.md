# #43 Entity Resolution Review UI — Design Spec

> **Status:** Approved
> **Date:** 2026-06-17
> **Backlog item:** #43

---

## Context

US-26.5 (PR #133) introduced embedding-based entity resolution with a two-threshold system: entities with cosine similarity ≥ 0.92 are auto-merged; those in the grey zone [0.80, 0.92) produce a new graph node **and** a `ke_resolution_flags` row for operator review. Without a UI, those `PENDING` rows are invisible.

This story adds:
1. A REST API in `emcip-knowledge-engine` to list, merge, and dismiss resolution flags.
2. A proxy in `emcip-admin-api` (API Gateway pattern, same as `CostsProxyController`).
3. A **Resolution Queue** page in `emcip-admin-ui`.

---

## Architecture

Three layers, each with a single responsibility:

```
emcip-admin-ui
  └─ Resolution Queue page
      └─ /api/resolution-review/**  →  emcip-admin-api (proxy)
                                         └─ /api/resolution-review/**  →  emcip-knowledge-engine
                                                                            ├─ ResolutionReviewController
                                                                            ├─ ResolutionReviewService
                                                                            └─ GraphRepository.mergeNodes()
```

`emcip-admin-api` adds **no business logic** — it is a pure proxy with auth/circuit-breaker, like `CostsProxyController`.

---

## Part 1 — `emcip-knowledge-engine` REST API

### 1.1 `ResolutionFlagRepository` additions

Add one custom query method:

```java
@Query("""
    SELECT f FROM ResolutionFlag f
    WHERE (:status IS NULL OR f.status = :status)
      AND (:conceptType IS NULL OR f.conceptType = :conceptType)
      AND (:tenantId IS NULL OR f.tenantId = :tenantId)
    ORDER BY f.createdAt DESC
    """)
Page<ResolutionFlag> findFiltered(
    @Param("status") String status,
    @Param("conceptType") String conceptType,
    @Param("tenantId") UUID tenantId,
    Pageable pageable);
```

### 1.2 `GraphRepository` addition

Add one method to the interface:

```java
/**
 * Reroutes all edges from candidateNodeId to targetNodeId in the AGE graph,
 * then deletes the candidate node. Throws RuntimeException on failure.
 */
void mergeNodes(UUID candidateNodeId, UUID targetNodeId);
```

### 1.3 AGE version requirement

**Apache AGE 1.5.0** is the release that targets PostgreSQL 16. All AGE usage (Dockerfile, Helm chart, pgvector Testcontainers image selection) must pin to AGE 1.5.0 for PG16.

The existing `pgvector/pgvector:pg16` test image does not include AGE. This is already handled in integration tests by always using `@MockitoBean GraphRepository`. The production image (Helm/k8s) must have AGE 1.5.0 installed — this is a deployment concern tracked separately; do not block this story on it.

### 1.4 `AgeGraphRepository` implementation of `mergeNodes()`

AGE 1.5.0 Cypher does not support dynamic relationship types in a `CREATE` clause (i.e., you cannot write `CREATE (a)-[:rtype]->(b)` where `rtype` is a variable). The edge-rerouting must therefore be done at the **Java level**: query edges first via `queryEdges()`, then create each replacement edge individually via the existing `createRelationship()`, then delete the candidate.

Add a private `queryEdges(String cypher)` helper to `AgeGraphRepository` (similar to the existing `queryNodes()` helper) that returns `List<GraphEdge>`.

`mergeNodes()` algorithm (all wrapped in a try/catch that rethrows as `RuntimeException` to propagate to the `@Transactional` service):

```java
@Override
public void mergeNodes(UUID candidateNodeId, UUID targetNodeId) {
    // Step 1: collect outgoing edges from candidate
    List<GraphEdge> outgoing = queryEdges(String.format(
        "MATCH (c {node_id: '%s'})-[r]->(n) WHERE NOT n.node_id = '%s' RETURN r",
        candidateNodeId, targetNodeId));

    // Step 2: collect incoming edges to candidate
    List<GraphEdge> incoming = queryEdges(String.format(
        "MATCH (n)-[r]->(c {node_id: '%s'}) WHERE NOT n.node_id = '%s' RETURN r",
        candidateNodeId, targetNodeId));

    // Step 3: recreate outgoing edges from target
    for (GraphEdge e : outgoing) {
        createRelationship(e.relationshipType(), targetNodeId,
            e.targetNodeId(), e.properties(), e.sourceMessageId());
    }

    // Step 4: recreate incoming edges to target
    for (GraphEdge e : incoming) {
        createRelationship(e.relationshipType(), e.sourceNodeId(),
            targetNodeId, e.properties(), e.sourceMessageId());
    }

    // Step 5: delete candidate node (DETACH removes any remaining self-edges)
    executeCypher(String.format(
        "MATCH (c {node_id: '%s'}) DETACH DELETE c", candidateNodeId));

    log.info("Merged AGE node {} into {}: {} outgoing + {} incoming edges rerouted",
        candidateNodeId, targetNodeId, outgoing.size(), incoming.size());
}
```

`queryEdges(String cypher)` follows the same pattern as the existing `queryNodes()` but maps `GraphEdge` from the agtype result. It must parse `sourceNodeId`, `targetNodeId`, `relationshipType`, and `properties` from the agtype row — using the existing `parseEdgeFromAgtype()` helper if it exists, or a new one following the same pattern as `parseNodeFromAgtype()`.

If any step throws, the exception propagates to the service layer which is `@Transactional(rollbackFor = Exception.class)` — the flag status update is rolled back.

### 1.5 `ResolutionReviewService`

```java
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResolutionReviewService {

    private final ResolutionFlagRepository flagRepository;
    private final GraphRepository graphRepository;

    public Page<ResolutionFlag> list(String status, String conceptType,
                                     UUID tenantId, Pageable pageable) {
        return flagRepository.findFiltered(status, conceptType, tenantId, pageable);
    }

    @Transactional(rollbackFor = Exception.class)
    public void merge(UUID flagId) {
        ResolutionFlag flag = flagRepository.findById(flagId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"PENDING".equals(flag.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Flag is not PENDING: " + flag.getStatus());
        }
        // Graph operation first — if it throws, flag update is rolled back
        graphRepository.mergeNodes(flag.getCandidateNodeId(), flag.getSimilarNodeId());
        flag.setStatus("MERGED");
        flagRepository.save(flag);
        log.info("Merged node {} into {} (flag={})",
            flag.getCandidateNodeId(), flag.getSimilarNodeId(), flagId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void dismiss(UUID flagId) {
        ResolutionFlag flag = flagRepository.findById(flagId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"PENDING".equals(flag.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Flag is not PENDING: " + flag.getStatus());
        }
        flag.setStatus("DISMISSED");
        flagRepository.save(flag);
        log.info("Dismissed resolution flag {}", flagId);
    }
}
```

### 1.6 `ResolutionReviewController`

```java
@RestController
@RequestMapping("/api/resolution-review")
@RequiredArgsConstructor
@Slf4j
public class ResolutionReviewController {

    private final ResolutionReviewService service;

    @GetMapping
    public Page<ResolutionFlag> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String conceptType,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(status, conceptType, tenantId,
            PageRequest.of(page, Math.min(size, 200), Sort.by("createdAt").descending()));
    }

    @PatchMapping("/{id}/merge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void merge(@PathVariable UUID id) {
        service.merge(id);
    }

    @PatchMapping("/{id}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable UUID id) {
        service.dismiss(id);
    }
}
```

No Spring Security on the knowledge-engine controller — it uses `X-Service-Token` header validation already present in the existing `KnowledgeSearchController` pattern (internal-only service, not exposed to the internet).

### 1.7 Response shape

`GET /api/resolution-review` returns Spring `Page<ResolutionFlag>` serialised to:

```json
{
  "content": [
    {
      "id": "uuid",
      "candidateLabel": "AI",
      "candidateNodeId": "uuid",
      "similarLabel": "artificial intelligence",
      "similarNodeId": "uuid",
      "conceptType": "TOPIC",
      "similarityScore": 0.87,
      "tenantId": "uuid",
      "status": "PENDING",
      "createdAt": "2026-06-17T10:00:00Z"
    }
  ],
  "totalElements": 42,
  "totalPages": 3,
  "number": 0,
  "size": 20
}
```

---

## Part 2 — `emcip-admin-api` Proxy

### 2.1 `WebClientConfig` addition

Add a `knowledgeWebClient` bean:

```java
@Bean("knowledgeWebClient")
public WebClient knowledgeWebClient(
        @Value("${service.knowledge.url}") String knowledgeUrl,
        @Value("${admin.service-token}") String serviceToken) {
    return buildWebClient(
        WebClient.builder().defaultHeader("X-Service-Token", serviceToken),
        knowledgeUrl,
        Duration.ofSeconds(30));
}
```

### 2.2 `application.yml` addition

```yaml
service:
  knowledge:
    url: ${SERVICE_KNOWLEDGE_URL:http://localhost:9088}
```

Add `knowledge` circuit-breaker and retry instances following the existing pattern for `policy-engine`, `audit-service`, etc.

### 2.3 `ResolutionReviewProxyController`

```java
@Slf4j
@RestController
@RequestMapping("/api/resolution-review")
@PreAuthorize("hasAuthority('RESOLUTION_REVIEW_READ')")
@Tag(name = "Resolution Review", description = "Proxy to knowledge-engine resolution review API")
public class ResolutionReviewProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;

    public ResolutionReviewProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge");
    }

    @GetMapping
    public Mono<String> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String conceptType,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return knowledgeWebClient.get()
            .uri(b -> b.path("/api/resolution-review")
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParamIfPresent("conceptType", Optional.ofNullable(conceptType))
                .queryParamIfPresent("tenantId", Optional.ofNullable(tenantId))
                .queryParam("page", page)
                .queryParam("size", size)
                .build())
            .retrieve()
            .bodyToMono(String.class)
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @PatchMapping("/{id}/merge")
    @PreAuthorize("hasAuthority('RESOLUTION_REVIEW_WRITE')")
    public Mono<Void> merge(@PathVariable UUID id) {
        return knowledgeWebClient.patch()
            .uri("/api/resolution-review/{id}/merge", id)
            .retrieve()
            .bodyToMono(Void.class)
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @PatchMapping("/{id}/dismiss")
    @PreAuthorize("hasAuthority('RESOLUTION_REVIEW_WRITE')")
    public Mono<Void> dismiss(@PathVariable UUID id) {
        return knowledgeWebClient.patch()
            .uri("/api/resolution-review/{id}/dismiss", id)
            .retrieve()
            .bodyToMono(Void.class)
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
```

### 2.4 Permissions

In `emcip-admin-api` `SecurityConfig` (or wherever `@PreAuthorize` authorities are sourced from JWT claims), no changes needed — authorities are read from the JWT. The new permissions must be added to `permissions.js` in the UI (see Part 3) and added to the JWT generation if it uses a static list. Check `AuthService` to confirm.

---

## Part 3 — `emcip-admin-ui` Resolution Queue page

### 3.1 Permissions (`src/auth/permissions.js`)

Add to both `ADMIN` and `TENANT_ADMIN`:

```js
'RESOLUTION_REVIEW_READ', 'RESOLUTION_REVIEW_WRITE',
```

### 3.2 Nav entry (`src/layout/Sidebar/Sidebar.jsx`)

Add to the NAV array (after Decisions / `⚑`):

```js
{ path: '/resolution-queue', label: 'Resolution Queue', icon: '⊗', permission: 'RESOLUTION_REVIEW_READ' },
```

Glyph: `⊗` (U+2297, CIRCLED TIMES) — represents merge/intersection. Add to the iconography table:

| `⊗` | U+2297 | Resolution Queue |

### 3.3 Route (`src/App.jsx`)

```jsx
<Route path="/resolution-queue" element={<ResolutionQueue />} />
```

### 3.4 API module (`src/api/resolutionReview.js`)

```js
export function resolutionReviewApi(request) {
  return {
    list: (page, size, status, conceptType, tenantId) => {
      const params = new URLSearchParams({ page, size })
      if (status) params.set('status', status)
      if (conceptType) params.set('conceptType', conceptType)
      if (tenantId) params.set('tenantId', tenantId)
      return request(`/api/resolution-review?${params}`)
    },
    merge: (id) =>
      request(`/api/resolution-review/${encodeURIComponent(id)}/merge`, { method: 'PATCH' }),
    dismiss: (id) =>
      request(`/api/resolution-review/${encodeURIComponent(id)}/dismiss`, { method: 'PATCH' }),
  }
}
```

Wire into the existing API factory (wherever `flagsApi`, `costsApi`, etc. are assembled).

### 3.5 Page component (`src/pages/ResolutionQueue/ResolutionQueue.jsx`)

**Layout:** Page header → filter bar → table → pagination. Follows the AuditLog pattern.

**Page header:**
```jsx
<div className="page-header">
  <div>
    <h2>RESOLUTION QUEUE</h2>
    <div className="system-id">⊗ knowledge-engine · 9088 · entity deduplication</div>
  </div>
</div>
```

**Filters:**
- `Status` dropdown: All / PENDING / MERGED / DISMISSED (default: PENDING)
- `Concept Type` dropdown: All + distinct values from the loaded data (populated client-side from first page, or a future `/api/resolution-review/concept-types` endpoint — use client-side for now)
- `Page size` selector: 10 / 20 / 50

**Table columns:**

| Column | Source field | Rendering |
|--------|-------------|-----------|
| Created | `createdAt` | Formatted date, mono |
| Candidate | `candidateLabel` | Plain text |
| Similar To | `similarLabel` | Plain text |
| Type | `conceptType` | `<Badge variant="blue">` |
| Score | `similarityScore` | Mono, amber `--signal-warn-fg` if 0.80–0.92 |
| Status | `status` | `<Badge>` — PENDING=yellow, MERGED=green, DISMISSED=gray |
| Actions | — | Merge + Dismiss buttons (disabled when status ≠ PENDING) |

**Per-row action buttons:**
- `Merge` — `<Button variant="primary">` — opens `<ConfirmDialog>` before acting
- `Dismiss` — `<Button variant="secondary">` — opens `<ConfirmDialog>` before acting

Both actions are irreversible and require confirmation. Use the existing `<ConfirmDialog>` component (`components/ConfirmDialog/ConfirmDialog.jsx`):

- **Merge confirm:**
  - Title: `Merge Entity`
  - Body: `Merge "{candidateLabel}" into "{similarLabel}"? This will delete the candidate node and reroute all its graph relationships. This cannot be undone.`
  - Confirm button: `Merge` (primary)

- **Dismiss confirm:**
  - Title: `Dismiss Flag`
  - Body: `Dismiss this resolution flag for "{candidateLabel}"? The candidate node will be kept as a separate entity.`
  - Confirm button: `Dismiss` (secondary)

On confirm: disable both row buttons immediately (optimistic), call the API, then re-fetch list on settle.

On error: show inline error toast (follow the pattern used in Decisions page for errors).

**Empty state:** `No resolution flags found.` italic `var(--fg-3)`.

**Default state:** page loads with `status=PENDING` so operators see only actionable items.

---

## Testing

### `emcip-knowledge-engine` unit tests

- `ResolutionReviewServiceTest` (Mockito, no DB):
  - `merge()` happy path: `graphRepository.mergeNodes()` called, flag status = MERGED
  - `merge()` on non-PENDING flag → 409 CONFLICT
  - `merge()` when `graphRepository.mergeNodes()` throws → transaction rolls back, flag unchanged
  - `dismiss()` happy path: flag status = DISMISSED
  - `dismiss()` on non-PENDING flag → 409 CONFLICT

### `emcip-knowledge-engine` integration test

- `ResolutionReviewIntegrationTest` (`@IntegrationTest`, `@MockitoBean GraphRepository`):
  - Insert a PENDING `ResolutionFlag` via repository
  - Call `GET /api/resolution-review?status=PENDING` → assert 1 item returned
  - Call `PATCH /api/resolution-review/{id}/dismiss` → assert 204, flag status = DISMISSED
  - Call `PATCH /api/resolution-review/{id}/dismiss` again → assert 409

**Note:** `merge` integration test does not test the real Cypher execution (`GraphRepository` is always `@MockitoBean` since AGE is not in the test image). The graph merge path is covered by unit test stubbing.

---

## Out of Scope

- Bulk merge/dismiss — per-row buttons are sufficient for low-volume flag queues
- Detail modal — all needed info is in the table row
- Concept-types endpoint — client-side distinct values from first page is sufficient
- Re-resolution of already-MERGED/DISMISSED flags — operator can't undo
- Pagination of graph edges during rerouting — DETACH DELETE handles all edges atomically in AGE
