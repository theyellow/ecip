# EMCIP Open Points

> Last updated: 2026-05-13
> Current phase: **Phase 4 — Observability, Moderation & Audit**

---

## Phase 4 — Observability, Moderation & Audit

### Epic 4.1: Moderation Service

| Status | Item |
|--------|------|
| ❌ | **US-4.1.1** — No toxicity detection beyond simple keyword/regex/length rules. `RuleEvaluationService` is functional but primitive. No integration with OpenNLP, Perspective API, or ML-based scoring. |
| ❌ | **US-4.1.4** — No documentation for moderation rules and escalation paths beyond the user story doc. |
| ❌ | **US-4.1.5** — Only 2 test files (`RuleEvaluationServiceTest`, `ModerationEventConsumerTest`). No integration test with a real Kafka/DB. |
| ✅ | **Missing REST controller** — `ModerationRuleController` added to moderation-service at `/api/moderation-rules` (CRUD). admin-api proxies it via `ModerationServiceClient`. The shared-DB approach was incorrect; each service now owns its tables. *(Fixed 2026-05-13)* |

### Epic 4.2: Observability

| Status | Item |
|--------|------|
| ✅ | **US-4.2.3** — Prometheus added to docker-compose (port 14010) + Helm. All 8 services expose `/actuator/prometheus`. *(PR #37)* |
| ✅ | **US-4.2.4** — Grafana JVM & HTTP metrics dashboard provisioned in both docker-compose and Helm. *(PR #37)* |
| ❌ | **US-4.2.2 (OpenTelemetry tracing)** — `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-logging` in 3 services only (admin-api, audit-service, moderation-service). All export to **logs only** — no OTLP/Jaeger/Tempo backend. No OTel collector in docker-compose or Helm. 6 services have no OTel dep at all. |
| ❌ | **US-4.2.5** — No integration tests for logging or metrics. |
| ✅ | **OTel gap in 6 services** — Added `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-logging` to all 5 runnable services (`conversation-context`, `intent-classifier`, `policy-engine`, `llm-orchestrator`, `tdlib-adapter`). `emcip-core` is a library and needs no OTel dep. *(Fixed 2026-05-13)* |

### Epic 4.3: Admin API Security

| Status | Item |
|--------|------|
| ✅ | **US-4.3.3 (service-to-service auth)** — Full inter-service auth implemented. Domain services (moderation-service, policy-engine, audit-service) each have a `ServiceTokenFilter` protecting `/api/**`. admin-api has `WebClient` proxy clients (`ModerationServiceClient`, `PolicyEngineClient`, `AuditServiceClient`) that send `X-Service-Token` on every call. Foreign JPA entities/repos removed from admin-api. SecurityConfig `.permitAll()` workaround removed. *(Fixed 2026-05-13)* |
| ❌ | **US-4.3.4** — No OpenAPI/Swagger documentation generated or exposed in any service. |
| ❌ | **US-4.3.5** — Only 3 test files in admin-api (`JwtServiceTest`, `SecurityFilterChainTest`, `TelegramAccountControllerTest`). No test coverage for most controllers (AuditController, FlagController, GroupProfileController, ModerationRuleController, PolicyRuleController, SimulateController, TenantController, AIProxyController). |

### Epic 4.4: Audit / Event Log

| Status | Item |
|--------|------|
| ✅ | **US-4.4.2 (retention policies)** — Reviewed 2026-05-13. `RetentionService` is correct: `@EnableScheduling` present, `created_at` column matches entity, `retentionDays` configurable (default 90). Fire-and-forget `.subscribe()` with `.doOnError()` logging is adequate for a scheduled purge. No changes needed. |
| ❌ | **US-4.4.4** — No schema documentation (ER diagrams, data dictionary) for audit/event log tables. |
| ❌ | **US-4.4.5** — No integration tests for log persistence or retention. |

---

## Phase 3 — Partially Done Items

| Status | Item |
|--------|------|
| ✅ | **Intent classifier — 0 tests** — Added `IntentClassificationServiceTest` (8 unit tests covering GREETING/QUESTION/COMMAND/SPAM/UNKNOWN/multi-match/Kafka publish/metadata). *(Fixed 2026-05-13)* |
| ❌ | **LLM orchestrator — sparse tests** — Only 1 test file (`LlmCallServiceTest`). No test for prompt template versioning, model routing fallback, or cost tracking logic. |

---

## Admin UI Gaps

| Status | Item |
|--------|------|
| ❌ | `Tenants.jsx` has no `Tenants.test.jsx` |
| ❌ | `ModerationRules.jsx` has no test file |
| ❌ | `AuditLog.jsx` has no test file |
| ❌ | `AIProxyController` (backend) has no test |

---

## Bigger Projects (Phase 5+)

| Priority | Project | Notes |
|----------|---------|-------|
| **High** | **OpenTelemetry distributed tracing** | Instrument all 8 services consistently. Wire to a real backend (Tempo in Grafana stack, or Jaeger). Currently only logging exporter, 3 services only. |
| **High** | **Test coverage uplift** | `intent-classifier` (0 tests), `admin-api` (sparse), `audit-service` (3 files), `moderation-service` (2 files). Phase 4 DoD requires 80% JaCoCo. |
| **Medium** | **Policy versioning (Epic 5.3)** | DB schema exists (`005-policy-rule-versioning.xml`) but complex rule logic (time-based, context-aware rules) not implemented. |
| **Medium** | **Multi-tenancy hardening (Epic 5.1)** | `tenant_id` columns added to all tables, `TenantContextFilter` exists in core, but no tenant isolation enforcement at the JPA query level. |
| **Medium** | **GraalVM native for R2DBC services** | 4 services deferred: `moderation-service`, `audit-service`, `admin-api`, `intent-classifier`. JVM images only. Prerequisite: R2DBC + GraalVM reflection hints investigation. |
| **Medium** | **Kubernetes TLS (cert-manager)** | Ingress deployed without TLS. Needs cert-manager ClusterIssuer + TLS stanza in `helm/emcip/templates/ingress.yaml`. |
| **Low** | **Swagger / OpenAPI docs** | No generated API docs exposed in any service. SpringDoc OpenAPI dep needed. |
| **Low** | **CI/CD: image vulnerability scanning** | Add Trivy or Anchore scan step to `build-images.yml` after each `docker/build-push-action`. |
| **Low** | **CI/CD: GraalVM build caching** | Native image builds take 15–20 min each. Add `cache-from`/`cache-to` in `docker/build-push-action` to speed up repeat CI builds. |
| **Low** | **CI/CD: staging vs. production image tags** | All builds currently go to the same tag. A promotion strategy (`:staging` on merge, `:latest` on release) is not yet implemented. |
| **Low** | **Telegram: concurrent multi-account sessions** | Only one Telegram account active at a time. True concurrent sessions require architectural work in `tdlib-adapter`. |
| **Low** | **LLM cost analytics dashboard** | Admin UI page for per-tenant LLM call counts and token spend. |
| **Low** | **Gatling load tests in CI** | 3 simulations exist (`IntentClassifierSimulation`, `AdminApiSimulation`, `PolicyEngineSimulation`) but no CI integration and no performance regression gate. |
| **Low** | **Multi-arch images (arm64)** | JVM images are amd64 only. `eclipse-temurin:21-jdk` is multi-arch — `buildx` could produce arm64 manifests for the Pi mixed-cluster. |

---

## Known Pre-existing Issues

| Severity | Issue |
|----------|-------|
| ✅ Fixed | **Jackson 2→3 migration gap in `emcip-policy-engine`** — Stale `emcip-core` JAR in local Maven repo (compiled before Jackson 3 migration). Fixed 2026-05-13 by reinstalling `emcip-core`. All 43 tests pass. |

---

## Already in the Backlog (`docs/superpowers/BACKLOG.md`)

These are tracked separately and not duplicated here:
- GraalVM native for R2DBC services (detailed notes)
- Kubernetes HA / multi-replica (HPA, PDB)
- Kubernetes TLS via cert-manager
- Mixed-cluster arm64 native images
- CI/CD multi-arch JVM images, vulnerability scanning, GraalVM caching, staging tags
- Telegram concurrent multi-account sessions
- Telegram self-service account connection
- Admin UI LLM cost analytics dashboard
