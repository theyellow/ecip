# TENANT-AUDIT PR 1 — HTTP tenant binding, policy/flag/cost tenant enforcement

**Date:** 2026-09-26 · **Backlog:** TENANT-AUDIT (findings F-1, P-1, F-2, C-1) · **Status:** approved design
**Context:** static pre-step for Round 3 session 3 (T-04 held until this and PR 2 are merged).
PR 2 (separate) covers T-1, S-1, M-1, A-1, U-1 and the rest of the ADR-009 amendment.

## 0. Audit findings this PR fixes

| # | Finding | Reachable by | Severity |
|---|---|---|---|
| **F-1** | `POST /api/flags/{id}/reply` for another tenant's flag posts into **that tenant's Telegram group via that tenant's account**: the decision lookup is unscoped, `GroupProfileRepository.findByTelegramChatId` is unscoped (a scoped `findByTelegramChatIdAndTenantId` exists, unused), and `resolveAccount` picks the account watching that group. Flag ids of other tenants are visible via the flag list (F-2). | TENANT_ADMIN, MODERATOR (`MODERATION_RULES_WRITE`) | **CRITICAL** |
| **P-1** | Policy rules: list shows every tenant's rules; `PUT`/`DELETE /api/policy-rules/{id}` change **any** tenant's rule (`findById`). | read: TENANT_ADMIN, MODERATOR, ANALYST; write: TENANT_ADMIN, MODERATOR | **HIGH** |
| **F-2** | Flags (policy decisions): list shows every tenant's flags; status update, analyse, chat and reply act on another tenant's flag by id (analyse/chat send its message content to the LLM). | `MODERATION_RULES_READ/WRITE` roles | **HIGH** |
| **C-1** | Costs: admin-api sends no tenant; llm-orchestrator's cost aggregates are unscoped (one is a native query). | TENANT_ADMIN, ANALYST (`COSTS_READ`) | MEDIUM |

**Root cause (P-1, F-2, C-1):** the JPA services rely on `TenantContext` (ThreadLocal) to enable their
Hibernate `tenantFilter`. The Kafka path sets it; **the HTTP path never does** — the servlet filter meant
for it (emcip-core pom: "for TenantContextFilter in MVC services") was never wired into any service, so
admin-api's `X-Tenant-Id` is ignored and every REST query runs unscoped. (Removing that class in #258
changed nothing at runtime — it was registered nowhere; re-verified: no annotation, no component scan,
no auto-configuration, no `@Import`.)

## 1. Tenant binding on the HTTP path — revised during planning

**Correction (2026-09-26, found while planning):** the approved design put a servlet
`TenantHeaderFilter` in emcip-core and wired it into policy-engine and llm-orchestrator. That cannot
work: **policy-engine is WebFlux** (`spring-boot-starter-webflux`) — a servlet filter is never
registered there — and its controllers run the blocking JPA calls on `Schedulers.boundedElastic()`,
another thread, so a ThreadLocal `TenantContext` (which the Hibernate `tenantFilter` aspect reads) would
be invisible anyway. Revised design — **explicit tenant, no ThreadLocal on the HTTP path**, the style
KNOW-F1 already uses:

- **policy-engine:** a reactive `TenantWebFilter` (same shape as audit-service's and
  moderation-service's) puts a valid `X-Tenant-Id` into the Reactor context; header absent → no tenant
  (trusted caller, ADR-009 rule 5); header present but not a UUID → **400**. Controllers read the tenant
  from the Reactor context and pass it **explicitly** into tenant-scoped queries and ownership checks.
- **llm-orchestrator** (Spring MVC, synchronous): the cost endpoints take the header explicitly —
  `@RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId` (malformed → 400 via type
  conversion) — and pass it into the queries (§4).
- **No emcip-core filter.** knowledge-engine, conversation-context and intent-classifier are unchanged
  (reasons as before: explicit params, not proxied, already handled). Consolidating the three reactive
  `TenantWebFilter` copies into emcip-core is a follow-up (TENANT-WEBFILTER).
- The Hibernate `tenantFilter` + `TenantFilterAspect` remain the **Kafka-path** mechanism only.

## 2. policy-engine: explicit tenant on every query

With a tenant in the Reactor context:

- `GET /api/policy-rules` → only the tenant's active rules (new repository method
  `findByActiveTrueAndTenantIdOrderByPriorityAsc`).
