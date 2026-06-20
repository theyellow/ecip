# Epic 42 — Knowledge Enrichment Connectors

**Date:** 2026-06-19
**Status:** Approved for implementation
**Dependencies:** #26.8 (Document ingestion — complete), #26.9 (Knowledge Query API — in progress)

---

## Overview

Epic 42 adds structured knowledge enrichment to the EMCIP knowledge base. The system periodically (and on demand) fetches content from 13 external knowledge sources, ingests it into the existing `knowledge_passages` / Apache AGE graph, and exposes API key management through the admin UI — scoped per vendor and per tenant.

This is distinct from Epic 27 (Deep Research Agent), which covers live, on-demand web search during autonomous research sessions.

**Scope:**
1. 13 knowledge connector implementations (search APIs, academic databases, structured knowledge graphs)
2. API key registry — global defaults with per-tenant override
3. Enrichment pipeline — three trigger modes feeding one shared ingestion path
4. Admin UI — `INTEGRATIONS` page with role-split views

**Out of scope:** Live search tool for LLM processing (Epic 27), connector plugin framework (connectors are direct implementations), full-text encryption of stored keys (deferred to follow-up).

---

## Architecture

### Approach: Extend `knowledge-engine`

All connector logic, scheduling, and key resolution live inside `knowledge-engine`. `admin-api` exposes REST endpoints for key and source configuration (CRUD only — no business logic). Both services share the PostgreSQL database directly.

```
Admin UI
  └── admin-api          ← CRUD: vendor keys, enrichment sources, trigger REST
  └── knowledge-engine   ← Connectors, scheduler, Kafka listener, ingestion pipeline
        └── PostgreSQL   ← vendor_api_keys, enrichment_sources, enrichment_runs
                           + existing knowledge_passages, knowledge_entities (AGE)
```

No new microservice is introduced.

---

## Connectors (v1 — all 13)

| Vendor | Type | Key Required | Notes |
|---|---|---|---|
| **Wikipedia** | Encyclopedia / background knowledge | None | MediaWiki REST v1 — clean article summaries |
| **arXiv** | Preprints (physics, math, CS, econ) | None | 2M+ papers; 3 req/s limit enforced |
| **PubMed** | Biomedical literature | Optional (free NCBI key) | 36M+ citations; 10 req/s with key |
| **Wikidata** | Structured knowledge graph | None | SPARQL + REST; entity enrichment (author → ORCID, org → ROR) |
| **OpenAlex** | Papers, citations, concepts | None | 250M+ works, CC0; successor to Microsoft Academic Graph |
| **Semantic Scholar** | Papers, citations, TLDR summaries | Optional (free) | 214M+ papers; AI-generated TLDRs; batch endpoints |
| **bioRxiv / medRxiv** | Biology + medicine preprints | None | Tracks when preprints get published; 30 records/call |
| **CORE** | Full-text open-access papers | Required (free key) | Only free source with actual full text from institutional repos |
| **Zenodo** | Papers, datasets, software (CERN) | None | 3M+ research objects; download links included |
| **Unpaywall** | OA availability lookup by DOI | None (email param) | 100k req/day; lookup-only (needs DOI as `externalId`); participates in topic-driven trigger only when entity carries a DOI |
| **DOAJ** | Open-access journal directory | None | 20k+ OA journals; used to verify journal legitimacy |
| **Exa Search** | Neural web search | Required (paid) | 1,000 req/month free tier; finds papers not in academic DBs |
| **Brave Search** | Independent web search + news | Required (paid) | ~1k req/month free; independent index, no Google/Bing wrapper |

OceanOfPapers, Daily Academic, and ResearchPod have no public APIs — they are consumer aggregators built on the same underlying sources listed above.

---

## Data Model

### New DB Tables (Liquibase migrations)

#### `vendor_api_keys`

