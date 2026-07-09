# Ingestion Pipeline Improvements — Design Spec

## Problem Statement

Document ingestion in the knowledge engine has four pain points:

1. **Model cold-start**: LLM models (embed + extract) run on a local Mac M2 Ultra with 128 GB RAM. Models are not always loaded — the first request after idle triggers a slow model load, causing timeouts and circuit breaker trips.

2. **Slow ingestion**: Chunks are processed sequentially. Each chunk makes 2+ LLM calls (1 embed + 1 extract + N entity embeds). For a 25-chunk document, that is ~125 serial HTTP round-trips, taking minutes.

3. **Blocking UI**: The Add Document dialog shows a "Processing..." spinner and disables the close button during polling, trapping the user. They cannot queue multiple documents or do other work.

4. **Silent errors**: There is no global notification system. API errors are either shown as inline banners (duplicated CSS per page) or silently swallowed. Users have no visibility into background job outcomes.

## Constraints

- Models run on local hardware (Mac M2 Ultra, 128 GB). Cannot keep all models loaded simultaneously. Warm-up must be on-demand, not scheduled.
- LiteLLM proxy sits between the orchestrator and models. OpenAI-compatible API.
- Admin-api is WebFlux (reactive). Knowledge-engine and orchestrator are JPA/blocking.
- Existing circuit breakers: `llm-orchestrator-embed` (strict) and `llm-orchestrator-analyse` (lenient: 180s slow-call, 70% failure-rate).
- Frontend follows EMCIP v2 design system: brass-and-ink, semantic tokens, no emoji, no rounded corners on data surfaces, Cinzel display font.

## Architecture Overview

```
Admin UI                    Admin API (WebFlux)         Orchestrator          Knowledge Engine
  |                              |                          |                      |
  |-- open Add Document -------->|                          |                      |
  |                              |-- POST /warm-up -------->|                      |
  |                              |                          |-- tiny embed ------->| LiteLLM
  |                              |                          |-- tiny extract ----->| LiteLLM
  |                              |<-- ready/latency --------|                      |
  |<-- "Models ready" -----------|                          |                      |
  |                              |                          |                      |
  |-- submit document ---------->|-- POST /ingest/upload -->|                      |
  |<-- jobId + toast "submitted" |                          |-- processChunks --->|
  |                              |                          |   (3 parallel)       |
  |   (polls jobs table)         |                          |   per chunk:         |
  |                              |                          |     embed(chunk)     |
  |                              |                          |     extract(chunk)   |
  |                              |                          |     embedBatch(entities)
  |                              |                          |                      |
  |<-- toast "complete/failed" --|                          |                      |
```

---

## Section 1: Model Warm-Up Endpoint

### Orchestrator

**New endpoint:** `POST /api/warm-up`

Request:
```json
{ "taskTypes": ["EMBED", "EXTRACT"] }
```

For each task type:
1. Resolve the configured model via `orchestratorService.selectModelForTask(taskType)`.
2. Send a minimal inference request:
   - EMBED: embed the string `"ping"` via `llmClient.embed(model, "ping")`.
   - EXTRACT/other analyse tasks: call the orchestrator's internal `llmClient.analyse(model, "ping")` directly (not via `/api/analyse` — warm-up is internal, not a full pipeline call).
3. Record latency and success/failure.

Response:
```json
{
  "results": {
    "EMBED": { "ready": true, "model": "bge-m3", "latencyMs": 2340 },
    "EXTRACT": { "ready": true, "model": "qwen3-14b", "latencyMs": 4120 }
  }
}
```

If a model fails to warm up, `ready: false` with an `error` field. The endpoint does not throw — it always returns 200 with per-task-type status.

Circuit breakers are bypassed for warm-up calls (they are health probes, not production traffic — a cold-start timeout should not trip the breaker).

### Admin API

**New proxy endpoint:** `POST /api/admin/ai/warm-up`

Proxies to orchestrator's `/api/warm-up`. Same request/response shape. Uses the existing `orchestrator` WebClient bean. No circuit breaker wrapping (warm-up is itself a health check).

---

## Section 2: Batch Embed Endpoint

### Orchestrator

**New endpoint:** `POST /api/embed/batch`

Request:
```json
{ "inputs": ["text one", "text two", "text three"] }
```

1. Resolve EMBED model via `selectModelForTask("EMBED")`.
2. Call LiteLLM's `/v1/embeddings` with the array of inputs (OpenAI embeddings API natively supports arrays).
3. Return ordered results.

Response:
```json
{
  "success": true,
  "embeddings": [[0.1, 0.2, ...], [0.3, 0.4, ...]],
  "model": "bge-m3"
}
```

Max batch size: 32 inputs. Requests exceeding this get a 400.

Uses the `llm-orchestrator-embed` circuit breaker (same as single embed).

### OpenAiCompatibleLlmClient

New method `embedBatch(String modelName, List<String> inputs)` that sends the array to `/v1/embeddings`. The existing `embed(String modelName, String input)` stays unchanged.

### Knowledge Engine — LlmOrchestratorClient

New method `embedBatch(List<String> texts)` that calls `POST /api/embed/batch` on the orchestrator. Returns `List<float[]>`. Wrapped in the `llm-orchestrator-embed` circuit breaker.

---

## Section 3: Parallel Chunk Processing

### DocumentIngestionService

`processChunks()` changes from a sequential for-loop to bounded parallel execution:

