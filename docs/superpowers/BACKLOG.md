# EMCIP Backlog

> Last updated: 2026-06-23 (Epic #6 policy rule versioning complete)
> Single source of truth for all open work. Completed items are in §5.
> Size guide: **XS** < 2h · **S** ½ day · **M** 1–2 days · **L** 3–5 days · **XL** > 1 week
> Dependency key: items are ordered so prerequisites appear before dependents. "Needs" column lists hard blockers.

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

> Ordered by dependency: items are ready to pick up unless "Needs" says otherwise. Pick the top unblocked item.

| # | Item | Size | Needs | Notes |
|---|------|------|-------|-------|
| 8 | **ML toxicity detection** | XL | Architecture decision | Replace keyword/regex with model-based scorer (OpenNLP, Perspective API, or local LiteLLM). Architecture decision needed first. |

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
| 26 | Knowledge Foundation — US-26.1 extensions, US-26.2 service bootstrap, US-26.3 ontology model | ✅ PR #122/#123 — 2026-06-13. US-26.4–26.10 all complete (see below). Spec: `specs/2026-06-13-knowledge-management-platform-design.md` |
| SC6b | Audit-log page: filters + pagination | ✅ 2026-06-14. Spec: `specs/2026-06-14-audit-log-filters-pagination-design.md` |
| 40 | SC8 resilience follow-ons: retry + read fallbacks | ✅ 2026-06-14. Spec: `specs/2026-06-14-sc8-resilience-follow-ons-design.md` |
| 24 | Flag-detail: AI analysis end-to-end fix | ✅ 2026-06-15. Spec: `specs/2026-06-14-ai-analysis-e2e-design.md` |
| 23 | Flag-detail: AI Research chat (Phase 2) | ✅ 2026-06-15. Spec: `specs/2026-06-15-ai-research-chat-design.md` |
| 7 | LLM cost analytics dashboard | ✅ 2026-06-15. Spec: `specs/2026-06-15-llm-cost-analytics-design.md` |
| 41b | Decisions reply composer v2 — 4-mode SegmentedControl, chip-row, char counter, NOTE backend | ✅ PR #130 — 2026-06-16. Spec: `specs/2026-06-15-reply-composer-v2-design.md` |
| 26.4 | Knowledge extraction pipeline — DLQ, metadata preservation, ontology-driven prompt, result validation | ✅ PR #132 — 2026-06-16. Spec: `specs/2026-06-16-knowledge-extraction-pipeline-design.md` |
| 26.5 | Entity resolution — embedding similarity (merge ≥ 0.92, flag ≥ 0.80), `ke_resolution_flags` queue | ✅ PR #133 — 2026-06-17. Spec: `specs/2026-06-16-entity-resolution-design.md` |
| 43 | Entity resolution review UI — Resolution Queue page, merge/dismiss with ConfirmDialog | ✅ PR #134 — 2026-06-17. Spec: `specs/2026-06-17-entity-resolution-review-ui-design.md` |
| 41a | Simulate page: two-column pipeline trace + correlationId fix in AuditEventConsumer | ✅ PR #135 — 2026-06-18. Spec: `specs/2026-06-17-simulate-pipeline-trace-design.md` |
| 26.6 | Live message fork — per-group `knowledgeForkEnabled` flag, conditional knowledge.raw.messages publish | ✅ PR #137 — 2026-06-18. Plan: `plans/2026-06-18-live-message-fork.md` |
| 26.7 | Bulk backfill — operator-triggered historical backfill, BackfillModal, per-group progress polling | ✅ PR #139 — 2026-06-18. Spec: `specs/2026-06-18-bulk-backfill-design.md` |
| 26.8 | Document ingestion (factual knowledge) — URL fetch + file upload (Tika), async jobs, Admin-UI Knowledge page + IngestionModal | ✅ PR #140 — 2026-06-19. Spec: `specs/2026-06-18-document-ingestion-design.md` |
| 26.9 | Knowledge query API — semantic search, graph exploration (topics, persons, neighbors), hybrid search, KnowledgePage Search tab | ✅ 2026-06-20 — implemented within PR #142 (Epic 42 branch). |
| 42 | Structured feed connectors — 13 connectors (Wikipedia, arXiv, PubMed, Wikidata, OpenAlex, SemanticScholar, CORE, DOAJ, Zenodo, Unpaywall, BioRxiv, Brave, Exa), EnrichmentConnectorRegistry, EnrichmentScheduler | ✅ PR #142 — 2026-06-20. |
| 26.10 | Knowledge enrichment for LLM responses — KnowledgeEngineClient, KnowledgeContextEnricherService wired into LlmCallService; feature-flagged via KNOWLEDGE_ENRICHMENT_ENABLED | ✅ PR #143 — 2026-06-20. Plan: `plans/2026-06-20-knowledge-enrichment-llm.md` |
| 41c | Users: expanded roles (MODERATOR, ANALYST, VIEWER) + lastLogin column + tenant selector for all non-ADMIN roles | ✅ 2026-06-22. Plan: `plans/2026-06-22-users-expanded-roles.md` |
| 27B | Deep Research Agent — web search (SearXNG connector, Brave fallback, US-27.3) + structured report generation (LLM synthesis, ke_research_reports, US-27.5) | ✅ PR branch — 2026-06-22. Plan: `plans/2026-06-22-deep-research-agent-plan-b.md` |
| 27C | Deep Research Agent — Admin UI: session list, Start Research modal, live polling, evidence table, report viewer (Markdown renderer + download), comparison view (US-27.6, 27.7, 27.8) | ✅ PR branch — 2026-06-22. Plan: `plans/2026-06-22-deep-research-agent-plan-c.md` |
| 27A | Deep Research Agent — backend: session lifecycle, strategy engine, execution loop, evidence with provenance, cost/depth guardrails (US-27.1, 27.2, 27.4, 27.9) | ✅ PR #145 — 2026-06-21. Plan: `plans/2026-06-20-deep-research-agent-plan-a.md` |
| 6 | Policy rule versioning — condition groups (AND/OR), 7 evaluator types, rule history snapshots, dry-run endpoint, admin-api proxy | ✅ 2026-06-23. Branch: `feat/42-knowledge-enrichment-connectors`. |

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