```sql
CREATE TABLE vendor_api_keys (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    vendor_id   VARCHAR(64)  NOT NULL,
    tenant_id   UUID,                          -- NULL = global default
    api_key     VARCHAR(512) NOT NULL,          -- plain text; encryption-ready column
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_vendor_api_keys PRIMARY KEY (id),
    CONSTRAINT uq_vendor_tenant    UNIQUE NULLS NOT DISTINCT (vendor_id, tenant_id)
);
```

`tenant_id IS NULL` means the key is the global fallback for all tenants. One row per vendor per tenant (enforced by unique constraint). `NULLS NOT DISTINCT` (PostgreSQL 15+) ensures that two global rows for the same vendor (`tenant_id IS NULL`) are correctly rejected.

The `api_key` column stores the value as plain text in v1. The column is sized and named to support AES-256 encryption in a follow-up — no schema change will be required, only a value transformation and an `encrypted BOOLEAN` flag column addition.

#### `enrichment_sources`

```sql
CREATE TABLE enrichment_sources (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    vendor_id        VARCHAR(64)  NOT NULL,
    tenant_id        UUID,                      -- NULL = applies globally
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    schedule_cron    VARCHAR(64),               -- Spring cron (6 fields); NULL = no schedule
    last_run_at      TIMESTAMPTZ,
    last_run_status  VARCHAR(16),               -- SUCCESS | PARTIAL | FAILURE | RUNNING
    config           JSONB        NOT NULL DEFAULT '{}',  -- vendor-specific params
    version          BIGINT       NOT NULL DEFAULT 0,     -- @Version optimistic lock
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_enrichment_sources PRIMARY KEY (id)
);
```

`config` JSONB holds vendor-specific parameters: search terms, category filters, date range offsets, etc. Schema is per-vendor and documented in each connector implementation.

Cron expressions must never use exact round times (`:00`). `EnrichmentSourceService` generates a random offset on first save: e.g. `"0 17 3 * * *"` not `"0 0 3 * * *"`.

#### `enrichment_runs`

```sql
CREATE TABLE enrichment_runs (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    source_id       UUID         NOT NULL REFERENCES enrichment_sources(id),
    trigger_type    VARCHAR(16)  NOT NULL,      -- SCHEDULED | TOPIC_DRIVEN | MANUAL
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    status          VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',  -- RUNNING | SUCCESS | PARTIAL | FAILURE
    items_fetched   INT          NOT NULL DEFAULT 0,
    items_ingested  INT          NOT NULL DEFAULT 0,
    error_message   VARCHAR(1024),
    CONSTRAINT pk_enrichment_runs PRIMARY KEY (id)
);
```

---

## Connector Interface (`knowledge-engine`)

```java
// All connectors implement this interface.
public interface KnowledgeConnector {
    String vendorId();         // matches vendor_id in DB — "wikipedia", "arxiv", "exa" …
    String displayName();
    boolean requiresApiKey();

    // Called by all three trigger types. Context carries the resolved API key,
    // tenant, and a since-timestamp for incremental fetches.
    Flux<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx);
}

public record ConnectorContext(
    @Nullable String  apiKey,      // null when requiresApiKey() == false
    TenantId          tenantId,
    Instant           since        // for incremental / feed-mode connectors
) {}

public record EnrichmentRequest(
    TriggerMode              mode,        // SCHEDULED | TOPIC_DRIVEN | MANUAL
    @Nullable String         query,       // for search-capable connectors
    @Nullable String         externalId,  // for lookup-capable connectors (DOI, PMID …)
    Map<String, String>      params       // vendor-specific extras from source.config
) {}

public record EnrichmentResult(
    String              externalId,
    String              title,
    @Nullable String    content,          // abstract or full text; null if unavailable
    String              url,
    String              sourceVendorId,
    Instant             publishedAt,
    Map<String, Object> metadata          // vendor-specific: authors, doi, categories …
) {}
```

Connectors are Spring beans annotated `@Component`. `EnrichmentConnectorRegistry` collects them via `List<KnowledgeConnector>` injection and provides lookup by `vendorId`.

---

## API Key Resolution

