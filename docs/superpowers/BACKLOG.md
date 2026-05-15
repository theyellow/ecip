# EMCIP Backlog

> Last updated: 2026-05-15
> Single source of truth for all open work.
> Size guide: **XS** < 2h · **S** ½ day · **M** 1–2 days · **L** 3–5 days · **XL** > 1 week

Items are ordered by priority. Phase 4 (current) items come first.

---

## Now — Phase 4 Completion

| # | Item | Size | Notes |
|---|------|------|-------|
| 1 | ~~**US-4.2.2 — OTel tracing backend**~~ | ~~M~~ | ✅ Done 2026-05-15. All 8 services export OTLP/HTTP to Grafana Tempo. W3C `traceparent` propagated via Kafka headers. Tempo in docker-compose (port 14011) + Helm. |
| 2 | ~~**US-4.2.5 — Observability tests**~~ | ~~S~~ | ✅ Done 2026-05-15. `PrometheusScrapingIT` + `TraceContextPropagationIT` added to moderation-service. |
| 3 | ~~**US-4.1.5 — Moderation integration tests**~~ | ~~S~~ | ✅ Done 2026-05-15. `ModerationFlowIT`: Testcontainers Kafka + PostgreSQL, publishes `policies.decisions`, asserts flag persisted. |
| 4 | ~~**US-4.4.5 — Audit integration tests**~~ | ~~S~~ | ✅ Done 2026-05-15. `AuditEventPersistenceIT` + `RetentionServiceIT`. Fixed `AuditEventEntity.details` String→`Json` (JSONB binding bug). |
| 5 | **US-4.3.4 — OpenAPI / Swagger** | M | Add `springdoc-openapi` to all services, expose `/swagger-ui.html`. |
| 6 | **US-4.1.4 — Moderation operator docs** | S | Rule type descriptions, escalation path runbook, no-code. |
| 7 | **US-4.4.4 — Audit schema docs** | S | ER diagram + data dictionary for audit/event log tables. |

---

## Next — Phase 5 Feature Work

| # | Item | Size | Notes |
|---|------|------|-------|
| 8 | **Test coverage to 80% (JaCoCo gate)** | L | Gate not yet enforced. Weakest services: `moderation-service` (2 files), `audit-service` (3 files). Phase 4 DoD requirement. |
| 9 | **Multi-tenancy enforcement at JPA level** | L | `tenant_id` columns + `TenantContextFilter` exist but no query-level isolation. Cross-tenant data leak is currently possible. |
| 10 | **Policy versioning — complex rule logic (Epic 5.3)** | L | DB schema exists (`005-policy-rule-versioning.xml`). Time-based and context-aware rule evaluation not implemented. |
| 11 | **LLM cost analytics dashboard** | M | Admin UI page: per-tenant call counts + token spend. Ref: `specs/2026-04-24-admin-ui-phase2-design.md`. |
| 12 | **US-4.1.1 — ML toxicity detection** | XL | Replace keyword/regex rules with OpenNLP or Perspective API scoring. Architecture decision needed before implementation. |
| 13 | **Telegram: self-service account connection** | L | Allow end-users (not just admins) to link Telegram accounts via phone → OTP flow. Ref: `specs/2026-04-26-telegram-multi-account-auth-design.md`. |
| 14 | **Telegram: concurrent multi-account sessions** | XL | Only one Telegram account active at a time. True concurrency needs `tdlib-adapter` architectural rework. Ref: `specs/2026-04-26-telegram-multi-account-auth-design.md`. |

---

## Infrastructure

| # | Item | Size | Notes |
|---|------|------|-------|
| 15 | **Kubernetes TLS (cert-manager)** | S | cert-manager `ClusterIssuer` + TLS stanza in `helm/emcip/templates/ingress.yaml`. Ref: `specs/2026-04-29-kubernetes-helm-deployment-design.md`. |
| 16 | **Kubernetes HA / multi-replica** | M | HPA templates, tuned `replicas` per service tier, PodDisruptionBudgets for critical services. Ref: `specs/2026-04-29-kubernetes-helm-deployment-design.md`. |
| 17 | **GraalVM native — R2DBC services** | XL | 4 services JVM-only: `moderation-service`, `audit-service`, `admin-api`, `intent-classifier`. Blocked on R2DBC + GraalVM reflection hints investigation. Ref: `specs/2026-04-29-graalvm-native-migration-design.md`. |
| 18 | **Gatling load tests in CI** | S | 3 simulations exist (`IntentClassifierSimulation`, `AdminApiSimulation`, `PolicyEngineSimulation`). No CI integration, no regression gate. |
| 19 | **CI/CD: image vulnerability scanning** | S | Trivy or Anchore scan step after each `build-push-action` in `build-images.yml`. Ref: `specs/2026-05-03-github-actions-image-publishing-design.md`. |
| 20 | **CI/CD: GraalVM build caching** | S | `cache-from`/`cache-to` in `build-push-action`. Saves ~15–20 min per native build. Ref: `specs/2026-05-03-github-actions-image-publishing-design.md`. |
| 21 | **CI/CD: staging vs. production image tags** | S | Push `:staging` on merge to `main`, promote to `:latest` on release tag. Ref: `specs/2026-05-03-github-actions-image-publishing-design.md`. |
| 22 | **Multi-arch JVM images (linux/arm64)** | S | `docker buildx` with `eclipse-temurin:21-jdk` (already a multi-arch base image). Ref: `specs/2026-05-03-github-actions-image-publishing-design.md`. |
| 23 | **Mixed-cluster: arm64 native images** | L | Cross-compile GraalVM native for Pi 4 nodes. Needs QEMU emulation or a dedicated arm64 runner. Ref: `specs/2026-05-02-mixed-cluster-helm-values-design.md`. |
| 24 | **Mixed-cluster: node taints + tolerations** | S | Fine-grained pod scheduling if node pools grow. Currently `nodeSelector` is sufficient. Ref: `specs/2026-05-02-mixed-cluster-helm-values-design.md`. |
