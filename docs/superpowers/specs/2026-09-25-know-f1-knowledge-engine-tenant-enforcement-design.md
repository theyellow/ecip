# KNOW-F1 — Knowledge-engine tenant enforcement (+ ADR-009)

**Date:** 2026-09-25 · **Backlog:** KNOW-F1, absorbs KNOW-F2 · **Roadmap:** delivers ADR-009 of 3.14 ·
**Status:** approved design (revised after a backlog cross-check)

## 0. Premise correction and scope

KNOW-F1 was filed as "the research and ingestion proxies take a caller-supplied tenant". Auditing every
admin-api proxy to knowledge-engine found the same two defects in **four** areas:

| Area | Caller-chosen tenant (create / list) | Id-addressed calls with no ownership check (IDOR) |
|---|---|---|
| Research | `POST /` (body), `GET /?tenantId` | `GET /{id}`, `POST /{id}/pause`, `/resume`, `GET /{id}/report`, `/report/markdown` |
| Ingestion | `POST /url` (body), `POST /upload?tenantId`, `GET /?tenantId` | `GET /{jobId}`, `/details`, **`DELETE /{jobId}`**, `POST /{jobId}/reingest` |
| Resolution review (not filed before) | `GET /?tenantId` | `PATCH /{id}/merge`, `PATCH /{id}/dismiss` |
| Graph neighbors (was KNOW-F2) | — | `GET /graph/node/{id}/neighbors`, and search's internal `findConnected` expansion |

knowledge-engine looks items up by id and never compares tenants; lists with a null tenant return every
tenant's items; knowledge-engine never registers `TenantContextFilter`. Anyone holding an id can read —
and with write permissions pause, delete, re-ingest, merge or dismiss — another tenant's item.

`BackfillProxyController` already applies the right rule inline (bound tenant wins, `ADMIN` may choose);
it is switched to the shared resolver.

Audited and deliberately left alone: backfill `GET /status?backfillId=` (progress counters only, no
content); `OntologyController` (not proxied by admin-api — reachable only in-cluster, covered by the
NetworkPolicy in 3.20 and ADR-010).

Live data (2026-09-25): 0 research sessions, 2 ingestion jobs (global); knowledge is all global.

**Cross-check against the backlog.** The trust model below is a multi-tenancy decision, so this PR also
writes **ADR-009 (multi-tenancy)** — the first of 3.14's ADRs — recording it together with the P3.8a
search rule and "system prompt templates are global". **No new "knowledge-engine authentication" item:**
the 1.0 mitigation for "no tenant = trusted" is a NetworkPolicy, added to **3.20** (no NetworkPolicy exists
today); whether knowledge-engine should also verify a service token goes to **ADR-010** (auth/authz, 3.14),
together with RT-005 (unauthenticated Kafka) — the same "any pod in the namespace" threat.

## 1. Trust model

knowledge-engine has no authentication of its own; admin-api is the security boundary.

- **A tenant asserted** in the call → knowledge-engine enforces it.
- **No tenant asserted** → the caller is trusted (platform `ADMIN` in admin mode, or an in-cluster
  service) — today's behaviour for every such caller.

The hole closes because admin-api always asserts a tenant-bound caller's tenant (§2).

Deliberate difference from P3.8a search, where "no tenant" means global knowledge only: search results
feed users and LLM prompts, so its default is the narrow one; the areas here are operational admin views
that only a platform `ADMIN` reaches without a tenant.

## 2. admin-api: always assert the caller's tenant

`ResearchProxyController`, `DocumentIngestionProxyController`, `ResolutionReviewProxyController`,
`KnowledgeSearchProxyController.getNeighbors` and `BackfillProxyController` resolve the tenant with
`KnowledgeTenantResolver` (from P3.8a):

| Caller | Asserted tenant |
|---|---|
| Tenant-bound role (JWT tenant) | the JWT tenant — any tenant in the request is overwritten |
| `ADMIN` with `X-Tenant-Id` | the header tenant |
| `ADMIN` in admin mode | the tenant the request names, else none |

Transport: `tenantId` in the JSON body for creates (research `POST /`, ingestion `POST /url`), replacing
any caller value (non-object body → 400); `tenantId` query parameter / multipart part for lists and
upload, replaced the same way; **a new `tenantId` query parameter on every id-addressed call**, omitted
when no tenant is asserted.

**404 must reach the user as 404.** Today every one of these proxies turns *any* knowledge-engine error
into 503 (`onErrorResume` → `SERVICE_UNAVAILABLE`), and the resolution proxy maps none at all. A
knowledge-engine 404 is passed through as 404; other errors keep their current mapping.

## 3. knowledge-engine: enforce when a tenant is asserted

One policy class, `TenantAccess`, used everywhere below:

- `canRead(UUID itemTenant, UUID asserted)` → `asserted == null || itemTenant == null || itemTenant.equals(asserted)`
- `canWrite(UUID itemTenant, UUID asserted)` → `asserted == null || Objects.equals(itemTenant, asserted)`