`ApiKeyResolver` is called before every connector fetch. It queries `vendor_api_keys` using R2DBC:

1. Look for tenant-specific key: `vendor_id = X AND tenant_id = currentTenant`
2. Fall back to global: `vendor_id = X AND tenant_id IS NULL`
3. If `requiresApiKey() == false` → skip resolver, pass `null`
4. If key required but none found → log `WARN`, skip connector for this run (does not fail the run)

The raw key value is never returned in any REST response. All GET endpoints return the key masked to the last 4 characters: `"••••••••••••7f3a"`.

---

## Enrichment Pipeline

### Three Trigger Types

**Trigger 1 — Scheduled (cron)**

A single `@Scheduled` method fires at second `:17` of every minute (itself offset to avoid round times). It loads all enabled `enrichment_sources` where `schedule_cron IS NOT NULL` and checks each using `CronExpression.parse(source.scheduleCron()).next(ZonedDateTime.now().minusMinutes(1))`. If the next-due time falls within the last minute, the source is dispatched.

**Trigger 2 — Topic-driven (Kafka)**

Listens on `knowledge.entity.created`. On each event, all enabled sources for the tenant (or global sources when `tenant_id IS NULL`) are queried and fetched with the entity name/concept as the search query. Only connectors that support text search participate (Wikipedia, arXiv, PubMed, Exa, Brave, OpenAlex, Semantic Scholar, bioRxiv, CORE, Zenodo, DOAJ).

**Trigger 3 — Manual (REST)**

```
POST /api/v1/admin/integrations/sources/{id}/trigger   (admin-api)
→ 202 Accepted
→ { "runId": "uuid" }

GET  /api/v1/admin/integrations/sources/{id}/runs/{runId}  (admin-api)
→ { "status": "RUNNING|SUCCESS|PARTIAL|FAILURE", "itemsFetched": N, "itemsIngested": N }
```

`admin-api` handles the trigger request: it creates an `enrichment_runs` row with `status = RUNNING` and publishes a `knowledge.enrichment.trigger` Kafka event carrying `{ sourceId, runId }`. `knowledge-engine` consumes the event, executes the fetch, and updates the run row on completion. The polling GET reads directly from `enrichment_runs`.

All three triggers feed the same shared ingestion path.

### Shared Ingestion Path (6 stages)

```
1. Resolve key   ApiKeyResolver → Optional<String> apiKey
2. Fetch         connector.fetch() → Flux<EnrichmentResult>  (with backpressure)
3. Deduplicate   check existing by (externalId, sourceVendorId); skip if present
4. Embed         generate vector embedding for title + content (existing embedding model)
5. Store         upsert into knowledge_passages; add node/edge to Apache AGE if entity ref detected
6. Audit         update enrichment_runs: items_fetched++, items_ingested++, completed_at, status
```

Per-item errors use `onErrorContinue` so one failed result does not abort the stream. Run status is `SUCCESS` (zero errors), `PARTIAL` (some errors), or `FAILURE` (connector itself threw — auth failure, network error, etc.).

---

## REST API (`admin-api`)

### Vendor Keys

```
GET    /api/v1/admin/integrations/keys              → list all (masked); query param: ?tenantId=
POST   /api/v1/admin/integrations/keys              → create key
PUT    /api/v1/admin/integrations/keys/{id}         → update key or toggle enabled
DELETE /api/v1/admin/integrations/keys/{id}         → remove key
```

Tenant admin endpoints (scoped to own tenant):
```
GET    /api/v1/tenant/integrations/keys             → list own keys (masked)
PUT    /api/v1/tenant/integrations/keys/{vendorId}  → upsert own key for vendor
DELETE /api/v1/tenant/integrations/keys/{vendorId}  → remove own key (fall back to global)
```

### Enrichment Sources

