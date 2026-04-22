# EMCIP Documentation Suite — Design Spec

> **For agentic workers:** Use superpowers:writing-plans to create an implementation plan from this spec before touching any files.

**Goal:** Replace the scattered MD + single architecture.adoc with four focused AsciiDoc documents that each produce a clean PDF for a distinct audience, linked by named cross-references.

**Approach:** Full rewrite (Approach A) — archive all old content, produce four new `.adoc` files that consume existing content as source material without being bound by its structure.

---

## 1. Output Documents

| File | PDF audience | Tone |
|------|-------------|------|
| `documentation/architecture-guide.adoc` | Architects, tech leads, new team members | Technical, decision-oriented |
| `documentation/developer-guide.adoc` | Engineers building on or extending EMCIP | Practical, task-oriented |
| `documentation/operations-guide.adoc` | DevOps, SREs, anyone running the platform | Procedural, reference |
| `documentation/user-guide.adoc` | Platform admins (Part I UI) + API developers (Part II REST) | Tutorial + reference |

Each `.adoc` file is built independently by the asciidoctor-maven-plugin and produces one PDF. No shared master file.

---

## 2. Architecture Guide — `architecture-guide.adoc`

```
= EMCIP Architecture Guide
:toc:
:toclevels: 3
:sectnums:
```

### Sections

1. **Introduction** — What EMCIP does. The four operating modes: React, Summarize, Moderate, Observe. TDLib-first design (real user, not bot). Cross-reference to Developer Guide for setup.

2. **System Context (C1)** — `include::diagrams/c1-context.puml[]`. External actors: Platform Admin, Telegram User. External systems: Telegram API (TDLib), LLM Providers.

3. **Container Architecture (C2)** — `include::diagrams/c2-container.puml[]`. All 8 microservices, PostgreSQL, Kafka, infrastructure services.

4. **Component Details (C3)** — Four component diagrams:
   - Overview: `include::diagrams/c3-component.puml[]`
   - TDLib Adapter: `include::diagrams/c3-tdlib-adapter.puml[]`
   - Policy Engine: `include::diagrams/c3-policy-engine.puml[]`
   - LLM Orchestrator: `include::diagrams/c3-llm-orchestrator.puml[]`
   - Admin API: `include::diagrams/c3-admin-api.puml[]` _(new diagram)_

5. **Domain & Code Model (C4)** — `include::diagrams/c4-code.puml[]` + `include::diagrams/c4-policy-domain.puml[]`.

6. **Event Architecture** — Three diagrams in sequence:
   - Message flow intro: `include::diagrams/sequence-message-flow.puml[]`
   - Event topology: `include::diagrams/c4-event-flow.puml[]`
   - Kafka consumers: `include::diagrams/c4-kafka-consumers.puml[]`
   - Kafka topic reference table (all 8 topics, producer, consumer, schema summary)

7. **Data Flows** — `include::diagrams/dataflow-audit-trail.puml[]` + `include::diagrams/dataflow-context-enrichment.puml[]`.

8. **Technology Stack & Guardrails** — Java 21 / Spring Boot 4 / Maven. JPA vs R2DBC split and rationale. Liquibase-only (no Flyway). Spotless / Google Java Style. Lombok conventions.

9. **Security Architecture** — JWT auth chain. Tenant isolation: `include::diagrams/sequence-tenant-propagation.puml[]` _(new diagram)_. X-Tenant-Id header propagation through HTTP → Kafka → DB. Service-to-service token.

10. **Appendix A–D: Architecture Decision Records** — Full content of ADR-001 through ADR-004 included inline.

### Cross-references out
- NOTE box at end of §6: "For sequence-level pipeline detail, see the _Developer Guide_."
- NOTE box at end of §9: "For deployment and port configuration, see the _Operations Guide_."

---

## 3. Developer Guide — `developer-guide.adoc`

```
= EMCIP Developer Guide
:toc:
:toclevels: 3
:sectnums:
```