| Operation | tenant `t` asserted | no tenant |
|---|---|---|
| create | item belongs to `t` | as sent (global if none) |
| list (research, ingestion, resolution flags) | `t`'s items **plus global** | all (unchanged) |
| read by id (session, report, markdown, job, job details) | `canRead`, else **404** | unchanged |
| change by id (pause, resume, delete, reingest, merge, dismiss) | `canWrite`, else **404** | unchanged |
| graph neighbors of node `n` | `n` must pass `canRead` (else 404); returned neighbors filtered by `canRead` | unchanged |

- **404, not 403**, so another tenant's ids reveal nothing; a denied item is indistinguishable from a
  missing one. Ingestion by-id endpoints currently turn a missing job into an `IllegalArgumentException`
  (a 500 — knowledge-engine has no controller advice); missing and denied both become **404**.
- **Global items** (`tenant_id IS NULL`): readable by every tenant, changeable only with no tenant
  asserted. A tenant must not delete, re-ingest, merge or dismiss shared knowledge.
- **Research sessions cannot be global** — `ke_research_sessions.tenant_id` is `NOT NULL` — so for
  research "own + global" is simply "own", and its list query stays as it is.
- **Search expansion (KNOW-F2):** `KnowledgeQueryService` expands each hit with `findConnected`; the
  returned neighbors are filtered by the P3.8a search rule (tenant → own + global; null → global only).

## 4. ADR-009 — Multi-tenancy (written in this PR)

`documentation/adrs/ADR-009-multi-tenancy.md`, in the style of ADR-001…008. Records, as decided:

1. **Where a tenant comes from:** JWT tenant for tenant-bound roles; `X-Tenant-Id` for `ADMIN`; the
   `tenant_id` Kafka header for events (fail-closed, global sentinel for "none"); never a
   tenant-bound caller's request body.
2. **Global** = `tenant_id IS NULL` (sentinel `00000000-…` on Kafka and where a column is `NOT NULL`).
3. **Visibility:** a tenant sees its own rows plus global rows; it never sees another tenant's.
4. **Knowledge search** (P3.8a): no tenant → global only.
5. **Operational admin views** (this spec): no tenant asserted → trusted caller, unrestricted.
6. **Writes to global rows** only in global scope (platform `ADMIN`).
7. **System prompt templates are global** (PROMPT-TENANT).
8. **Enforcement:** JPA services use the Hibernate `tenantFilter` for tenant-bound reads; id-addressed
   lookups (which Hibernate filters do not cover) check ownership explicitly (`TenantAccess`); the
   admin-api edge binds the tenant (`KnowledgeTenantResolver`).
9. **`TenantContextFilter` (emcip-core, servlet) is removed:** no service ever registered it, and its
   behaviour (400 without a tenant header) contradicts rule 5. HTTP tenant binding happens at the
   admin-api edge (`AdminTenantContextFilter`, WebFlux) and is passed on explicitly.
10. **Open, deferred to ADR-010:** whether knowledge-engine and other internal services authenticate
   in-cluster callers; until then NetworkPolicy (3.20) limits who can reach them.

## 5. Testing

Every new assertion is observed failing once before it counts.

- **knowledge-engine, real Postgres/AGE (`IntegrationTest` harness):** seed sessions, jobs, resolution
  flags and graph nodes for tenant A, tenant B and global. With A asserted: A's and global items readable,
  B's → 404; pause/delete/merge/dismiss of a global item → 404; lists = A + global, never B; neighbors of
  B's node → 404, neighbors of A's node never include B's. With no tenant: unchanged. Missing job → 404
  (was 500). Search expansion never returns B's neighbors for A.
- **`TenantAccess` unit test** covering the full truth table.
- **admin-api (MockWebServer at the knowledge-engine boundary):** for a tenant-bound caller the JWT tenant
  arrives on every endpoint type (create body, list query, upload part, id-addressed query); an `ADMIN` in
  admin mode sends none unless it names one; `BackfillProxyController` behaviour unchanged.

## 6. Code removed

`emcip-core` `TenantContextFilter` and `TenantContextFilterTest` (dead: registered by no service; see
ADR-009 rule 9).

## 7. Tracking changes

- BACKLOG: KNOW-F1 delivered (scope as §0); KNOW-F2 closed into it; resolution review noted.
- ROADMAP 3.14: ADR-009 delivered; ADR-010 gains "internal service authentication (knowledge-engine,
  Kafka / RT-005)". ROADMAP 3.20: add "NetworkPolicy — knowledge-engine ingress limited to admin-api and
  llm-orchestrator".
- BACKLOG: file **TENANT-AUDIT** and **DOCS-FAILIF** (see §10).

## 8. Documentation and diagrams (final task)

This PR brings every document that describes tenancy in line with ADR-009 — including drift left by
PR #255 (automated responses switch, global system templates, cost-log tenant) and PR #256 (search tenant
rule), whose diagrams were not updated.

