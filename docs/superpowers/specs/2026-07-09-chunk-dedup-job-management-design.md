# Chunk Deduplication & Ingestion Job Management

## Goal

Prevent duplicate document ingestion (same URL/file or identical content), add job lifecycle management (detail view, delete with cascade, re-ingest), and link chunks to the jobs that created them via a proper foreign key.

## Architecture

Job-centric model: add `job_id` FK on `ke_knowledge_documents` and `content_hash` on `ke_ingestion_jobs`. Dedup is two-layer (sourceRef pre-extraction, content hash post-extraction). Delete cascades from job → chunks → edges, leaving graph nodes intact. Detail view traverses job → chunks → edges → nodes.

## Tech Stack

Java 21, Spring Boot 4, JPA/Hibernate, PostgreSQL (pgvector), Liquibase, React (Vite), CSS Modules.

## Global Constraints

- Liquibase only for schema changes (never Flyway)
- Spotless formatting before every commit
- Lombok `@Slf4j`, `@RequiredArgsConstructor` — no manual getters
- UUID primary keys, `@Column(nullable = false)` where applicable
- Admin-UI: semantic tokens only, no emoji, no rounded corners on data surfaces, Cinzel display font for headings/labels
- Admin-API proxies all knowledge-engine endpoints through circuit breaker with appropriate permissions (KNOWLEDGE_READ / KNOWLEDGE_WRITE)

---

## 1. Data Model Changes

### 1.1 New column: `ke_ingestion_jobs.content_hash`

- Type: `VARCHAR(64)`, nullable
- SHA-256 hex digest of the full extracted text (before chunking)
- Set after Tika extraction, before chunk processing
- NULL for: legacy jobs, jobs that failed before extraction, FLAGGED_INJECTION_RISK jobs
- Index: `idx_ke_jobs_content_hash` on `content_hash` — needed for hash dedup query

### 1.2 New column: `ke_knowledge_documents.job_id`

- Type: UUID, nullable, FK → `ke_ingestion_jobs(id)`
- Nullable because legacy rows predate the FK
- All new rows will have `job_id` set
- Index: `idx_ke_docs_job_id` on `job_id`

### 1.3 Backfill migration

SQL update that matches existing documents to jobs by `source_ref` and `tenant_id`:
- Tenant matching uses `(d.tenant_id = j.tenant_id OR (d.tenant_id IS NULL AND j.tenant_id IS NULL))` to handle NULL correctly
- For documents matching multiple COMPLETED jobs, link to the most recent one (by `created_at`)
- Documents with no matching job keep `job_id = NULL`
- This is a data-only migration — no application code change needed for backfill

---

## 2. Dedup Flow

### 2.1 Layer 1: sourceRef check (pre-extraction, instant)

When a user submits a URL or filename, before any processing:
- Query `ke_ingestion_jobs` for a COMPLETED job with matching `source_ref` and `tenant_id` (both NULL tenant values treated as matching)
- If found: return 409 Conflict with body `{ "error": "DUPLICATE_SOURCE", "existingJobId": "<UUID>", "message": "Already ingested: {sourceRef}. Use re-ingest to update." }`
- Frontend displays this as an info toast

### 2.2 Layer 2: content hash check (post-extraction, pre-chunking)

After Tika extraction, before chunking:
- Compute SHA-256 of full extracted text
- Store hash on the current job record
- Query `ke_ingestion_jobs` for a COMPLETED job with matching `content_hash` and `tenant_id` (excluding the current job)
- If found: mark current job as COMPLETED with `chunkCount = 0` and `errorMessage = "Duplicate content (matches job {existingJobId}, source: {existingSourceRef})"`
- No chunks created, but the job record stays visible for audit

### 2.3 Re-ingest flow

New endpoint: `POST /api/knowledge/ingest/{jobId}/reingest`