### Sections

1. **Quick Start** — Prerequisites (Java 21, Docker, Maven). Clone → `mvn install -DskipTests` → `docker compose up -d postgres kafka zookeeper` → run a service. Cross-reference to Operations Guide for full infrastructure.

2. **Module Structure** — The 9 Maven modules: `emcip-core` (shared), 7 services, `gatling-tests`. What each module owns (no overlap).

3. **Development Patterns** — Four sub-sections:
   - JPA entities: UUID IDs, `@Version`, `@Column(nullable=false)`, Lombok
   - Kafka: `CommonKafkaConfig`, `TenantAwareKafkaSupport`, DLQ, retry
   - Liquibase: changeset naming convention, never Flyway
   - Spotless: `mvn spotless:apply` before every commit, expected output

4. **Kafka Topics Reference** — Table: topic name, schema, producer service, consumer service(s). Source: EVENT_SCHEMAS.md.

5. **Service APIs** — Health endpoints (`/actuator/health`), Prometheus (`/actuator/prometheus`), Admin API REST endpoints summary.

6. **Testing** — Testcontainers setup (`TestcontainersInitializer`). Unit test patterns (Mockito). Backup/restore integration test (`BackupRestoreIT`, gated by `ECIP_IT_ENABLED`). Gatling load tests (`cd gatling-tests && mvn gatling:test`).

7. **TDLib Integration** — Why TDLib (real user, not bot). Docker build (compiles from source, ~15–30 min, cached). Auth flow (`TELEGRAM_API_ID`, `TELEGRAM_API_HASH`, phone number). Troubleshooting `UnsatisfiedLinkError`.

8. **Message Pipeline Walkthrough** — Three sequence diagrams with explanatory prose:
   - `include::diagrams/sequence-full-message-lifecycle.puml[]`
   - `include::diagrams/sequence-policy-evaluation.puml[]`
   - `include::diagrams/sequence-llm-orchestration.puml[]`

9. **Contributing** — Branch strategy (feature branch → PR → main). Commit format (`feat(scope):`, `fix(scope):`). Spotless gate in CI. One commit per user story minimum.

### Cross-references out
- NOTE box at top of §2: "For C2/C3 architectural diagrams, see the _Architecture Guide_."
- NOTE box at end of §1: "For port configuration and Docker Compose profiles, see the _Operations Guide_."

---

## 4. Operations Guide — `operations-guide.adoc`

```
= EMCIP Operations Guide
:toc:
:toclevels: 3
:sectnums:
```

### Sections

1. **Infrastructure Overview** — `include::diagrams/deployment-local-docker.puml[]`. Services map: 8 application services + 9 infrastructure services (Zookeeper, Kafka, Kafka UI, PostgreSQL, pgAdmin, Grafana, Loki, Promtail, Admin UI).

2. **Docker Compose Quickstart** — Default profile (infrastructure + always-on services). Named profiles: `full` (all app services), `llm` (LLM orchestrator, requires `ANTHROPIC_API_KEY`), `telegram` (TDLib adapter, requires Telegram credentials). `.env` file setup.

3. **Port Reference** — Complete table of all 17 ports. Application services 9080–9087. Infrastructure 14001–14009. Source: PORT_CONFIGURATION.md.

4. **Observability** — Grafana (port 14007, admin/admin). Three pre-built dashboards: Service Health, Kafka Consumer Lag, Audit Throughput. Loki (port 14008) — LogQL query examples. Prometheus metrics — key metrics per service. Structured JSON log fields (`@timestamp`, `level`, `logger_name`, `message` — Spring Boot native format).

5. **Backup & Restore** — `scripts/db/backup.sh` and `scripts/db/restore.sh`. Environment variables (`DB_HOST`, `DB_PORT=14005`, `DB_NAME`, `DB_USER`, `PGPASSWORD`). Step-by-step restore procedure. Verification (row count check). Source: backup-restore-runbook.md.

