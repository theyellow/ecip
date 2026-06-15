# EMCIP Backlog

> Last updated: 2026-06-15
> Single source of truth for all open work. Completed items are in §5.
> Size guide: **XS** < 2h · **S** ½ day · **M** 1–2 days · **L** 3–5 days · **XL** > 1 week

---

## 1. Review-Driven Structural Changes

> All SC items complete. Full findings in `documentation/REVIEW-2026-05-18.md §8.2`.

| # | Item | Size | Done |
|---|------|------|------|
| SC1 | **Extract service layer** from controllers | S | ✅ PR #58 |
| SC2 | **Input validation** — `@Valid` on all request bodies | S | ✅ PR #62 |
| SC3 | **Replace ThreadLocal tenant** with Reactor `Context` | S | ✅ PR #60 |
| SC4 | **Multi-tenancy enforcement** — Hibernate @Filter, ReactorTenantContext | L | ✅ |
| SC5 | **Refactor `AuditEventConsumer`** — extract generic handler | S | ✅ PR #63 |
| SC6 | **Pagination enforcement** — `PageResponse<T>`, size cap 200 | M | ✅ PR #73 |
| SC7 | **Refresh token** — 1 h JWT, `/api/auth/refresh` | M | ✅ PR #73 |
| SC8 | **Circuit breakers** on all WebClient downstream calls | M | ✅ PR #73 |
| SC9 | **Network segmentation** in docker-compose | S | ✅ PR #63 |

---

## 2. Open — Feature Work

> Ordered: quick wins → UI polish → architecture → incremental features → ML-gated work.

