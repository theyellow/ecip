# EMCIP Backlog

> Last updated: 2026-05-30 (item 29 complete — all Admin UI v2 page redesigns)
> Single source of truth for all open work.
> Size guide: **XS** < 2h · **S** ½ day · **M** 1–2 days · **L** 3–5 days · **XL** > 1 week

---

## 1. Now — Finish Review-Driven Structural Changes

> All SC items complete. Full findings in `documentation/REVIEW-2026-05-18.md §8.2`.

| # | Item | Size | Notes |
|---|------|------|-------|
| SC1 | **Extract service layer** from controllers | S | ✅ PR #58 — 2026-05-18 |
| SC2 | **Input validation** — `@Valid` on all request bodies, Jakarta annotations on DTOs | S | ✅ PR #62 — 2026-05-19 |
| SC3 | **Replace ThreadLocal tenant** with Reactor `Context` | S | ✅ PR #60 — 2026-05-19 |
| SC4 | **Multi-tenancy enforcement** | L | ✅ Hibernate @Filter, ReactorTenantContext, TenantAwareKafkaSupport |
| SC5 | **Refactor `AuditEventConsumer`** — extract generic handler | S | ✅ PR #63 — 2026-05-19 |
| SC9 | **Network segmentation** in docker-compose | S | ✅ PR #63 — 2026-05-19 |
| SC6 | **Pagination enforcement** — upper-bound `size`, return total count | M | ✅ PR #73 — 2026-05-21 |
| SC7 | **Refresh token** — reduce JWT to 1–2h expiry, add `/api/auth/refresh` | M | ✅ PR #73 — 2026-05-21 |
| SC8 | **Circuit breakers** on WebClient calls to downstream services | M | ✅ PR #73 — 2026-05-21 |

---

## 2. Up Next — Feature Work

> Order reflects current priorities. Telegram features promoted from "defer".