Steps:
1. Load original job — read `source_ref`, `source_type`, `tenant_id`
2. For `source_type = FILE_UPLOAD`: return 400 immediately with `{ "error": "REUPLOAD_REQUIRED", "sourceRef": "{filename}" }` — no data deleted; frontend opens the ingestion modal pre-filled with the filename so the user can re-upload
3. For `source_type = URL`: delete old chunks where `job_id` matches the original job, delete graph edges where `document_id` IN those deleted chunk IDs, then create a new job record (new UUID, status QUEUED) with same source details and re-fetch/process

The old job record stays in history; its chunks are deleted but the record shows it existed. When re-uploading a file, the user submits via the normal ingestion flow — the sourceRef dedup check is bypassed because the frontend passes a `replaceJobId` parameter, which triggers the same delete-then-ingest sequence as the URL path.

---

## 3. Job Delete

### 3.1 Endpoint: `DELETE /api/knowledge/ingest/{jobId}`

Single `@Transactional` operation:
1. Load all `ke_knowledge_documents` where `job_id = {jobId}` — collect their UUIDs
2. Delete `ke_graph_edges` where `document_id` IN those chunk UUIDs
3. Delete the chunks (`ke_knowledge_documents` where `job_id = {jobId}`)
4. Delete the job record (`ke_ingestion_jobs` where `id = {jobId}`)
5. Return 204 No Content

Graph nodes (`ke_graph_nodes`) are never deleted — they are shared resources that may serve other documents via entity resolution merging.

### 3.2 Admin-API proxy

`DELETE /api/admin/knowledge/ingest/{jobId}` → proxied to knowledge-engine with circuit breaker, requires `KNOWLEDGE_WRITE` permission.

---

## 4. Job Detail

### 4.1 Endpoint: `GET /api/knowledge/ingest/{jobId}/details`

Response:
```json
{
  "job": {
    "jobId": "...",
    "sourceType": "URL",
    "sourceRef": "https://example.com/doc.pdf",
    "tenantId": "..." | null,
    "status": "COMPLETED",
    "chunkCount": 25,
    "errorMessage": null,
    "createdAt": "2026-07-09T10:00:00Z",
    "contentHash": "a1b2c3..."
  },
  "chunks": [
    {
      "id": "...",
      "chunkIndex": 0,
      "contentPreview": "First 200 characters of chunk text...",
      "entityCount": 3,
      "relationshipCount": 2
    }
  ],
  "entities": [
    { "label": "Angela Merkel", "conceptType": "PERSON", "nodeId": "..." }
  ],
  "totalChunks": 25,
  "totalEntities": 14,
  "totalRelationships": 8
}
```

- `chunks`: all chunks for this job, ordered by `chunkIndex`. `contentPreview` truncated to 200 chars server-side.
- `entities`: deduplicated list of graph nodes referenced by this job's edges. Same node from multiple chunks appears once.
- `totalEntities` / `totalRelationships`: aggregate counts across all chunks.

Entity and relationship data derived by joining: chunks (by `job_id`) → edges (by `document_id`) → nodes.

### 4.2 Admin-API proxy

`GET /api/admin/knowledge/ingest/{jobId}/details` → proxied to knowledge-engine with circuit breaker, requires `KNOWLEDGE_READ` permission.

---

## 5. Frontend Changes

### 5.1 Job row actions

Add an `actions` column (rightmost, ~120px) to the ingestion jobs DataTable:

| Button | Glyph | When | Action |
|--------|-------|------|--------|
| View | `▸` | Always | Opens job detail modal |
| Delete | `✕` | Always | Opens ConfirmDialog |
| Re-ingest | `↻` | COMPLETED or FAILED only | URLs: confirm + trigger. Files: open IngestionModal in re-upload mode. |

### 5.2 Job detail modal

Standard `<Modal>` with title = sourceRef (truncated if long).

**`— JOB INFO —`** section:
- sourceRef, sourceType, tenant name or "Global", status Badge, content hash (mono), created date
- For FAILED jobs: full error message in a `signal-stop-bg` tinted block
- For FLAGGED_INJECTION_RISK: warning block explaining content was flagged

**`— CHUNKS ({n}) —`** section:
- Scrollable list of chunk cards
- Each shows: chunk index (mono), content preview (200 chars, `fg-2`), entity count, relationship count as small mono badges
- No pagination — all chunks in one response (capped at 500 by chunker)

