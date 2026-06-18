# US-26.8 — Document Ingestion (Factual Knowledge)

**Date**: 2026-06-18
**Status**: Approved
**Backlog item**: #26.8
**Size**: M

---

## 1. Overview

Allows admins to submit URLs or upload documents (plain text, HTML, PDF, DOCX) into the knowledge base. Each submission is processed asynchronously: the document is parsed, chunked, embedded, and entity-extracted into the knowledge graph. A job ID is returned immediately; the admin-UI polls for status. Ingested knowledge can be scoped to a specific tenant or marked global (`tenant_id = null`).

---

## 2. Architecture & Data Flow

```
Admin-UI (Knowledge page)
    │  POST /api/admin/knowledge/ingest/url    (url, tenantId?)
    │  POST /api/admin/knowledge/ingest/upload (multipart: file, tenantId?)
    ▼
Admin-API: DocumentIngestionProxyController
    │  WebClient + @CircuitBreaker("knowledgeEngine")
    ▼
Knowledge-Engine: DocumentIngestionController
    │  creates IngestionJob row (status=QUEUED)
    │  returns { jobId }
    │  submits VirtualThread task
    ▼
DocumentIngestionService (async)
    ├─ Apache Tika: parse URL or uploaded file → plain text
    ├─ chunkText(500 words, 50-word overlap) → List<String>
    ├─ per chunk:
    │   ├─ llmClient.embed(chunk) → store KnowledgeDocument
    │   └─ knowledgeExtractionService.processChunk(chunk, sourceRef, tenantId)
    └─ update IngestionJob: status=COMPLETED, chunk_count=N
       (or status=FAILED, error_message on exception)

Admin-UI polls:  GET /api/admin/knowledge/ingest/{jobId}
Admin-UI lists:  GET /api/admin/knowledge/ingest?page=0&size=20&tenantId=...
```

---

## 3. Database

### 3.1 New table: `ke_ingestion_jobs`

Liquibase migration: `010-ingestion-jobs.xml`

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | UUID | PK, not null |
| `tenant_id` | UUID | nullable — null = global |
| `source_type` | VARCHAR(20) | not null — `URL` or `FILE_UPLOAD` |
| `source_ref` | TEXT | not null — URL string or original filename |
| `status` | VARCHAR(20) | not null — `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED` |
| `chunk_count` | INT | nullable — populated on completion |
| `error_message` | TEXT | nullable |
| `created_at` | TIMESTAMPTZ | not null, default now() |

### 3.2 Existing table: `ke_knowledge_documents`

No schema changes. The `tenant_id` column is already nullable; global documents are stored with `tenant_id = null`.

---

## 4. Knowledge-Engine

### 4.1 New files

**`IngestionJob.java`** — JPA entity mapping `ke_ingestion_jobs`. Fields match table above. Enum `IngestionStatus { QUEUED, RUNNING, COMPLETED, FAILED }`, enum `SourceType { URL, FILE_UPLOAD }`.

> **Note on `KnowledgeExtractionService`**: the existing `processMessage()` method carries chat-specific parameters (`chatId`, `senderId`, `senderDisplayName`, etc.) that do not apply to document ingestion. Add a new overload:
> ```java
> public void processDocument(String chunk, String sourceRef, UUID tenantId)
> ```
> This delegates to the same extraction + graph-write logic, passing `null` for all chat-specific fields. `DocumentIngestionService` calls this overload; `BackfillService` continues to call `processMessage()`.

**`IngestionJobRepository.java`** — Spring Data JPA.
```java
Page<IngestionJob> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
Page<IngestionJob> findAllByTenantIdIsNullOrderByCreatedAtDesc(Pageable pageable);
Page<IngestionJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
```

### 4.2 Modified: `DocumentIngestionController.java`

New endpoints (existing controller extended):

| Method | Path | Body / Params | Response |
|--------|------|---------------|----------|
| `POST` | `/api/knowledge/ingest/url` | `{ url, tenantId? }` JSON | `{ jobId }` 202 |
| `POST` | `/api/knowledge/ingest/upload` | multipart: `file`, `tenantId?` | `{ jobId }` 202 |
| `GET` | `/api/knowledge/ingest/{jobId}` | — | `IngestionJobDto` |
| `GET` | `/api/knowledge/ingest` | `?page&size&tenantId` | `Page<IngestionJobDto>` |

### 4.3 Modified: `DocumentIngestionService.java`

Replace current `HttpClient` + regex `stripHtml()` with **Apache Tika** (`AutoDetectParser`) for both URL fetching and file parsing. Tika handles HTML, plain text, PDF, and DOCX transparently.