6. **Performance Tuning** — HikariCP: `maximumPoolSize=20`, `minimumIdle=5` (policy-engine). Kafka consumer: `max-poll-records=500` (intent-classifier). JFR profiling: `java -XX:StartFlightRecording=...`. Load test execution: `cd gatling-tests && mvn gatling:test`. SLO table (p95 intent <200ms, p95 policy <100ms, p99 end-to-end <2s, throughput 500 msg/s). Source: performance-benchmarks.md.

7. **Error Handling & DLQ** — `include::diagrams/sequence-error-handling.puml[]`. Retry configuration (exponential backoff via `CommonKafkaConfig`). DLQ topic naming convention. `DeadLetterQueueConsumer` monitoring. Retryable vs non-retryable exceptions.

8. **Moderation Rules** — Rule types: `KEYWORD`, `REGEX`, `LENGTH`. Configuring via Admin API (`POST /api/admin/moderation-rules`). Cache refresh interval (5 min). Source: MODERATION_RULES.md.

9. **Troubleshooting** — Common failure table: port conflicts → `lsof` commands. Kafka connectivity → `KAFKA_BOOTSTRAP_SERVERS`. PostgreSQL connection → Liquibase migration failures. TDLib auth → `TELEGRAM_PHONE_NUMBER` env var. Logback startup errors → ensure `logstash-logback-encoder` is not on classpath.

### Cross-references out
- NOTE box at top of §1: "For the architectural context of these services, see the _Architecture Guide_."

---

## 5. User Guide — `user-guide.adoc`

```
= EMCIP User Guide
:toc:
:toclevels: 3
:sectnums:
```

### Part I — Admin UI (http://localhost:14009)

1. **Login** — Navigate to `http://localhost:14009`. Default credentials from `ADMIN_JWT_SECRET` env var. `include::diagrams/sequence-admin-auth.puml[]` _(new diagram)_. Session is JWT stored in-memory (not localStorage — lost on page reload).

2. **Tenant Management** — What a tenant is (row-level data isolation per community). Create tenant (name field). Delete tenant (cascades data — irreversible warning). List view with tenant IDs.

3. **Policy Rules** — What a policy rule does (intent → action mapping). Creating a new version (auto-deactivates previous). Rule actions: WARN, MUTE, BAN, ALLOW. Time-based rules (active only within configured hours). Context-aware rules (fires on thread length or speaker role). Version history modal.

4. **Audit Log** — Paginated event viewer. Event types (MESSAGE_RECEIVED, POLICY_DECISION, LLM_RESPONSE, MODERATION_FLAG). Filter by type and date. Payload JSON inspection.

### Part II — REST API Reference

5. **Authentication** — `POST /api/auth/token` (body: `{username, password}`, returns `{token}`). All subsequent requests: `Authorization: Bearer <token>`. Token expiry and renewal.

6. **Tenants API** — `GET /api/tenants`, `POST /api/tenants` (body: `{name}`), `DELETE /api/tenants/{id}`. Request/response schemas with examples.

7. **Policy Rules API** — `GET /api/policy-rules` (active rules), `POST /api/policy-rules` (new version), `GET /api/policy-rules/{name}/history`. Schema: `name`, `action`, `priority`, `active`, `ruleVersion`, `effectiveFrom`, `effectiveTo`.

8. **Audit Events API** — `GET /api/audit/events?size=50&page=0`, `GET /api/audit/events/{id}`, `GET /api/audit/summary`. Pagination response envelope.

9. **Moderation Rules API** — `GET /api/admin/moderation-rules`, `POST /api/admin/moderation-rules`, `DELETE /api/admin/moderation-rules/{id}`. Rule schema: `ruleType`, `pattern`, `action`, `enabled`.

10. **Health & Metrics** — `GET /actuator/health` (all services, port 9080–9087). `GET /actuator/prometheus` (Prometheus scrape endpoint). Common health indicator fields.

