# KNOW-F1 — Research and ingestion tenant scoping

**Date:** 2026-09-25 · **Backlog:** KNOW-F1 (follow-up of P3.8a / TENANT-F1) · **Status:** approved design

## 0. Premise correction

KNOW-F1 was filed as "the research and ingestion proxies take a caller-supplied tenant". The audit found
two problems:

1. **Caller-chosen tenant** on create and list — the pattern P3.8a fixed for search:
   research `POST /` (body), `GET /?tenantId`; ingestion `POST /url` (body), `POST /upload?tenantId`,
   `GET /?tenantId`.
2. **No ownership check at all on id-addressed endpoints (IDOR).** knowledge-engine looks items up with
   `findById(id)` and never compares tenants: research `GET /{id}`, `POST /{id}/pause`, `POST /{id}/resume`,
   `GET /{id}/report`, `GET /{id}/report/markdown`; ingestion `GET /{jobId}`, `GET /{jobId}/details`,
   `DELETE /{jobId}`, `POST /{jobId}/reingest`. Anyone holding an id can read another tenant's item, and
   with `KNOWLEDGE_WRITE` pause, resume, **delete** or re-ingest it.

Also: list endpoints with a null tenant return every tenant's items, and knowledge-engine never
registers `TenantContextFilter`, so nothing in it knows the caller's tenant on REST paths.

Live data (2026-09-25): 0 research sessions, 2 ingestion jobs, both global — the rules below change
almost nothing that exists today; they matter before tenants own data.

## 1. Trust model

knowledge-engine has no authentication of its own; admin-api is the security boundary.

- **A tenant asserted** in the call → knowledge-engine enforces it.
- **No tenant** → the caller is trusted (platform `ADMIN` in admin mode, or an in-cluster service) —
  exactly today's behaviour for every such caller.

The hole closes because admin-api always asserts the tenant of a tenant-bound caller (§2). Whether
knowledge-engine should authenticate in-cluster callers at all is out of scope (§5).

Difference from P3.8a, on purpose: in search, "no tenant" means global knowledge only. Here "no tenant"
stays unrestricted — these are operational admin views, and after §2 only a platform `ADMIN` reaches
them without a tenant.

## 2. admin-api: always assert the caller's tenant

`ResearchProxyController` and `DocumentIngestionProxyController` resolve the tenant with
`KnowledgeTenantResolver` (introduced in P3.8a) on **every** endpoint:

| Caller | Asserted tenant |
|---|---|
| Tenant-bound role (JWT tenant) | the JWT tenant — any tenant in the request is overwritten |
| `ADMIN` with `X-Tenant-ID` | the header tenant |
| `ADMIN` in admin mode | the tenant the request names, else none |

Transport: `tenantId` in the JSON body for `POST /` (research) and `POST /url` (ingestion), replacing any
caller value (non-object body → 400, as in P3.8a); `tenantId` query / multipart part for upload and lists,
replaced the same way; **a new `tenantId` query parameter on every id-addressed call**, omitted when no
tenant is asserted.

## 3. knowledge-engine: enforce when a tenant is asserted

One policy class, `TenantAccess`, used by both controllers:

- `canRead(UUID itemTenant, UUID asserted)` → `asserted == null || itemTenant == null || itemTenant.equals(asserted)`
- `canWrite(UUID itemTenant, UUID asserted)` → `asserted == null || Objects.equals(itemTenant, asserted)`

| Operation | tenant `t` asserted | no tenant |
|---|---|---|
| create | item belongs to `t` | as sent (global if none) |
| list | `t`'s items **plus global** | all (unchanged) |
| read by id — session, report, markdown, job, job details | allowed if `canRead`, else **404** | unchanged |
| change by id — pause, resume, delete, reingest | allowed if `canWrite`, else **404** | unchanged |

- **404, not 403,** so another tenant's ids reveal nothing. A denied item is indistinguishable from a
  missing one.
- Ingestion by-id endpoints currently turn a missing job into an `IllegalArgumentException` (a 500 — no
  controller advice exists). Missing and denied both become **404** there.
- Global items (`tenant_id IS NULL`): readable by every tenant, changeable only with no tenant asserted
  (platform `ADMIN`). A tenant deleting a global ingestion job would remove shared knowledge for all.
- List queries: research `findByTenantIdOrderByCreatedAtDesc` and ingestion
  `findAllByTenantIdOrderByCreatedAtDesc` become "tenant or global" queries.

## 4. Testing

Every new assertion is observed failing once before it counts.

- **knowledge-engine, real Postgres (`IntegrationTest` harness):** seed sessions and jobs for tenant A,
  tenant B and global. With A asserted: A's and global items readable, B's → 404; pause/delete of a global
  item → 404; list = A + global, never B. With no tenant: every call behaves as before. Missing job → 404
  (was 500).
- **`TenantAccess` unit test** covering the full truth table.
- **admin-api (MockWebServer at the knowledge-engine boundary):** for a tenant-bound caller, the JWT
  tenant arrives on create (body, overriding the caller's), list (query), upload (multipart part) and
  id-addressed calls (query); an `ADMIN` in admin mode sends none unless it names one.

## 5. Out of scope (BACKLOG)

- **KNOW-F2:** `findConnected` graph traversal is unscoped.
- **KNOW-AUTH (new, P4):** knowledge-engine trusts every in-cluster caller; decide whether it should
  authenticate callers (service token, like tdlib-adapter's `X-Service-Token`) so "no tenant =
  unrestricted" is no longer available to any pod in the namespace.
- The **3.14 multi-tenancy ADR** records this trust model alongside the P3.8a search rule and "system
  prompt templates are global".