Async execution flow:
1. Create and save `IngestionJob` (status=QUEUED)
2. Submit `VirtualThread` task (same executor pattern as `BackfillService`)
3. In thread: update status → RUNNING
4. Tika-parse content to plain text
5. `chunkText()` → `List<String>`
6. For each chunk:
   - `llmClient.embed(chunk)` → save `KnowledgeDocument`
   - `knowledgeExtractionService.processDocument(chunk, sourceRef, tenantId)`
7. Update job: status=COMPLETED, chunk_count=N
8. On any exception: update job: status=FAILED, error_message=ex.getMessage()

### 4.4 New Tika dependency (`pom.xml`)

```xml
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>2.9.2</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>2.9.2</version>
</dependency>
```

---

## 5. Admin-API

### 5.1 New: `DocumentIngestionProxyController.java`

Pure proxy — no business logic. Mirrors `BackfillProxyController` exactly.

Forwards all four endpoints from §4.2, prefixed `/api/admin/knowledge/ingest/...`. Uses existing `WebClient` bean for knowledge-engine + `@CircuitBreaker("knowledgeEngine")`.

Multipart file forwarding: read `MultipartFile` bytes, rebuild as `MultiValueMap<String, HttpEntity<?>>` for WebClient.

---

## 6. Admin-UI

### 6.1 New page: `KnowledgePage.jsx`

Route: `/knowledge`
Sidebar entry: "Knowledge" (position: after "Groups", before any settings)

Layout:
- Page header: "Knowledge Base" + "Add Document" button (right-aligned)
- `DataTable` with columns: Type (URL/FILE icon), Source, Tenant (name or "Global"), Status (`Badge`), Chunks, Created At
- Pagination controls
- Clicking "Add Document" opens `IngestionModal`

### 6.2 New component: `IngestionModal.jsx`

Four phases:

| Phase | Content |
|-------|---------|
| `config` | `SegmentedControl`: URL / File · URL: text input · File: file picker (accepts .txt, .html, .pdf, .docx) · Tenant: dropdown (all tenants + "Global") · "Submit" button |
| `polling` | Spinner + "Processing…" + source ref · polls `GET /ingest/{jobId}` every 2 s |
| `done` | Status badge (COMPLETED) + chunk count + "Close" button |
| `error` | Error message + "Retry" button (returns to `config`) |

### 6.3 New API hooks

```js
api.ingestUrl(url, tenantId)        // POST /api/admin/knowledge/ingest/url
api.ingestUpload(file, tenantId)    // POST /api/admin/knowledge/ingest/upload  (FormData)
api.ingestionStatus(jobId)          // GET  /api/admin/knowledge/ingest/{jobId}
api.ingestionJobs(page, tenantId)   // GET  /api/admin/knowledge/ingest
```

---

## 7. Testing

### 7.1 Knowledge-engine integration test: `DocumentIngestionControllerTest.java`

Testcontainers (PostgreSQL + pgvector + Apache AGE + WireMock for llm-orchestrator):

- `POST /ingest/url` with a real (or WireMocked) URL → assert 202, poll until COMPLETED, assert `ke_knowledge_documents` rows exist, assert job `chunk_count > 0`
- `POST /ingest/upload` with a small PDF → same assertions
- `POST /ingest/upload` with a DOCX → same assertions
- Tenant-scoped ingestion (`tenantId` set) → assert `ke_knowledge_documents.tenant_id` matches
- Global ingestion (`tenantId` absent) → assert `ke_knowledge_documents.tenant_id` is null
- Bad URL (unreachable) → assert job status = FAILED, `error_message` non-null

### 7.2 Knowledge-engine unit test: `DocumentIngestionServiceTest.java`

- Mock Tika, mock `LlmOrchestratorClient`, mock `KnowledgeExtractionService`
- Verify `embed()` called once per chunk, `processChunk()` called once per chunk
- Verify `IngestionJob` updated to COMPLETED with correct `chunk_count`
- Verify `IngestionJob` updated to FAILED when `embed()` throws

### 7.3 Admin-API slice test: `DocumentIngestionProxyControllerTest.java`

- WireMock knowledge-engine: assert URL and multipart forwarding correct
- Circuit breaker trips on knowledge-engine 500 → assert 503 returned to caller

### 7.4 Admin-UI

- `IngestionModal`: renders all four phases; polling starts after submit; `done` phase shows chunk count; `error` phase shows retry
- `KnowledgePage`: renders DataTable with mocked job list; "Add Document" opens modal

---

## 8. Out of Scope

- Duplicate URL detection (submit same URL twice → creates two jobs, second overwrites chunks)
- Manual re-ingestion / retry of FAILED jobs from UI (retry resubmits via "Retry" button only in same session)
- Ingestion scheduling / periodic re-fetch of URLs (covered by #42)
- OCR for scanned PDFs (Tika text-only; image-only PDFs will produce empty content → FAILED)