| # | Item | Size | Notes |
|---|------|------|-------|
| SC6b | **Audit-log page: filters + pagination** | S | ✅ Done. Operational time presets (10m–72h), custom datetime picker, working pagination, loading state. Spec: `docs/superpowers/specs/2026-06-14-audit-log-filters-pagination-design.md`. |
| 40 | **SC8 resilience follow-ons** | S | ✅ Done. Retry (3 attempts, exponential backoff) + read fallbacks on all admin-api downstream clients. Spec: `docs/superpowers/specs/2026-06-14-sc8-resilience-follow-ons-design.md`. |
| 23 | **Flag-detail: AI-research prompt interface (Phase 2)** | M | ✅ Done. Multi-turn AI Research chat in Flag Detail modal. Spec: `docs/superpowers/specs/2026-06-15-ai-research-chat-design.md`. |
| 24 | **Flag-detail: AI analysis end-to-end test** | S | ✅ Done. Missing `GENERAL` task-type model seed added (migration 011). Spec: `docs/superpowers/specs/2026-06-14-ai-analysis-e2e-design.md`. |
| 41 | **Admin UI: design-handoff deferred UX** | M | Three items deferred from v2 handoff (PR #94): (1) **Simulate** two-column layout with real-time animated pipeline trace; (2) **Decisions** reply composer v2 — 4-mode SegmentedControl, chip-row templates, char counter; (3) **Users** expanded roles (MODERATOR, ANALYST, VIEWER) + `lastLogin`/`createdAt` columns — needs backend role expansion first. |
| 6 | **Policy versioning — complex rule logic (Epic 5.3)** | L | DB schema exists (`005-policy-rule-versioning.xml`). Time-based and context-aware rule evaluation not yet implemented. **Scope to be redefined/refined before picking up** — the full design for complex rule logic has not been settled. |
| 26 | **Knowledge Foundation** — new `emcip-knowledge-engine` service | XL | ✅ Foundation complete (PR #122/#123). Service skeleton, Liquibase migrations (pgvector + AGE), Kafka consumers/producers, vector search, graph repository, health indicator, Helm/Prometheus/Grafana. Remaining user stories (US-26.4–26.10) tracked as follow-up work. Spec: `specs/2026-06-13-knowledge-management-platform-design.md`. ADR-008. Prerequisite for #27. |
| 27 | **Deep Research Agent** | XL | Operator-triggered autonomous research agent. Multi-step LLM reasoning, knowledge base query strategies, web search, evidence collection, structured reports, cost guardrails. 9 user stories (US-27.1–27.9). Spec: `specs/2026-06-13-knowledge-management-platform-design.md`. Depends on #26. |
| 42 | **Structured feed connectors for Factual Knowledge** | M | Predefined source connectors (Wikipedia API, arXiv, PubMed) for automated periodic ingestion into the knowledge base. Depends on #26. |
| 8 | **ML toxicity detection** | XL | Replace keyword/regex moderation rules with a model-based scorer (OpenNLP, Perspective API, or local LiteLLM). Architecture decision needed before implementation. |
| 7 | **LLM cost analytics dashboard** | M | Admin UI page: per-tenant call counts + token spend. Data already in `model_cost_logs`. Ref: `specs/2026-04-24-admin-ui-phase2-design.md`. |

---

## 3. Infrastructure / Pre-1.0.0 Requirements

> All items below must be complete before any public release.
> Goal: `helm install` on a blank cluster must produce a fully working system with no manual steps.

| # | Item | Size | Notes |
|---|------|------|-------|
| INF1 | **Liquibase migration consolidation** | M | Each service has 6–10 incremental migrations, several with `md5sum='manual'` (root cause of 2026-05-20 AI Config 500). Squash to single `001-initial-schema.xml` per service before 1.0.0. |
| INF2 | **Fresh install smoke test** | S | After INF1: drop test DB, `helm install`, verify all pages. Document in `docs/operations/fresh-install.md`. |
| INF3 | **Telegram test account seeding via Helm values** | S | `testing.telegram.enabled` + account params → post-deploy Job inserts row into `telegram_accounts`. Removes manual DB access during dev. |
| 21 | **Tenant provisioning / onboarding flow** | M | No way to create a tenant without direct DB access. Needs admin-api endpoint + Liquibase-safe seed flow. Blocks 1.0.0. |
| 4 | **Test coverage to 80% (JaCoCo gate)** | L | Weakest: `moderation-service`, `audit-service`, `llm-orchestrator`. Phase 4 DoD requirement. |
| 14 | **Gatling load tests in CI** | S | 3 simulations exist (`IntentClassifierSimulation`, `AdminApiSimulation`, `PolicyEngineSimulation`). No CI gate yet. |
| 12 | **Kubernetes HA / multi-replica** | M | HPA templates, tuned `replicas`, PodDisruptionBudgets. Ref: `specs/2026-04-29-kubernetes-helm-deployment-design.md`. |

---

## 4. Deferred

> Not needed before 1.0.0. Revisit when cluster grows or a concrete use case arises.

| # | Item | Size | Notes |
|---|------|------|-------|
| PolicyRules-paging | **Paginate `PolicyRuleController.listActive()`** | XS | SC6 adds `.take(200)` safety cap. Full `PageResponse<T>` not needed — policy rules are config data and will stay small. Revisit if a tenant exceeds ~100 rules. |
| 20 | **Mixed-cluster: node taints + tolerations** | S | Fine-grained pod scheduling. `nodeSelector` is sufficient today. Ref: `specs/2026-05-02-mixed-cluster-helm-values-design.md`. |
| 19 | **Mixed-cluster: arm64 native images** | L | Cross-compile GraalVM native for Pi 4 nodes. Needs QEMU or dedicated arm64 runner. Ref: `specs/2026-05-02-mixed-cluster-helm-values-design.md`. |
| 13 | **GraalVM native — R2DBC services** | XL | 4 services JVM-only (`moderation-service`, `audit-service`, `admin-api`, `intent-classifier`). Blocked on R2DBC + GraalVM reflection hints. Ref: `specs/2026-04-29-graalvm-native-migration-design.md`. |

---

## 5. Completed

> One line per item. PR number + date is enough to find the full context in git / specs.

| # | Item | Done |
|---|------|------|
| INF4 | Refresh token cleanup job | ✅ PR #73 — 2026-05-21 |
| 9 | Telegram: self-service account connection | ✅ PR #80 — 2026-05-21. Spec: `specs/2026-05-21-telegram-self-service-rbac-design.md` |
| 10 | Telegram: multi-account scaling foundation | ✅ PR #85 — 2026-05-27. Spec: `specs/2026-05-26-tdlib-multi-account-scaling-design.md` |
| 25 | Group name + full sender info on flags and audits | ✅ PR #85 — 2026-05-27 |
| 23 | Flag-detail: operator reply panel (Phase 1) | ✅ PR #89 — 2026-05-27. Spec: `specs/2026-05-27-flag-detail-operator-reply-design.md` |
| 28 | Admin UI v2: design system + Groups page | ✅ PR #90 — 2026-05-28. Spec: `specs/2026-05-28-admin-ui-v2-design-system-design.md` |
| 29 | Admin UI v2: all page redesigns | ✅ PR #91/#94 — 2026-05-28/29 |
| 22 | Admin UI: cross-tenant views (ADMIN dropdown) | ✅ 2026-06-05 |
| 30 | Admin UI v2: remove v1 compat aliases | ✅ 2026-06-05 |
| 31 | Admin UI v2: delete design handoff directory | ✅ 2026-06-05 |
| 32 | Admin UI v2: SpaceBackground v3 (Otherland Sky) | ✅ 2026-06-05 |
| 33 | Policy rule: warn on live-effect actions in UI | ✅ 2026-06-05 |
| 34 | Architecture: rewire moderation-service off `telegram.raw.messages` | ✅ 2026-06-05. Spec: `specs/2026-06-05-moderation-service-rewire-design.md` |
| 35 | Self-host Inter Variable font | ✅ 2026-06-05 |
| 36 | Signal detectors: 9 structural/script abuse detectors | ✅ PR #115 — 2026-06-08. Spec: `specs/2026-06-08-signal-detectors-design.md` |
| 39 | Decisions page: filters, pagination, rename from Flags | ✅ PR #117 — 2026-06-09 |
| 26 | Knowledge Foundation — `emcip-knowledge-engine` service skeleton | ✅ PR #122/#123 — 2026-06-13. Spec: `specs/2026-06-13-knowledge-management-platform-design.md` |
| SC6b | Audit-log page: filters + pagination | ✅ 2026-06-14. Spec: `specs/2026-06-14-audit-log-filters-pagination-design.md` |
| 40 | SC8 resilience follow-ons: retry + read fallbacks | ✅ 2026-06-14. Spec: `specs/2026-06-14-sc8-resilience-follow-ons-design.md` |
| 24 | Flag-detail: AI analysis end-to-end fix | ✅ 2026-06-15. Spec: `specs/2026-06-14-ai-analysis-e2e-design.md` |
| 23 | Flag-detail: AI Research chat (Phase 2) | ✅ 2026-06-15. Spec: `specs/2026-06-15-ai-research-chat-design.md` |

---

## Documentation Audit (2026-05-16)

> **New diagram (2026-06-02):** `documentation/diagrams/kafka-topic-flow.puml` — topic-centric Kafka flow, all 10 topics, producer/consumer mapping.
> **New diagram (2026-06-13):** `documentation/diagrams/c3-knowledge-engine.puml` — Knowledge Engine component view.

Diagrams confirmed current during LiteLLM integration pass:

| Diagram | Covers |
|---|---|
| `c3-policy-engine.puml` | Policy Engine component view |
| `c3-tdlib-adapter.puml` | TDLib Adapter component view |
| `c4-policy-domain.puml` | Policy domain model |
| `sequence-error-handling.puml` | Retry, DLQ, and circuit breaker flow |
| `sequence-admin-auth.puml` | Admin UI JWT authentication flow |
| `sequence-policy-evaluation.puml` | Policy evaluation detail flow |
| `dataflow-context-enrichment.puml` | Conversation context enrichment data flow |