| # | Item | Size | Notes |
|---|------|------|-------|
| 9 | **Telegram: self-service account connection** | L | ✅ PR — 2026-05-21. TENANT_ADMIN role, permission matrix, JWT tenantId (S10), user management API + UI, global tenant switcher. Ref: `specs/2026-05-21-telegram-self-service-rbac-design.md`. |
| 10 | **Telegram: multi-account scaling foundation** | XL | ✅ PR #85 — 2026-05-27. Per-account API credentials, adapter_id routing metadata, per-API-ID rate limiting (Resilience4j), session resume filtered by adapter. Ref: `specs/2026-05-26-tdlib-multi-account-scaling-design.md`. |
| 25 | **Group name + full sender info on flags and audits** | M | ✅ PR #85 — 2026-05-27. `TelegramMessageEvent` enriched with `senderDisplayName`, `senderUsername`, `chatTitle`. Caffeine profile caches populated from `UpdateUser`/`UpdateChatTitle` events. |
| 23 | **Flag-detail: reaction / response action** | M | **Phase 1 ✅ PR #89 — 2026-05-27.** Operator reply panel in flag detail modal: text reply to group or DM, reply-to-original, [Moderator] prefix, multi-account selection (409 flow), audit trail via `audit.events` topic. Touches intent-classifier, policy-engine, tdlib-adapter, admin-api, admin-ui. Phase 2: AI-research prompt interface — open a chat-style UI backed by one of the configured LiteLLM models so the operator can research/draft a response with AI assistance before sending. |
| 28 | **Admin UI v2: design system + Groups page** | M | ✅ PR #90 — 2026-05-28. v2 token system, font setup, restyle Button/Badge/Modal, new DataTable component, CLAUDE.md project guidance, Groups page redesign as proof-of-concept. Ref: `specs/2026-05-28-admin-ui-v2-design-system-design.md`. |
| 29 | **Admin UI v2: page redesigns** | L | ✅ All pages complete. Tenants/AuditLog/PolicyRules (PR #91) · ModerationRules/Telegram/Users/Simulate/Login/AIConfig/Flags (PR #94). New shared component: SegmentedControl. Depends on #28. |
| 30 | **Admin UI v2: remove v1 compat aliases** | XS | After all pages redesigned, remove the `/* v1 compat */` block from `variables.css`. Depends on #29. |
| 32 | **Admin UI v2: SpaceBackground v3 (Otherland Sky)** | S | Replace SpaceBackground with Claude Design handoff #2: orb/eye sigil over auto-drift particle sky + foggy skyline. Pure canvas+SVG, removes `simplex-noise` dependency. Handoff at `/home/ben/Development/tmp/design_handoff_emcip_sky`. Depends on #29. |
| 31 | **Admin UI v2: delete design handoff directory** | XS | Remove `emcip-admin-ui/design_handoff_emcip_admin/` once all page redesigns are complete and no reference material is still needed. Depends on #29. |
| 26 | **Bulk message ingestion + topic clustering + RAG knowledge base** | L | Harvest historical messages from watched groups (backfill via TDLib `getChatHistory`). Cluster into topics (BERTopic, keyBERT, or LiteLLM summarization). Build a per-group queryable knowledge base for RAG retrieval. Goal: quickly surface recurring topics and key facts. Prerequisite for item 27. |
| 27 | **Deep research operator tool** | XL | Operator-triggered autonomous research agent (wrapper around `langchain-ai/open_deep_research` or similar). Given a flagged message or topic cluster, researches via RAG + web search + LLM reasoning and produces a structured report (facts, sources, risk assessment). Feeds into flag-detail UI (item 23 Phase 2). Depends on item 26. |
| 8 | **ML toxicity detection** | XL | Replace keyword/regex moderation rules with a model-based scorer (OpenNLP, Perspective API, or local LiteLLM). Architecture decision needed before implementation. |
| 7 | **LLM cost analytics dashboard** | M | Admin UI page: per-tenant call counts + token spend. Data already in `model_cost_logs`. Ref: `specs/2026-04-24-admin-ui-phase2-design.md`. |
| 6 | **Policy versioning — complex rule logic (Epic 5.3)** | L | DB schema exists (`005-policy-rule-versioning.xml`). Time-based and context-aware rule evaluation not yet implemented. |
| 22 | **Admin UI: cross-tenant views** | M | Admin users browse data across all tenants. Requires ADMIN-mode bypass (already implemented) + UI pages. |
| 24 | **Flag-detail: clickable message links + AI content research** | S | In the flag detail modal, make URLs in the message text clickable. Add an "Investigate" action that sends the message + context to a configured LLM model for content analysis (spam signals, toxicity, intent) and displays the response inline. Lower priority than #23 and Telegram items 10/25. |

---

## 3. Infrastructure / Pre-1.0.0 Requirements

> No production users exist yet — databases can be dropped and recreated freely.
> All items in this section must be complete before any public release.
> Goal: `helm install` on a blank cluster must produce a fully working system with no manual steps.

| # | Item | Size | Notes |
|---|------|------|-------|
| INF1 | **Liquibase migration consolidation** | M | Each service has 6–10 incremental migrations from development, several with `md5sum='manual'` (root cause of the 2026-05-20 AI Config 500 incident). Squash into a single `001-initial-schema.xml` per service before 1.0.0. |
| INF4 | **Refresh token cleanup job** | XS | ✅ 2026-05-21 — nightly `deleteByExpiresAtBefore` at 03:00, `@EnableScheduling` on AdminApiApplication. |
| INF2 | **Fresh install smoke test** | S | After INF1: drop the test DB, `helm install`, verify all pages work. Document in `docs/operations/fresh-install.md`. |
| INF3 | **Telegram test account seeding via Helm values** | S | Optional: `testing.telegram.enabled` + account params → post-deploy Kubernetes Job inserts row into `telegram_accounts`. Removes manual DB access during development. |
| 21 | **Tenant provisioning / onboarding flow** | M | No way to create a tenant without direct DB access. Needs admin-api endpoint + Liquibase-safe seed flow. Blockers 1.0.0. |
| 4 | **Test coverage to 80% (JaCoCo gate)** | L | Split by service (each S/XS). Weakest: `moderation-service`, `audit-service`, `llm-orchestrator`. Phase 4 DoD requirement. |
| 14 | **Gatling load tests in CI** | S | 3 simulations exist (`IntentClassifierSimulation`, `AdminApiSimulation`, `PolicyEngineSimulation`). No CI gate yet. |
| 12 | **Kubernetes HA / multi-replica** | M | HPA templates, tuned `replicas` per service, PodDisruptionBudgets. Ref: `specs/2026-04-29-kubernetes-helm-deployment-design.md`. |

---

## 4. Deferred

> Not needed before 1.0.0. Revisit when cluster grows or a concrete use case arises.

| # | Item | Size | Notes |
|---|------|------|-------|
| 20 | **Mixed-cluster: node taints + tolerations** | S | Fine-grained pod scheduling. Currently `nodeSelector` is sufficient. Ref: `specs/2026-05-02-mixed-cluster-helm-values-design.md`. |
| SC6b | **Paginate `PolicyRuleController.listActive()`** | XS | SC6 adds a `.take(200)` safety cap. Full `PageResponse<T>` not needed — policy rules are config data and will stay small in practice. Revisit if a tenant exceeds ~100 rules. |
| 19 | **Mixed-cluster: arm64 native images** | L | Cross-compile GraalVM native for Pi 4 nodes. Needs QEMU emulation or a dedicated arm64 runner. Ref: `specs/2026-05-02-mixed-cluster-helm-values-design.md`. |
| 13 | **GraalVM native — R2DBC services** | XL | 4 services JVM-only: `moderation-service`, `audit-service`, `admin-api`, `intent-classifier`. Blocked on R2DBC + GraalVM reflection hints. Ref: `specs/2026-04-29-graalvm-native-migration-design.md`. |

---

## Documentation Audit (2026-05-16)

Diagrams audited during the LiteLLM integration pass — confirmed current, no update needed:

| Diagram | Covers |
|---|---|
| `c3-policy-engine.puml` | Policy Engine component view |
| `c3-tdlib-adapter.puml` | TDLib Adapter component view |
| `c4-policy-domain.puml` | Policy domain model |
| `sequence-error-handling.puml` | Retry, DLQ, and circuit breaker flow |
| `sequence-admin-auth.puml` | Admin UI JWT authentication flow |
| `sequence-policy-evaluation.puml` | Policy evaluation detail flow |
| `dataflow-context-enrichment.puml` | Conversation context enrichment data flow |