| File | Change |
|---|---|
| `documentation/adrs/ADR-009-multi-tenancy.md` | new (§4) |
| `documentation/diagrams/sequence-tenant-propagation.puml` | redraw the real paths: admin-api `AdminTenantContextFilter` → Reactor context → proxies assert `tenantId` → knowledge-engine `TenantAccess`; Kafka `tenant_id` header → `TenantContext` → Hibernate `tenantFilter`. Remove the servlet `TenantContextFilter` path (never registered). |
| `documentation/diagrams/c3-knowledge-engine.puml` | real controllers (search, research, ingestion, resolution review, ontology, backfill) instead of one `KnowledgeController`; `TenantAccess`; correct the "tenant isolation" claim to the P3.8a / ADR-009 rules |
| `documentation/diagrams/c3-admin-api.puml` | knowledge proxies (search, research, ingestion, resolution review, backfill) and `KnowledgeTenantResolver` |
| `documentation/diagrams/c3-llm-orchestrator.puml` | catch-up #255: `PolicyDecisionConsumer` gated by `emcip.llm.automated-responses.enabled`; system templates global; cost logs per tenant |
| `documentation/diagrams/sequence-llm-orchestration.puml` | catch-up #255: switch check before `callForTask`; template lookup under the tenant filter with `system = true` visible |
| `documentation/architecture-guide.adoc` | knowledge-engine: enforcement model (§3) next to the P3.8a paragraph; admin-api: knowledge proxies bind the tenant; ADR list: ADR-009 |
| `documentation/developer-guide.adoc` | §5 Service APIs: `tenantId` on knowledge-engine research / ingestion / resolution / neighbors endpoints and what it means |
| `documentation/user-guide.adoc` | Knowledge, Research, Ingestion and Resolution pages: tenant users see their own + global items, cannot change global ones (actions hidden, §9), and ingest into their own tenant |

Every file in the table is checked against the code it describes, not against this spec.

Diagram verification: all five diagrams are included from `architecture-guide.adoc` /
`developer-guide.adoc` and rendered by the root `asciidoctor-maven-plugin` (`asciidoctorj-diagram`,
bundled modern PlantUML — unaffected by the outdated local `plantuml` 1.2020). Render with
`mvn -N generate-resources` and **read the log** for PlantUML errors: the plugin has no `failIf`, so a
broken diagram does not fail the build (CI included). Filed as **DOCS-FAILIF**: make diagram and
Asciidoctor errors fail the build.

## 9. Admin UI

The UI has no role awareness for these actions today; without changes it would offer tenant users
choices that the backend now overrides or refuses:

- `IngestionModal` offers everyone a tenant picker including "Global (all tenants)" — admin-api would
  silently replace a tenant user's choice with their own tenant.
- Delete / re-ingest (`KnowledgePage`) and merge / dismiss (`ResolutionQueue`) are shown on **global**
  items to everyone — a tenant user would get a 404 error. (Pause / resume in
  `Research/SessionDetailPage` needs no change: research sessions are never global.)

Change, following `IntegrationsPage`'s existing `isAdmin` pattern (`hasPermission(role, …)` from
`auth/permissions`):

- `IngestionModal`: the tenant picker is shown to `ADMIN` only; everyone else ingests into their own
  tenant, and the modal says so.
- Change actions on an item whose `tenantId` is null are hidden for non-`ADMIN` users, with a short
  "Global — managed by a platform admin" label in their place.
- vitest: one test per page asserting a non-`ADMIN` user sees no change action on a global item and an
  `ADMIN` does; `IngestionModal` shows the picker only to `ADMIN`. Each observed red first.

The backend rules (§3) remain the enforcement; the UI change only stops offering what will be refused.

## 10. Out of scope

Each item is tracked where named; none is silently dropped.

| Item | Why not here | Tracked in |
|---|---|---|
| Authentication of in-cluster callers to knowledge-engine (and other internal services) | an architecture decision — "no tenant = trusted" stays valid only while the caller set is controlled | **ADR-010** (3.14), together with RT-005 (unauthenticated Kafka) |
| NetworkPolicy limiting knowledge-engine ingress to admin-api and llm-orchestrator | the 1.0 mitigation for the item above; infra, not code | **3.20** (K8s pod hardening) |
| Tenant audit of admin-api's **other** proxies and clients — AI config, costs, audit, intent classifier, moderation, policy engine, Telegram accounts | ADR-009 states platform-wide rules, but this PR audits only the knowledge-engine path | **TENANT-AUDIT** (new, P3): check each against ADR-009 |
| Backfill `GET /status?backfillId=` | returns progress counters only, no content | — (audited, accepted) |
| `OntologyController` | not proxied by admin-api; reachable only in-cluster | ADR-010 / 3.20 |
| Diagram and Asciidoctor errors do not fail the build | CI build configuration, independent of tenancy | **DOCS-FAILIF** (new) |
| Data-poisoning / retrieval trust of knowledge content | about *what* knowledge says, not *who* may see it | **KE-TRUST** |
| Data migration | nothing to migrate: 0 research sessions, 2 global ingestion jobs, all knowledge global (2026-09-25) | — |
| ADR-010 (auth/authz) and ADR-011 (API versioning) | the other two ADRs of 3.14 | **3.14** |