**`— ENTITIES ({n}) —`** section:
- Chip-style list of extracted entities
- Each chip: label text + conceptType Badge
- Clicking an entity navigates to the Search tab with that label pre-filled as the query

### 5.3 Delete confirmation

ConfirmDialog text: `"Delete job and its {n} chunks? Graph nodes will be preserved."`

Require typed confirmation for jobs with > 0 chunks. Zero-chunk jobs (failed, duplicate-detected) delete immediately on button click.

### 5.4 Re-ingest UX

- **URL jobs:** ConfirmDialog: `"Re-ingest from {sourceRef}? Old chunks will be replaced."` → on confirm, calls re-ingest endpoint directly
- **File jobs:** ConfirmDialog explains re-upload is needed → on confirm, opens IngestionModal with `sourceRef` pre-filled and "Re-ingest" as submit label. Tenant is pre-selected from the original job.

### 5.5 Dedup feedback

When the modal submit returns 409:
- Parse `existingJobId` from response body
- Show info toast: `"Already ingested: {sourceRef}. Use re-ingest to update."`
- Close the modal (don't leave it open)

### 5.6 FLAGGED_INJECTION_RISK badge

Add to `STATUS_VARIANT` map: `FLAGGED_INJECTION_RISK: 'yellow'`

### 5.7 Frontend API additions

New methods in `knowledgeApi`:
- `jobDetails: (jobId) => request(\`/api/admin/knowledge/ingest/${jobId}/details\`)`
- `deleteJob: (jobId) => request(\`/api/admin/knowledge/ingest/${jobId}\`, { method: 'DELETE' })`
- `reingest: (jobId) => request(\`/api/admin/knowledge/ingest/${jobId}/reingest\`, { method: 'POST' })`

---

## 6. Entity & DTO Changes

### 6.1 IngestionJob entity

Add field:
```java
@Column(name = "content_hash", length = 64)
private String contentHash;
```

### 6.2 KnowledgeDocument entity

Add field:
```java
@Column(name = "job_id")
private UUID jobId;
```

### 6.3 IngestionJobDto

Add `contentHash` field to the existing record.

### 6.4 New DTOs

`IngestionJobDetailDto` — the response shape from Section 4.1.

`ChunkSummaryDto`:
```java
public record ChunkSummaryDto(
    UUID id,
    int chunkIndex,
    String contentPreview,
    int entityCount,
    int relationshipCount
) {}
```

`EntitySummaryDto`:
```java
public record EntitySummaryDto(
    String label,
    String conceptType,
    UUID nodeId
) {}
```

---

## 7. Service Layer

### 7.1 DocumentIngestionService changes

- `submitUrlIngestion` / `submitFileIngestion`: add sourceRef dedup check before creating job. Return 409 response info if duplicate found.
- New private method `computeContentHash(String extractedText)`: SHA-256 hex digest.
- `processUrlAsync` / `processFileAsync`: after Tika extraction, compute and store content hash. Check for hash duplicate before chunking.
- `processChunks`: set `job_id` on each `KnowledgeDocument` before saving.
- New method `deleteJob(UUID jobId)`: transactional cascade delete (chunks, edges, job).
- New method `reingestJob(UUID jobId)`: delete old data, create new job, re-process.
- New method `getJobDetails(UUID jobId)`: join job → chunks → edges → nodes, build `IngestionJobDetailDto`.

### 7.2 KnowledgeExtractionService changes

- `processDocument()` method signature gains a `UUID jobId` parameter.
- Sets `jobId` on the `KnowledgeDocument` before saving.

---

## 8. Testing

- **Dedup unit tests**: verify sourceRef check returns 409, content hash check marks job as duplicate, re-ingest clears old data
- **Delete unit tests**: verify cascade deletes chunks and edges but not nodes
- **Detail endpoint test**: verify response shape with chunks, entities, counts
- **Frontend tests**: detail modal renders sections, delete confirmation works, dedup toast appears on 409
