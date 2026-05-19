# EMCIP Backlog

> Last updated: 2026-05-19 (all Quick Wins confirmed done; SC1 ✅ PR #58; SC3 ✅ PR #60; SC2/SC4–SC9 added from REVIEW-2026-05-18.md)
> Single source of truth for all open work.
> Size guide: **XS** < 2h · **S** ½ day · **M** 1–2 days · **L** 3–5 days · **XL** > 1 week

Items are ordered by priority.

---

## Feature Work

| # | Item | Size | Notes |
|---|------|------|-------|
| 7 | **LLM cost analytics dashboard** | M | Admin UI page: per-tenant call counts + token spend. Data already exists in `model_cost_logs`. Ref: `specs/2026-04-24-admin-ui-phase2-design.md`. |
| 4 | **Test coverage to 80% (JaCoCo gate)** | L | Split by service — each piece is S/XS. Weakest: `moderation-service` (2 files), `audit-service` (3 files), `llm-orchestrator` (new code). Phase 4 DoD requirement. |
| 14 | **Gatling load tests in CI** | S | 3 simulations exist (`IntentClassifierSimulation`, `AdminApiSimulation`, `PolicyEngineSimulation`). No CI integration, no regression gate. |
| 12 | **Kubernetes HA / multi-replica** | M | HPA templates, tuned `replicas` per service tier, PodDisruptionBudgets for critical services. Ref: `specs/2026-04-29-kubernetes-helm-deployment-design.md`. |
| 6 | **Policy versioning — complex rule logic (Epic 5.3)** | L | DB schema exists (`005-policy-rule-versioning.xml`). Time-based and context-aware rule evaluation not implemented. |
| 9 | **Telegram: self-service account connection** | L | Allow end-users (not just admins) to link Telegram accounts via phone → OTP flow. Ref: `specs/2026-04-26-telegram-multi-account-auth-design.md`. |
| 21 | **Tenant provisioning / onboarding flow** | M | No way to create a tenant without direct DB access. Needs admin-api endpoint + Liquibase-safe seed flow. |
| 22 | **Admin UI: cross-tenant views** | M | Admin users should be able to browse data across all tenants. Requires admin-api ADMIN-mode bypass (built in #5) + UI pages. |

---

## Review-Driven Structural Changes (from REVIEW-2026-05-18.md)

> SC1 (service layer) and SC3 (Reactor tenant context) are done. Remaining items below.
> Full findings and rationale in `documentation/REVIEW-2026-05-18.md §8.2`.

| # | Item | Size | Notes |
|---|------|------|-------|
| SC2 | **Input validation** — `@Valid` on all request bodies, Jakarta annotations on DTOs | S | admin-api. Zero validation today. Addresses A2, S12. |
| SC4 | **Multi-tenancy enforcement** (plan already exists) | L | All modules. Plan at `docs/superpowers/plans/2026-05-17-multi-tenancy-enforcement.md`. Overlaps with #21 and #22. |
| SC5 | **Refactor `AuditEventConsumer`** — extract generic handler, cut 358-line duplication | S | audit-service. 5 nearly identical handler methods. |
| SC6 | **Pagination enforcement** — upper-bound `size`, return metadata with total count | M | admin-api, policy-engine, audit-service. Addresses A5. |
| SC7 | **Refresh token** — reduce JWT to 1–2h expiry, add `/api/auth/refresh` | M | admin-api. Addresses S11. |
| SC8 | **Circuit breakers** on WebClient calls to downstream services | M | admin-api. resilience4j or Spring Retry. Addresses A7, G7. |
| SC9 | **Network segmentation** in docker-compose — data-tier / app-tier / monitoring-tier | S | docker-compose. Addresses S20. |

---

## Lower Priority / Defer

| # | Item | Size | Notes |
|---|------|------|-------|
| 20 | **Mixed-cluster: node taints + tolerations** | S | Fine-grained pod scheduling if node pools grow. Currently `nodeSelector` is sufficient. Ref: `specs/2026-05-02-mixed-cluster-helm-values-design.md`. |
| 19 | **Mixed-cluster: arm64 native images** | L | Cross-compile GraalVM native for Pi 4 nodes. Needs QEMU emulation or a dedicated arm64 runner. Ref: `specs/2026-05-02-mixed-cluster-helm-values-design.md`. |
| 8 | **US-4.1.1 — ML toxicity detection** | XL | Replace keyword/regex rules with OpenNLP or Perspective API scoring. Architecture decision needed before implementation. |
| 10 | **Telegram: concurrent multi-account sessions** | XL | Only one Telegram account active at a time. True concurrency needs `tdlib-adapter` architectural rework. Ref: `specs/2026-04-26-telegram-multi-account-auth-design.md`. |
| 13 | **GraalVM native — R2DBC services** | XL | 4 services JVM-only: `moderation-service`, `audit-service`, `admin-api`, `intent-classifier`. Blocked on R2DBC + GraalVM reflection hints investigation. Ref: `specs/2026-04-29-graalvm-native-migration-design.md`. |

---

## Documentation Audit (2026-05-16)

### Diagrams confirmed current — no update needed

Audited during the LiteLLM integration documentation pass. These diagrams accurately reflect the implemented state and require no changes at this time:

| Diagram | Covers |
|---|---|
| `c3-policy-engine.puml` | Policy Engine component view |
| `c3-tdlib-adapter.puml` | TDLib Adapter component view |
| `c4-policy-domain.puml` | Policy domain model |
| `sequence-error-handling.puml` | Retry, DLQ, and circuit breaker flow |
| `sequence-admin-auth.puml` | Admin UI JWT authentication flow |
| `sequence-policy-evaluation.puml` | Policy evaluation detail flow |
| `dataflow-context-enrichment.puml` | Conversation context enrichment data flow |