```
GET    /api/v1/admin/integrations/sources           → list sources with last_run_at, status
PUT    /api/v1/admin/integrations/sources/{id}      → update enabled, schedule_cron, config
POST   /api/v1/admin/integrations/sources/{id}/trigger  → manual run → 202 + runId
GET    /api/v1/admin/integrations/sources/{id}/runs → run history (paginated)
GET    /api/v1/admin/integrations/sources/{id}/runs/{runId} → single run status
```

---

## Permissions

Two new permission constants added to `permissions.js` and the backend `Permission` enum:

| Constant | Role | Grants |
|---|---|---|
| `INTEGRATIONS_GLOBAL_MANAGE` | `ADMIN` | Global keys CRUD, source config, schedule, manual trigger, full run history |
| `INTEGRATIONS_TENANT_MANAGE` | `ADMIN` + `TENANT_ADMIN` | Own tenant keys CRUD only |

---

## Admin UI — Integrations Page

New sidebar entry: glyph `⊕` (U+2295), label `INTEGRATIONS`, permission guard `INTEGRATIONS_TENANT_MANAGE` (both roles can see the page).

### ADMIN view (3 tabs)

**Global Keys** — DataTable of all 13 vendors. Vendors with no key requirement show "No key required" in italic. Paid vendors (Exa, Brave, CORE) show masked key + Edit/Disable actions. Free vendors show Disable only.

**Sources & Schedule** — Card grid, one card per source. Shows: vendor name, cron expression, last run timestamp, last run status badge (Success/Partial/Failure), fetched/ingested counts, Configure button, Run Now button.

**Run History** — DataTable of `enrichment_runs` across all sources. Columns: vendor, trigger type, started at, duration, status, fetched, ingested, error (truncated).

### TENANT_ADMIN view (1 tab)

**My API Keys** — DataTable of all 13 vendors. For each: vendor name, own key (masked if set, "Not set" if absent), fallback badge ("Own key" or "Using global"), Set Key / Edit / Delete actions. Free vendors (no key needed) shown as read-only rows with "No key needed" note. No schedule visibility, no run history.

---

## Error Handling

| Scenario | Behaviour |
|---|---|
| Connector API returns 429 | Connector logs WARN, returns empty `Flux`; run status → PARTIAL |
| Connector API returns 401/403 | Logs WARN with vendor name (not key value); run status → FAILURE |
| Network timeout | `timeout(Duration)` per connector; logs WARN; run status → FAILURE |
| Per-item parse error | `onErrorContinue`; item skipped; error count incremented |
| Embedding service unavailable | Stage 4 throws; run status → FAILURE; items already stored are kept |
| Duplicate detected | Silently skipped; not counted in `items_ingested` |

---

## Testing

- Unit tests for each `KnowledgeConnector` implementation using `MockWebServer` (OkHttp) to stub external APIs
- Unit tests for `ApiKeyResolver` covering: tenant key found, global fallback, key required + not found
- Integration tests (Testcontainers) for the ingestion pipeline end-to-end: trigger → DB → knowledge_passages
- Integration tests for all `admin-api` REST endpoints: auth, masking, tenant isolation
- No mocking of PostgreSQL in integration tests

---

## Liquibase Migration Order

1. `V42.1__create_vendor_api_keys.sql`
2. `V42.2__create_enrichment_sources.sql`
3. `V42.3__create_enrichment_runs.sql`
4. `V42.4__seed_enrichment_sources.sql` — inserts one global `enrichment_sources` row per vendor with default cron offsets and empty `config`

---

## Open Questions (resolved)

| Question | Decision |
|---|---|
| New service or extend existing? | Extend `knowledge-engine` (Option B) |
| Key storage | Plain text in DB v1; column sized for AES-256 encryption later |
| Key scoping | Global default + per-tenant override (C) |
| Trigger types | All three: scheduled + topic-driven + manual |
| Connector list v1 | 13 connectors (all selected above) |
| Dynamic scheduling | Single `@Scheduled` master job at `:17`, checks `CronExpression` per source |
| Admin visibility | ADMIN: global keys + schedule + history; TENANT_ADMIN: own keys only |