- `PUT` / `DELETE /api/policy-rules/{id}`, `GET /api/policy-rules/{id}/history` → the rule must belong
  to the tenant, else **404** (identical to missing). Policy rules are never global (`tenant_id NOT NULL`).
- `GET /api/policy-decisions` → `findByFilters(tenant, …)`, tenant taken from the Reactor context
  (today it reads the ThreadLocal, which is always null on this path).
- `GET` / `PUT /api/policy-decisions/{id}` → the decision must belong to the tenant; a decision with
  `tenant_id NULL` is readable, not changeable (ADR-009 rule 6); else **404**.
- `POST /api/policy-rules` and dry-run are unchanged (create takes the tenant from the body, which
  admin-api sets from the bound tenant; dry-run evaluates a rule supplied in the request, not stored rules).
- No tenant → unchanged (trusted caller).

## 3. admin-api: send the tenant on every policy call; F-1 defence in depth

- `PolicyEngineClient`: **every** method sends `X-Tenant-Id` when a tenant is bound (today only
  `createRule` and `listDecisions` do). A policy-engine 404 is returned as 404.
- `FlagService.reply` (F-1): even with §2, the reply path must not rely on one layer. With a bound tenant
  it requires the decision's `tenantId` to equal the caller's tenant (else 404), and uses
  `findByTelegramChatIdAndTenantId(chatId, tenant)` for the group profile. No tenant (ADMIN admin mode)
  → unchanged.
- `analyse`, `chat`, `updateStatus` rely on §2 (policy-engine 404 → 404).

## 4. Costs (C-1)

- `CostsProxyController` sends `X-Tenant-Id` (bound tenant) on totals/by-model/by-day; ADMIN admin mode
  sends none (all tenants, unchanged).
- llm-orchestrator's cost endpoints (summary, totals, by-model, by-day) pass the header's tenant into
  the aggregates, each with an explicit `(:tenantId IS NULL OR tenant_id = :tenantId)` predicate — the
  native by-day query included (`CAST(:tenantId AS uuid)` so a null binds).

## 5. Testing

Every new assertion observed red first (or forced red with a deliberately broken implementation).

- **policy-engine** `TenantWebFilterTest`: valid header → tenant in the Reactor context; absent →
  none, chain runs; malformed → 400, chain not run.
- **policy-engine** IT (real Postgres, `WebTestClient` bound to the real controllers **plus the real
  `TenantWebFilter`**): with header for tenant A — list excludes B's rules/decisions; `PUT`/`DELETE`/history
  of B's rule → 404 and B's rule unchanged; `GET`/`PUT` of B's decision → 404. No header → unchanged.
- **llm-orchestrator** IT: cost totals/by-model/by-day with header for A exclude B's cost logs, including
  the native query.
- **admin-api**: PolicyEngineClient sends the header on every method (MockWebServer); `FlagService.reply`
  for another tenant's decision → 404 **and no request reaches tdlib-adapter**; costs proxy sends the
  header.

## 6. Documentation

- ADR-009 amendment (in this PR, since it corrects the enforcement model): rule 8 states that on the
  HTTP path downstream services take the tenant **explicitly** — reactive services (audit, moderation,
  policy-engine) via their `TenantWebFilter` → Reactor context, servlet endpoints via an explicit header
  parameter (llm-orchestrator costs) — while the Hibernate `tenantFilter` is the Kafka-path mechanism.
- `sequence-tenant-propagation.puml`: add the policy-engine HTTP path (admin-api → `TenantWebFilter` →
  explicit tenant query). Architecture guide: policy-engine/llm-orchestrator tenant
  binding. BACKLOG: TENANT-AUDIT findings table, PR 1 rows closed.
- Rendered via the project's asciidoctor build, log and images checked.

## 7. Out of scope (PR 2 or elsewhere)

TENANT-WEBFILTER (new, P4): move the three reactive `TenantWebFilter` copies into emcip-core.

T-1 (Telegram create tenant), S-1 (simulation chatId), M-1 (moderation global rules), A-1 (audit
correlation lookup), U-1 (UI permission mismatch) → PR 2. knowledge-engine / conversation-context /
intent-classifier header binding → not needed (§1). Live confirmation → Round 3 T-04 after both PRs.