1. A `Semaphore` gates concurrency. Default: 3 concurrent chunks.
2. Configurable via `knowledge.ingestion.parallelism: 3` in `application.yml`.
3. Chunks are submitted to the existing `INGESTION_EXECUTOR` (virtual thread executor).
4. Each chunk runs `KnowledgeExtractionService.processDocument()` independently.
5. After all chunks complete, the job status is updated. If any chunk throws, the job is marked FAILED with the first error message.

### KnowledgeExtractionService.processDocument()

Entity embedding is batched: after the extract call returns entities, collect all novel entity labels (not yet in `graph_node_embeddings`), embed them in one `embedBatch()` call, then proceed with entity resolution using the pre-fetched embeddings.

Flow per chunk:
1. Store `KnowledgeDocument` + single `embed(chunk)` call for vector search.
2. Single `extract(chunk)` call for entities/relationships.
3. Filter valid entities/relationships.
4. Collect novel entity labels → single `embedBatch(labels)` call.
5. Entity resolution + graph storage (using pre-fetched embeddings, no additional LLM calls).

This reduces per-chunk LLM calls from `2 + N` to exactly `3` (embed + extract + one batch embed), regardless of entity count.

### EntityResolutionService

Add an overload `resolve(String label, String conceptType, UUID tenantId, float[] precomputedEmbedding)` that skips the `llmClient.embed()` call and uses the provided embedding directly. The existing `resolve()` method (without embedding parameter) stays for the chat message processing path.

---

## Section 4: Toast Notification System (Frontend)

### Components

**`ToastProvider`** — React context provider, wraps the app in `App.jsx`.
- Manages a queue of toast objects: `{ id, type, title, message, duration }`.
- Renders a fixed container bottom-right (`position: fixed; bottom: var(--sp-5); right: var(--sp-5); z-index: 9999`).
- Toasts stack upward. Max 5 visible; older toasts auto-dismissed.

**`Toast`** — individual toast component.
- Types map to signal tokens: `success` → `--signal-ok-*`, `error` → `--signal-stop-*`, `info` → `--signal-info-*`, `warning` → `--signal-warn-*`.
- Layout: type label (Cinzel uppercase 10px, e.g. "ERROR") + message body (Inter 13px) + `✕` close button.
- No rounded corners. 1px brass border. `backdrop-filter: blur(16px)`. `background: var(--bg-card)`.
- Auto-dismiss: 5 seconds default, 8 seconds for errors. Configurable per toast.
- Fade-in 150ms on mount.

**`useToast`** hook — returns `{ addToast(type, message, options?) }`.

### File Structure

```
src/components/Toast/
  ToastProvider.jsx
  Toast.jsx
  Toast.module.css
  useToast.js
```

### Integration

- `App.jsx`: wrap with `<ToastProvider>`.
- No changes to existing pages in this spec. The toast system is consumed only by the ingestion flow. Migrating existing inline error banners to toasts is a separate effort (noted in POSSIBLE_DEVELOPMENT.md).

---

## Section 5: Background Ingestion UX

### Add Document Dialog (IngestionModal.jsx)

**On open:**
1. Fire `POST /api/admin/ai/warm-up` with `{ taskTypes: ["EMBED", "EXTRACT"] }`.
2. Show a status line below the form: "Preparing models..." with a small spinner.
3. Form fields are visible and editable. Submit button is disabled until warm-up completes or times out (15 second client-side timeout).
4. On warm-up success: status line shows "Models ready" (green) with latency, then fades after 3 seconds. Submit enabled.
5. On warm-up failure: fire a warning toast ("Model warm-up failed — ingestion may be slow"), enable Submit anyway (models may already be loaded from previous use).

**On submit:**
1. Call the ingest API, receive jobId.
2. Fire `addToast('info', 'Document submitted — tracking in Ingestion Jobs')`.
3. Close the dialog immediately.
4. Call `onJobCreated()` to refresh the jobs table.

**Close button:** always enabled, regardless of warm-up or submission state.

### Ingestion Jobs Table (KnowledgePage.jsx)

**Auto-polling:**
1. When the Ingestion Jobs tab is active and any visible job has status QUEUED or RUNNING, poll every 5 seconds.
2. When a job transitions to COMPLETED: fire `addToast('success', 'Ingestion complete: {filename} — {n} chunks')`.
3. When a job transitions to FAILED: fire `addToast('error', 'Ingestion failed: {filename} — {errorMessage}')`.
4. Stop polling when all visible jobs are terminal.

**Note:** Polling only runs while the Knowledge page is mounted. Completion notifications for jobs that finish while the user is on another page require WebSocket/SSE push — see POSSIBLE_DEVELOPMENT.md.

---

## Out of Scope

- Migrating existing inline error banners to the toast system (separate cleanup task)
- WebSocket/SSE for cross-page job completion notifications
- Warm-up on AI Config page (can be added later using the same endpoint)
- Scheduled/periodic model keep-alive pings
- Embed endpoint streaming or progress callbacks

## Global Constraints

- All Spring Boot services use Lombok `@Slf4j`, `@RequiredArgsConstructor`.
- Liquibase for any schema changes (none expected in this spec).
- `mvn spotless:apply` before every commit.
- Frontend uses CSS Modules, semantic tokens from `variables.css`, no emoji, no icon libraries.
- Cron timing: never schedule at exact round times — always use offset seconds/millis. (Not directly applicable here but listed as project-wide constraint.)