### Cross-references out
- NOTE box at start of Part II §5: "For the full security architecture including JWT internals, see the _Architecture Guide_."

---

## 6. New PUML Diagrams to Create

### `c3-admin-api.puml`
C3 component diagram of the Admin API service. Components: `JwtAuthenticationFilter`, `ServiceTokenAuthenticationFilter`, `SecurityConfig`, `AuthController`, `TenantController`, `PolicyRuleController` (with versioning logic), `AuditController`, `TenantWebFilter` (WebFlux WebFilter). Shows request flow: HTTP → filters → controllers → R2DBC repositories → PostgreSQL.

### `sequence-tenant-propagation.puml`
Sequence diagram showing `X-Tenant-Id` propagation. Participants: HTTP Client, `TenantWebFilter`, `TenantContext` (ThreadLocal), service method, JPA query, `TenantAwareKafkaSupport`, Kafka producer, Kafka consumer, downstream service method. Shows both HTTP path and Kafka path.

### `sequence-admin-auth.puml`
Sequence diagram for Admin UI authentication. Participants: Browser (Admin UI), `AuthController`, `AuthService`, `AdminUserRepository`, `JwtService`. Flow: POST credentials → load user → bcrypt verify → sign JWT → return token → subsequent request with Bearer header → `JwtAuthenticationFilter` validates → proceeds.

---

## 7. PUML Fixes Required

Two existing diagrams have a stray `class` keyword after the diagram name — this switches PlantUML to class-diagram rendering mode and must be removed:

- `documentation/diagrams/dataflow-context-enrichment.puml` line 1:
  `@startuml DataFlow_Context_Enrichment class` → `@startuml DataFlow_Context_Enrichment`
- `documentation/diagrams/sequence-llm-orchestration.puml` line 1:
  `@startuml Sequence_LLM_Orchestration class` → `@startuml Sequence_LLM_Orchestration`

---

## 8. Archive Plan

Use `git mv` to preserve history.

**Move to `documentation/archive/old-architecture/`:**
- `documentation/architecture.adoc`
- `documentation/architecture.pdf`

**Move to `documentation/archive/old-docs/`:**
- `documentation/DECISIONS_SUMMARY.md`
- `documentation/OPEN_QUESTIONS.md`
- `documentation/SOUL.md`
- `documentation/TEST_MATRIX.md`
- `documentation/CREATE_APPLICATION.md`
- `documentation/concept/DOMAIN_CONCEPT.md`
- `documentation/developer-idea/` (all 5 files)
- `documentation/schema/AUDIT_SCHEMA.md`
- `documentation/developer/backup-restore-runbook.md`
- `documentation/developer/MODERATION_RULES.md`
- `documentation/developer/performance-benchmarks.md`

**Move to `documentation/archive/old-root-docs/`:**
- `CODE_QUALITY.md`
- `CONTRIBUTING.md`
- `DOCUMENTATION-REFACTORING.md`
- `EVENT_SCHEMAS.md`
- `HEALTH_ENDPOINTS.md`
- `INFRASTRUCTURE.md`
- `LOGGING.md`
- `ONBOARDING.md`
- `PORT_CONFIGURATION.md`
- `TDLIB_SETUP.md`

**Not archived:**
- `README.md` (root, GitHub-facing)
- `documentation/diagrams/` (PUML source of truth)
- `documentation/adrs/` (referenced as architecture-guide appendices)
- `documentation/planning/` (historical reference)

---

## 9. Maven Plugin Assumption

The existing `asciidoctor-maven-plugin` in `pom.xml` either scans the `documentation/` directory (auto-picks up new `.adoc` files) or is hardcoded to `architecture.adoc`. If hardcoded, add the 3 new source file entries to the plugin configuration. Verify before writing new documents.

---

## 10. Stashed PUML Files

The new PUML diagrams (13 files) are currently in a git stash on `main`. They must be popped and committed before or during the implementation.

Run: `git stash pop` then commit all new/modified PUML files.
