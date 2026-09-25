# ADR-009: Multi-Tenancy — Tenant Sources, Visibility and Enforcement

## Status

**Accepted**

Date: 2026-09-25

---

## Context

EMCIP serves several tenants (community groups, business teams) from one deployment. Data carries a
`tenant_id`; `NULL` marks global data shared by every tenant. Until September 2026 the rules for who may
see and change what were never written down, and each service decided on its own. Three defects found
within two days showed the cost:

- **Knowledge search (P3.8a):** three search queries treated a missing tenant as *every tenant*, and
  admin-api's knowledge proxy forwarded a caller-chosen `tenantId` — a tenant-bound user could read any
  tenant's knowledge by naming it.
- **System prompt templates (PROMPT-TENANT):** templates are shared by design, but the Hibernate
  `tenantFilter` hid every one of them from tenant-scoped lookups — no automated LLM response had ever run.
- **Research, ingestion, resolution review, graph neighbours (KNOW-F1):** id-addressed endpoints looked
  items up by id and never compared tenants — anyone holding an id could read, delete, re-ingest, merge
  or dismiss another tenant's item.

Enforcement was also inconsistent in mechanism: JPA services used a Hibernate filter enabled from
`TenantContext`, which does **not** apply to `findById`; knowledge-engine received tenants as request
parameters and checked none; a servlet `TenantContextFilter` existed in emcip-core but no service
registered it.

---

## Decision

1. **Where a tenant comes from.** Tenant-bound roles (`TENANT_ADMIN`, `MODERATOR`, `ANALYST`, `VIEWER`):
   the tenant in their JWT. Platform `ADMIN`: the `X-Tenant-Id` header, or an explicit choice in the
   request, or none. Kafka events: the `tenant_id` header, validated fail-closed
   (`TenantAwareKafkaSupport`), with the global sentinel for "none". **Never** a tenant-bound caller's
   request body or query.
2. **Global** means `tenant_id IS NULL`. Where a column is `NOT NULL` (e.g. `model_cost_logs`), and on
   Kafka, the sentinel `00000000-0000-0000-0000-000000000000` stands for "no tenant".
3. **Visibility.** A tenant sees its own rows plus global rows; it never sees another tenant's.
4. **Knowledge search** (P3.8a): with a tenant, own + global; **without a tenant, global only**. Search
   results feed users and LLM prompts, so its default is the narrow one.
5. **Operational admin views** (research, ingestion, resolution review, graph neighbours): a tenant
   asserted in the call is enforced; **no tenant asserted means a trusted caller** (platform `ADMIN` in
   admin mode, or an in-cluster service) and is unrestricted. This holds only because admin-api always
   asserts a tenant-bound caller's tenant (rule 8).
6. **Writes to global rows** only in global scope (platform `ADMIN`). A tenant must not delete,
   re-ingest, merge or dismiss knowledge every tenant relies on.
7. **System prompt templates are global** (`prompt_templates.system = true`): visible to every tenant
   regardless of `tenant_id`; the filter is `tenant_id = :tenantId OR system = true`.
8. **Enforcement.**
   - **Edge:** admin-api binds the tenant (`AdminTenantContextFilter` → Reactor context) and every
     knowledge proxy asserts it via `KnowledgeTenantResolver`; a knowledge-engine 404 reaches the client
     as 404.
   - **Tenant-bound reads in JPA services:** the Hibernate `tenantFilter`.
   - **Id-addressed lookups** (which Hibernate filters do not cover): explicit ownership checks —
     knowledge-engine's `TenantAccess` (read: own or global; write: own only).
   - **Denied = missing:** a denied item answers 404, exactly like a missing one, so ids reveal nothing.
9. **`TenantContextFilter` (emcip-core, servlet) is removed.** No service registered it, and its
   behaviour (400 without a tenant header) contradicts rule 5.
10. **Open — deferred to ADR-010 (auth/authz):** whether knowledge-engine and other internal services
    authenticate in-cluster callers. Until then rule 5's "trusted caller" rests on who can reach them,
    to be limited by NetworkPolicy (ROADMAP 3.20). RT-005 (unauthenticated Kafka) is the same threat.

---

## Consequences

**Positive**

- One written rule set; each earlier defect maps to a rule, and new endpoints have a reference.
- Tenants can no longer read or change each other's data through any audited path, and cannot change
  global data.
- Global knowledge (all live knowledge as of 2026-09-25) is visible to tenants in search and graph
  views, where before tenant-scoped graph search returned nothing.

**Negative / accepted**

- Two meanings of "no tenant" (rule 4 vs rule 5). Deliberate, but it must be stated wherever a new
  endpoint accepts an optional tenant.
- Rule 5 trusts any caller that reaches knowledge-engine without a tenant — acceptable only with the
  NetworkPolicy in 3.20 and pending ADR-010.
- admin-api's non-knowledge proxies (AI config, costs, audit, intent, moderation, policy, Telegram
  accounts) have not yet been checked against these rules (TENANT-AUDIT).

---

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Reject requests without a tenant everywhere | Breaks trusted callers (platform `ADMIN`, in-cluster services) and leaves no way to ask for global data. |
| "No tenant = all tenants" in search | Fail-open: any caller that omits the tenant reads every tenant. |
| Enforce ownership only at the admin-api edge (fetch, compare, forward) | Two calls per request; the rule lives far from the data and does not protect other in-cluster callers. |
| Rely on the Hibernate `tenantFilter` for id lookups | Hibernate filters do not apply to `findById` / `EntityManager.find`. |
| Keep `TenantContextFilter` for future servlet services | Its fail-on-missing-header behaviour contradicts rule 5; a future service can add a filter that matches these rules. |

---

## Related ADRs

- ADR-003 (Data persistence with PostgreSQL), ADR-008 (Knowledge management with PostgreSQL extensions)
- ADR-010 (auth/authz, planned — ROADMAP 3.14)

## Related Specs

- `docs/superpowers/specs/2026-09-25-p3.8a-knowledge-search-tenant-scoping-design.md`
- `docs/superpowers/specs/2026-09-25-know-f1-knowledge-engine-tenant-enforcement-design.md`
