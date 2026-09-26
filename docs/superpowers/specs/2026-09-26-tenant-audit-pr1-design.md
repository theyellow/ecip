# TENANT-AUDIT PR 1 — Servlet tenant binding, policy/flag/cost tenant enforcement

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

## 1. Servlet tenant binding (emcip-core)

New `io.emcip.common.tenant.TenantHeaderFilter` (`OncePerRequestFilter`), ADR-009-conformant:

- `X-Tenant-Id` present and a valid UUID → `TenantContext.setTenantId(...)`, cleared in `finally`.
- Header **absent** → no tenant, request continues (trusted caller, ADR-009 rule 5) — **no 400**.
- Header present but **not a UUID** → **400** (an asserted tenant must be meaningful; today a malformed
  value would reach `UUID.fromString` in `TenantFilterAspect` and 500).
- Not a `@Component`: each service registers it explicitly (`FilterRegistrationBean`), so wiring is a
  visible per-service decision.

### Where it is wired — and where deliberately not

| Service | Wire? | Why |
|---|---|---|
| **policy-engine** | **yes** | Rules + decisions carry `tenantFilter`; admin-api's PolicyEngineClient is the caller. Fixes P-1/F-2 lists. |
| **llm-orchestrator** | **yes** | Cost logs carry `tenantFilter` (templates: `OR system = true`, already correct). Fixes C-1 together with §4. |
| knowledge-engine | no | KNOW-F1 enforces explicit `tenantId` params; its `KnowledgeDocument` filter is own-only (no global) and would contradict the search rule if any caller ever sent the header. |
| conversation-context | no | Not proxied by admin-api; in-cluster only — Round 3 T-09 / ADR-010. |
| intent-classifier | no | Already reads the header per controller; no Hibernate filter. |

## 2. policy-engine: explicit ownership on id-addressed calls

Hibernate filters do not apply to `findById` (ADR-009 rule 8), so binding alone does not fix writes.
With a tenant bound (`TenantContext.getTenantId() != null`):

- `GET /api/policy-decisions/{id}`, `PUT /api/policy-decisions/{id}`, `PUT /api/policy-rules/{id}`,
  `DELETE /api/policy-rules/{id}`, `GET /api/policy-rules/{id}/history`: the item must belong to the
  bound tenant, else **404** (identical to missing). Policy rules are never global
  (`tenant_id NOT NULL`); a decision with `tenant_id NULL` is readable, not changeable (ADR-009 rule 6).
- Lists are scoped by the (now active) Hibernate filter.
- No tenant bound → unchanged (trusted caller).

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
- llm-orchestrator cost aggregates take an optional tenant: the JPQL queries get the Hibernate filter
  via §1; the **native** query gets an explicit `(:tenantId IS NULL OR tenant_id = :tenantId)` predicate
  (a native query is never filtered).

## 5. Testing

Every new assertion observed red first (or forced red with a deliberately broken implementation).

- **emcip-core** `TenantHeaderFilterTest`: header → bound during the chain and cleared after; absent →
  unbound, chain runs; malformed → 400, chain not run.
- **policy-engine** ITs (real Postgres): with header for tenant A — list excludes B's rules/decisions;
  `PUT`/`DELETE` of B's rule → 404 and unchanged; `GET`/`PUT` of B's decision → 404. No header →
  unchanged. Test goes through MockMvc so the registered filter is exercised (not direct method calls).
- **llm-orchestrator** IT: cost totals/by-model/by-day with header for A exclude B's cost logs, including
  the native query.
- **admin-api**: PolicyEngineClient sends the header on every method (MockWebServer); `FlagService.reply`
  for another tenant's decision → 404 **and no request reaches tdlib-adapter**; costs proxy sends the
  header.

## 6. Documentation

- ADR-009 amendment (in this PR, since it corrects the enforcement model): rule 8 gains "servlet
  services bind `X-Tenant-Id` via `TenantHeaderFilter` where wired (policy-engine, llm-orchestrator); the
  reactive audit/moderation services via their `TenantWebFilter`"; rule 9 notes the replacement.
- `sequence-tenant-propagation.puml`: add the servlet HTTP path (admin-api → policy-engine →
  `TenantHeaderFilter` → Hibernate filter). Architecture guide: policy-engine/llm-orchestrator tenant
  binding. BACKLOG: TENANT-AUDIT findings table, PR 1 rows closed.
- Rendered via the project's asciidoctor build, log and images checked.

## 7. Out of scope (PR 2 or elsewhere)

T-1 (Telegram create tenant), S-1 (simulation chatId), M-1 (moderation global rules), A-1 (audit
correlation lookup), U-1 (UI permission mismatch) → PR 2. knowledge-engine / conversation-context /
intent-classifier header binding → not needed (§1). Live confirmation → Round 3 T-04 after both PRs.
