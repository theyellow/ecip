# EMCIP Documentation Suite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace scattered Markdown files and one monolithic `architecture.adoc` with four focused AsciiDoc documents that each produce a clean PDF for a distinct audience, linked by named cross-references.

**Architecture:** Full rewrite (Approach A) — archive all old content via `git mv`, produce four new `.adoc` files consuming existing content as source material. Each `.adoc` is built independently by `asciidoctor-maven-plugin` and produces one PDF. Three new PlantUML diagrams fill identified gaps before any AsciiDoc writing begins.

**Tech Stack:** AsciiDoc, asciidoctor-maven-plugin 3.0.0, asciidoctorj-pdf 2.3.18, asciidoctorj-diagram 2.3.1, PlantUML (via C4-PlantUML stdlib).

---

## File Map

| Action | Path | Purpose |
|--------|------|---------|
| Modify | `documentation/diagrams/dataflow-context-enrichment.puml` | Fix stray `class` keyword on line 1 |
| Modify | `documentation/diagrams/sequence-llm-orchestration.puml` | Fix stray `class` keyword on line 1 |
| Create | `documentation/diagrams/c3-admin-api.puml` | C3 component diagram for Admin API |
| Create | `documentation/diagrams/sequence-tenant-propagation.puml` | Sequence: X-Tenant-Id propagation |
| Create | `documentation/diagrams/sequence-admin-auth.puml` | Sequence: Admin UI authentication |
| Modify | `pom.xml` | Add asciidoctor-maven-plugin (no existing config) |
| git mv | `documentation/architecture.adoc` → `documentation/archive/old-architecture/` | Archive old monolith |
| git mv | `documentation/architecture.pdf` → `documentation/archive/old-architecture/` | Archive generated PDF |
| git mv | 10 root `.md` files → `documentation/archive/old-root-docs/` | Archive root docs |
| git mv | 9 `documentation/**/*.md` files → `documentation/archive/old-docs/` | Archive internal docs |
| Create | `documentation/architecture-guide.adoc` | Architecture Guide PDF |
| Create | `documentation/developer-guide.adoc` | Developer Guide PDF |
| Create | `documentation/operations-guide.adoc` | Operations Guide PDF |
| Create | `documentation/user-guide.adoc` | User Guide PDF |

---

## Task 1: Pop stash and fix two PUML bugs

**Files:**
- Modify: `documentation/diagrams/dataflow-context-enrichment.puml` line 1
- Modify: `documentation/diagrams/sequence-llm-orchestration.puml` line 1

- [ ] **Step 1: Pop the stash containing updated PUML files**

```bash
git stash pop
```

Expected output:
```
On branch chore/claude-config-and-docs
Changes not staged for commit:
  modified:   documentation/diagrams/c1-context.puml
  modified:   documentation/diagrams/c2-container.puml
```

- [ ] **Step 2: Commit the updated PUML files from stash**

```bash
git add documentation/diagrams/c1-context.puml documentation/diagrams/c2-container.puml
git commit -m "docs(diagrams): update C1 context and C2 container diagrams"
```

- [ ] **Step 3: Fix `dataflow-context-enrichment.puml` line 1**

Change line 1 from:
```
@startuml DataFlow_Context_Enrichment class
```
To:
```
@startuml DataFlow_Context_Enrichment
```

- [ ] **Step 4: Fix `sequence-llm-orchestration.puml` line 1**

Change line 1 from:
```
@startuml Sequence_LLM_Orchestration class
```
To:
```
@startuml Sequence_LLM_Orchestration
```

- [ ] **Step 5: Commit PUML fixes**

```bash
git add documentation/diagrams/dataflow-context-enrichment.puml \
        documentation/diagrams/sequence-llm-orchestration.puml
git commit -m "fix(diagrams): remove stray 'class' keyword that breaks PlantUML rendering"
```

---

## Task 2: Create `c3-admin-api.puml`

**Files:**
- Create: `documentation/diagrams/c3-admin-api.puml`

- [ ] **Step 1: Create the file with this exact content**

```
@startuml C3_Admin_API
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Component.puml

' Purpose: Component-level view of Admin API — auth, tenant, policy rule, audit controllers
' Used in: architecture-guide.adoc - Component Details section

LAYOUT_WITH_LEGEND()

Container_Boundary(admin_api, "Admin API - Port 9087") {

    ' Security Layer
    Component(jwt_filter, "JwtAuthenticationFilter", "Java / Spring WebFlux", "Validates Bearer tokens<br/>Extracts subject + roles<br/>Populates SecurityContext")

    Component(svc_token_filter, "ServiceTokenAuthenticationFilter", "Java / Spring WebFlux", "Validates service-to-service tokens<br/>Used by internal services<br/>Separate token secret")

    Component(security_config, "SecurityConfig", "Java / Spring Security", "WebFlux security chain<br/>Route-level access rules<br/>CORS configuration")

    Component(tenant_filter, "TenantWebFilter", "Java / Spring WebFlux WebFilter", "Extracts X-Tenant-Id header<br/>Binds to TenantContext<br/>Clears after request")

    ' Controllers
    Component(auth_ctrl, "AuthController", "Java / Spring REST", "POST /api/auth/token<br/>Verifies credentials via bcrypt<br/>Issues signed JWT")

    Component(tenant_ctrl, "TenantController", "Java / Spring REST", "GET/POST/DELETE /api/tenants<br/>Tenant CRUD operations<br/>Cascade-delete guard")

    Component(policy_ctrl, "PolicyRuleController", "Java / Spring REST", "GET/POST /api/policy-rules<br/>GET /api/policy-rules/{name}/history<br/>New version deactivates previous")

    Component(audit_ctrl, "AuditController", "Java / Spring REST", "GET /api/audit/events<br/>GET /api/audit/events/{id}<br/>GET /api/audit/summary")

    ' Repositories (R2DBC)
    Component(admin_user_repo, "AdminUserRepository", "Spring Data R2DBC", "Loads admin users<br/>bcrypt password column<br/>Role assignment")

    Component(tenant_repo, "TenantRepository", "Spring Data R2DBC", "Tenant CRUD<br/>tenant_id UUID primary key")

    Component(policy_repo, "PolicyRuleRepository", "Spring Data R2DBC", "Policy rules with versioning<br/>is_active + effective_from/to<br/>Version history queries")

    Component(audit_repo, "AuditEventRepository", "Spring Data R2DBC", "Paginated event reads<br/>Filter by type + date range")
}

ContainerDb(postgres, "PostgreSQL", "Database", "admin_users, tenants, policy_rules, audit_events")

' Request path
Rel(jwt_filter, security_config, "Feeds authentication", "SecurityContext")
Rel(svc_token_filter, security_config, "Feeds authentication", "SecurityContext")
Rel(tenant_filter, auth_ctrl, "Provides tenant context", "TenantContext")
Rel(tenant_filter, tenant_ctrl, "Provides tenant context", "TenantContext")
Rel(tenant_filter, policy_ctrl, "Provides tenant context", "TenantContext")
Rel(tenant_filter, audit_ctrl, "Provides tenant context", "TenantContext")

Rel(auth_ctrl, admin_user_repo, "Load user + verify bcrypt", "R2DBC")
Rel(tenant_ctrl, tenant_repo, "CRUD", "R2DBC")
Rel(policy_ctrl, policy_repo, "Versioned CRUD + history", "R2DBC")
Rel(audit_ctrl, audit_repo, "Paginated reads", "R2DBC")

Rel(admin_user_repo, postgres, "Queries", "R2DBC")
Rel(tenant_repo, postgres, "Queries", "R2DBC")
Rel(policy_repo, postgres, "Queries", "R2DBC")
Rel(audit_repo, postgres, "Queries", "R2DBC")

SHOW_LEGEND()
@enduml
```

- [ ] **Step 2: Commit**

```bash
git add documentation/diagrams/c3-admin-api.puml
git commit -m "docs(diagrams): add C3 component diagram for Admin API"
```

---

## Task 3: Create `sequence-tenant-propagation.puml`

**Files:**
- Create: `documentation/diagrams/sequence-tenant-propagation.puml`

- [ ] **Step 1: Create the file with this exact content**

```
@startuml Sequence_Tenant_Propagation
' Purpose: X-Tenant-Id propagation through HTTP and Kafka paths
' Used in: architecture-guide.adoc - Security Architecture section

!theme plain
skinparam sequenceMessageAlign center
skinparam responseMessageBelowArrow true

title X-Tenant-Id Propagation — HTTP and Kafka Paths

== HTTP Path ==

participant "HTTP Client" as Client
participant "TenantWebFilter" as TWF
participant "TenantContext\n(Reactor Context)" as TC
participant "Service Method" as Svc
participant "R2DBC Repository" as Repo
database "PostgreSQL\n(tenant_id column)" as DB

Client -> TWF : HTTP request\nX-Tenant-Id: <uuid>
activate TWF
TWF -> TC : subscriberContext()\n.put("tenantId", uuid)
TWF -> Svc : Mono / Flux with tenantId in context
activate Svc
Svc -> Repo : findAll() — query filtered\nby tenantId from context
Repo -> DB : SELECT * FROM ... WHERE tenant_id = ?
DB --> Repo : rows for this tenant only
Repo --> Svc : reactive result
Svc --> TWF : response
deactivate Svc
TWF --> Client : HTTP response
deactivate TWF

== Kafka Path ==

participant "Kafka Producer\n(any service)" as Prod
participant "TenantAwareKafkaSupport" as TAKS
queue "Kafka Topic" as Topic
participant "Kafka Consumer" as Cons
participant "Downstream\nService Method" as DSvc

Prod -> TAKS : send(event)
TAKS -> TAKS : read tenantId\nfrom TenantContext
TAKS -> Topic : ProducerRecord with\nheader tenant_id=<uuid>

Topic -> Cons : ConsumerRecord
Cons -> TAKS : extractTenantId(headers)
TAKS -> TC : bind tenantId\nto subscriber context
TAKS -> DSvc : invoke with\ntenantId in context
DSvc -> DB : filtered query\nWHERE tenant_id = ?

@enduml
```

- [ ] **Step 2: Commit**

```bash
git add documentation/diagrams/sequence-tenant-propagation.puml
git commit -m "docs(diagrams): add tenant propagation sequence diagram"
```

---

## Task 4: Create `sequence-admin-auth.puml`

**Files:**
- Create: `documentation/diagrams/sequence-admin-auth.puml`

- [ ] **Step 1: Create the file with this exact content**

```
@startuml Sequence_Admin_Auth
' Purpose: Admin UI authentication flow — credential verification and JWT issuance
' Used in: user-guide.adoc - Login section

!theme plain
skinparam sequenceMessageAlign center
skinparam responseMessageBelowArrow true

title Admin UI Authentication Flow

actor "Browser\n(Admin UI)" as Browser
participant "AuthController\nPOST /api/auth/token" as AC
participant "AuthService" as AS
participant "AdminUserRepository" as Repo
database "PostgreSQL\nadmin_users" as DB
participant "JwtService" as JWT

== Login ==

Browser -> AC : POST /api/auth/token\n{username, password}
activate AC
AC -> AS : authenticate(username, password)
activate AS
AS -> Repo : findByUsername(username)
activate Repo
Repo -> DB : SELECT * FROM admin_users\nWHERE username = ?
DB --> Repo : AdminUser row
Repo --> AS : Optional<AdminUser>
deactivate Repo
AS -> AS : BCrypt.checkpw(\n  password, storedHash)
AS -> JWT : sign(subject=username,\n  roles=user.roles)
activate JWT
JWT --> AS : signed JWT string
deactivate JWT
AS --> AC : token string
deactivate AS
AC --> Browser : 200 OK\n{token: "<jwt>"}
deactivate AC

note right of Browser
  JWT stored in memory (JS variable)
  NOT in localStorage — cleared on
  page reload for security
end note

== Subsequent Authenticated Requests ==

Browser -> AC : GET /api/tenants\nAuthorization: Bearer <token>
activate AC
AC -> AC : JwtAuthenticationFilter\nvalidates signature + expiry
AC -> AC : Extracts subject + roles\ninto SecurityContext
AC --> Browser : 200 OK {data}
deactivate AC

@enduml
```

- [ ] **Step 2: Commit**

```bash
git add documentation/diagrams/sequence-admin-auth.puml
git commit -m "docs(diagrams): add admin authentication sequence diagram"
```

---

## Task 5: Add asciidoctor-maven-plugin to root `pom.xml`

**Files:**
- Modify: `pom.xml` lines 349–351 (after `</pluginManagement>`, before `</build>`)

The plugin does NOT currently exist in pom.xml. Insert a `<plugins>` section after the closing `</pluginManagement>` tag.

- [ ] **Step 1: Add the plugin section**

In `pom.xml`, locate the line:
```xml
    </pluginManagement>
  </build>
```

Replace it with:
```xml
    </pluginManagement>

    <plugins>
      <!-- AsciiDoc PDF Documentation -->
      <plugin>
        <groupId>org.asciidoctor</groupId>
        <artifactId>asciidoctor-maven-plugin</artifactId>
        <version>3.0.0</version>
        <dependencies>
          <dependency>
            <groupId>org.asciidoctor</groupId>
            <artifactId>asciidoctorj-pdf</artifactId>
            <version>2.3.18</version>
          </dependency>
          <dependency>
            <groupId>org.asciidoctor</groupId>
            <artifactId>asciidoctorj-diagram</artifactId>
            <version>2.3.1</version>
          </dependency>
        </dependencies>
        <configuration>
          <sourceDirectory>${project.basedir}/documentation</sourceDirectory>
          <sourceDocumentNames>
            <sourceDocumentName>architecture-guide.adoc</sourceDocumentName>
            <sourceDocumentName>developer-guide.adoc</sourceDocumentName>
            <sourceDocumentName>operations-guide.adoc</sourceDocumentName>
            <sourceDocumentName>user-guide.adoc</sourceDocumentName>
          </sourceDocumentNames>
          <requires>
            <require>asciidoctor-diagram</require>
          </requires>
          <backend>pdf</backend>
          <outputDirectory>${project.basedir}/documentation/generated</outputDirectory>
          <attributes>
            <allow-uri-read>true</allow-uri-read>
            <imagesdir>${project.basedir}/documentation/diagrams</imagesdir>
            <plantumldir>${project.basedir}/documentation/diagrams</plantumldir>
          </attributes>
        </configuration>
        <executions>
          <execution>
            <id>generate-pdf-docs</id>
            <phase>generate-resources</phase>
            <goals>
              <goal>process-asciidoc</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
```

- [ ] **Step 2: Add `documentation/generated/` to `.gitignore`**

Open `.gitignore` and add:
```
documentation/generated/
```

- [ ] **Step 3: Run Spotless on pom.xml to fix formatting**

```bash
mvn spotless:apply -N
```

Expected: `0 were changed to be clean` or pom.xml gets reformatted — either is fine.

- [ ] **Step 4: Commit**

```bash
git add pom.xml .gitignore
git commit -m "build: add asciidoctor-maven-plugin for PDF documentation generation"
```

---

## Task 6: Archive old documentation files

**Files:** All `git mv` operations — no file content changes.

- [ ] **Step 1: Create archive directories**

```bash
mkdir -p documentation/archive/old-architecture
mkdir -p documentation/archive/old-docs
mkdir -p documentation/archive/old-root-docs
```

- [ ] **Step 2: Archive old architecture files**

```bash
git mv documentation/architecture.adoc documentation/archive/old-architecture/architecture.adoc
git mv documentation/architecture.pdf documentation/archive/old-architecture/architecture.pdf
```

- [ ] **Step 3: Archive old documentation/ markdown files**

```bash
git mv documentation/DECISIONS_SUMMARY.md documentation/archive/old-docs/DECISIONS_SUMMARY.md
git mv documentation/OPEN_QUESTIONS.md documentation/archive/old-docs/OPEN_QUESTIONS.md
git mv documentation/SOUL.md documentation/archive/old-docs/SOUL.md
git mv documentation/TEST_MATRIX.md documentation/archive/old-docs/TEST_MATRIX.md
git mv documentation/CREATE_APPLICATION.md documentation/archive/old-docs/CREATE_APPLICATION.md
git mv documentation/concept/DOMAIN_CONCEPT.md documentation/archive/old-docs/DOMAIN_CONCEPT.md
git mv documentation/developer-idea/ExtendedTechnical.md documentation/archive/old-docs/ExtendedTechnical.md
git mv documentation/developer-idea/MilestonesDraft.md documentation/archive/old-docs/MilestonesDraft.md
git mv documentation/developer-idea/MinimalIdeaTechnical.md documentation/archive/old-docs/MinimalIdeaTechnical.md
git mv documentation/developer-idea/MinimalIdeaTechnical2.md documentation/archive/old-docs/MinimalIdeaTechnical2.md
git mv documentation/developer-idea/rawInput.md documentation/archive/old-docs/rawInput.md
git mv documentation/schema/AUDIT_SCHEMA.md documentation/archive/old-docs/AUDIT_SCHEMA.md
git mv documentation/developer/backup-restore-runbook.md documentation/archive/old-docs/backup-restore-runbook.md
git mv documentation/developer/MODERATION_RULES.md documentation/archive/old-docs/MODERATION_RULES.md
git mv documentation/developer/performance-benchmarks.md documentation/archive/old-docs/performance-benchmarks.md
```

- [ ] **Step 4: Archive root-level markdown files**

```bash
git mv CODE_QUALITY.md documentation/archive/old-root-docs/CODE_QUALITY.md
git mv CONTRIBUTING.md documentation/archive/old-root-docs/CONTRIBUTING.md
git mv DOCUMENTATION-REFACTORING.md documentation/archive/old-root-docs/DOCUMENTATION-REFACTORING.md
git mv EVENT_SCHEMAS.md documentation/archive/old-root-docs/EVENT_SCHEMAS.md
git mv HEALTH_ENDPOINTS.md documentation/archive/old-root-docs/HEALTH_ENDPOINTS.md
git mv INFRASTRUCTURE.md documentation/archive/old-root-docs/INFRASTRUCTURE.md
git mv LOGGING.md documentation/archive/old-root-docs/LOGGING.md
git mv ONBOARDING.md documentation/archive/old-root-docs/ONBOARDING.md
git mv PORT_CONFIGURATION.md documentation/archive/old-root-docs/PORT_CONFIGURATION.md
git mv TDLIB_SETUP.md documentation/archive/old-root-docs/TDLIB_SETUP.md
```

- [ ] **Step 5: Commit the archive**

```bash
git add -A
git commit -m "docs: archive old scattered markdown files and legacy architecture.adoc"
```

---

## Task 7: Write `architecture-guide.adoc`

**Files:**
- Create: `documentation/architecture-guide.adoc`

- [ ] **Step 1: Create the file with this exact content**

```asciidoc
= EMCIP Architecture Guide
:toc:
:toclevels: 3
:sectnums:
:icons: font
:source-highlighter: rouge
:imagesdir: diagrams

== Introduction

EMCIP (Enterprise Messenger Community Intelligence Platform) connects to Telegram as a *real user* (not a bot) via TDLib, listens passively in groups, channels, and discussion chats, classifies message intent, evaluates community policy rules, and optionally generates LLM responses or applies moderation actions.

=== Operating Modes

[cols="1,3"]
|===
|Mode |Description

|*React*
|Policy rule evaluates intent and produces a structured response via the LLM Orchestrator.

|*Summarize*
|Conversation context is periodically summarised and stored for retrieval.

|*Moderate*
|The Moderation Service applies keyword, regex, or length rules and flags violations.

|*Observe*
|All pipeline events are written to the Audit Service — read-only observation with no actions.
|===

NOTE: For setup steps and development prerequisites, see the _Developer Guide_.

== System Context (C1)

The following diagram shows EMCIP in relation to its external actors and systems.

[plantuml,c1-context,png]
----
include::diagrams/c1-context.puml[]
----

*External actors:*

* **Platform Admin** — configures tenants, policy rules, and moderation settings via the Admin API or Admin UI.
* **Telegram User** — sends messages in groups and channels that EMCIP monitors.

*External systems:*

* **Telegram API (TDLib)** — EMCIP connects as a real account using `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`.
* **LLM Providers** — Claude (Anthropic), OpenAI, and local Ollama models are supported via the LLM Orchestrator.

== Container Architecture (C2)

[plantuml,c2-container,png]
----
include::diagrams/c2-container.puml[]
----

The platform comprises 8 application microservices, each independently deployable:

[cols="1,1,3"]
|===
|Service |Port |Responsibility

|`emcip-tdlib-adapter`
|9080
|Connects to Telegram via TDLib; publishes `telegram.raw.messages`.

|`emcip-conversation-context`
|9081
|Tracks threads, users, and message history; JPA / PostgreSQL.

|`emcip-intent-classifier`
|9082
|Classifies message intent; publishes `messages.classified`.

|`emcip-policy-engine`
|9083
|Evaluates policy rules; publishes `policies.decisions`.

|`emcip-llm-orchestrator`
|9084
|Routes to LLM provider; publishes `responses.generated`.

|`emcip-moderation-service`
|9085
|Applies moderation rules; publishes `moderation.flags`.

|`emcip-audit-service`
|9086
|Appends all events to the audit log; R2DBC / PostgreSQL.

|`emcip-admin-api`
|9087
|REST management API for tenants, policy rules, audit queries; R2DBC / WebFlux.
|===

Infrastructure services (Kafka, PostgreSQL, Grafana, Loki, Admin UI) are defined in `docker-compose.yml`.

== Component Details (C3)

=== Overview

[plantuml,c3-overview,png]
----
include::diagrams/c3-component.puml[]
----

=== TDLib Adapter

[plantuml,c3-tdlib,png]
----
include::diagrams/c3-tdlib-adapter.puml[]
----

=== Policy Engine

[plantuml,c3-policy,png]
----
include::diagrams/c3-policy-engine.puml[]
----

=== LLM Orchestrator

[plantuml,c3-llm,png]
----
include::diagrams/c3-llm-orchestrator.puml[]
----

=== Admin API

[plantuml,c3-admin-api,png]
----
include::diagrams/c3-admin-api.puml[]
----

The Admin API uses Spring WebFlux with two filter chains: `JwtAuthenticationFilter` for human operators and `ServiceTokenAuthenticationFilter` for internal service-to-service calls. All four controllers (`AuthController`, `TenantController`, `PolicyRuleController`, `AuditController`) share `TenantWebFilter` which binds the `X-Tenant-Id` header to the Reactor subscriber context.

== Domain & Code Model (C4)

[plantuml,c4-code,png]
----
include::diagrams/c4-code.puml[]
----

[plantuml,c4-policy-domain,png]
----
include::diagrams/c4-policy-domain.puml[]
----

== Event Architecture

The pipeline is fully event-driven. Services communicate via Kafka topics only — no synchronous HTTP calls between core pipeline services.

=== Message Flow Introduction

[plantuml,seq-msg-flow,png]
----
include::diagrams/sequence-message-flow.puml[]
----

=== Event Topology

[plantuml,c4-event-flow,png]
----
include::diagrams/c4-event-flow.puml[]
----

=== Kafka Consumer Groups

[plantuml,c4-kafka-consumers,png]
----
include::diagrams/c4-kafka-consumers.puml[]
----

=== Kafka Topic Reference

[cols="2,2,2,2,3"]
|===
|Topic |Partitions |Producer |Consumer(s) |Schema summary

|`telegram.raw.messages`
|3
|tdlib-adapter
|intent-classifier
|`eventId` (UUID), `eventType`, `timestamp`, `payload.chatId`, `payload.text`

|`telegram.raw.updates`
|3
|tdlib-adapter
|conversation-context
|`eventId`, `updateType`, `timestamp`, `payload`

|`messages.classified`
|3
|intent-classifier
|policy-engine, audit-service
|`eventId`, `intentType`, `confidence`, `originalMessageId`

|`context.threads`
|3
|conversation-context
|policy-engine
|`eventId`, `threadId`, `messageCount`, `participants`

|`policies.decisions`
|3
|policy-engine
|llm-orchestrator, moderation-service, audit-service
|`eventId`, `decision` (ALLOW/WARN/MUTE/BAN/RESPOND), `ruleId`, `reasoning`

|`responses.generated`
|3
|llm-orchestrator
|audit-service
|`eventId`, `model`, `promptTokens`, `completionTokens`, `responseText`

|`moderation.flags`
|3
|moderation-service
|audit-service
|`eventId`, `flagType` (KEYWORD/REGEX/LENGTH), `pattern`, `action`

|`audit.events`
|3
|all services
|audit-service
|`eventId`, `eventType`, `sourceService`, `timestamp`, `payload` (JSONB)
|===

NOTE: For sequence-level pipeline detail showing the full message lifecycle, see the _Developer Guide_.

== Data Flows

=== Audit Trail

[plantuml,df-audit,png]
----
include::diagrams/dataflow-audit-trail.puml[]
----

=== Context Enrichment

[plantuml,df-context,png]
----
include::diagrams/dataflow-context-enrichment.puml[]
----

== Technology Stack & Guardrails

=== Core Stack

[cols="1,2"]
|===
|Layer |Technology

|Language
|Java 21 (LTS). Java 25 is the intended production target once Spring Boot 4 adds official support.

|Framework
|Spring Boot 4 (WebFlux + WebMvc). Services with blocking I/O use WebMvc; reactive services use WebFlux.

|Build
|Maven 3.x, multi-module parent POM (`community-intelligence-parent:0.1.0-SNAPSHOT`).

|Database
|PostgreSQL 16 (port 14005). Schema migrations: Liquibase only — never Flyway.

|Messaging
|Apache Kafka (external port 14003). All producers use `CommonKafkaConfig` from `emcip-core`.

|Persistence
|JPA/Hibernate for Phase 2/3 services (complex domain models). R2DBC for Phase 4 services (flat schemas + WebFlux). See ADR-003 and ADR-004.

|Code style
|Spotless with Google Java Format (AOSP variant). Run `mvn spotless:apply` before every commit.

|Boilerplate
|Lombok: `@Slf4j`, `@RequiredArgsConstructor`, `@Data` where appropriate. Never write manual getters/setters.
|===

=== JPA vs R2DBC Split

[cols="1,1,2"]
|===
|Service |Persistence |Reason

|conversation-context
|JPA
|Rich domain: Chat, User, Message with FK associations and JPQL queries.

|intent-classifier
|JPA
|`@CreatedDate`, `@Version` optimistic locking; benefits from Hibernate caching.

|policy-engine
|JPA
|Policy versioning, complex rule queries, transaction management.

|llm-orchestrator
|JPA
|LLM cost tracking coordination requires JPA transaction management.

|moderation-service
|R2DBC
|Event-driven, flat tables, fully reactive Kafka consumer loop.

|audit-service
|R2DBC
|High-throughput append-only writes; non-blocking inserts maximise throughput.

|admin-api
|R2DBC
|Simple CRUD, interactive requests; non-blocking keeps latency low.
|===

NOTE: All R2DBC services still include `spring-boot-starter-jdbc` as `runtime` scope for Liquibase migrations only.

=== Liquibase Conventions

* One `db.changelog-master.xml` per service under `src/main/resources/db/changelog/`.
* Changeset IDs follow `YYYY-MM-DD-N-description` format.
* Never use `<dropTable>` or destructive operations without a migration ticket.

== Security Architecture

=== JWT Authentication Chain

The Admin API uses two parallel filter chains registered as Spring `WebFilter`:

1. **`JwtAuthenticationFilter`** — validates `Authorization: Bearer <token>` header. Extracts `sub` (username) and `roles` claims. Populates `ReactiveSecurityContextHolder`.
2. **`ServiceTokenAuthenticationFilter`** — validates `X-Service-Token` header for internal service calls.

Tokens are signed with `ADMIN_JWT_SECRET` (HS256). Expiry is configurable via `app.jwt.expiration`.

=== Tenant Isolation

Every entity table contains a `tenant_id UUID NOT NULL` column. The `TenantWebFilter` extracts the `X-Tenant-Id` HTTP header and binds it to the Reactor subscriber context. All repository queries include a tenant filter derived from this context.

[plantuml,seq-tenant,png]
----
include::diagrams/sequence-tenant-propagation.puml[]
----

Kafka messages carry `tenant_id` as a record header. `TenantAwareKafkaSupport` in `emcip-core` reads this header on the consumer side and binds it to the subscriber context before invoking the service method.

NOTE: For deployment configuration and port assignments, see the _Operations Guide_.

[appendix]
== ADR-001: Technology Stack Selection

*Status:* Accepted (2026-04-15). *Amendment:* Java 25 production target added 2026-04-21.

*Decision:* Java 21, Spring Boot 4, Maven, Apache Kafka, PostgreSQL 16, R2DBC/JPA, Docker.

*Context:* EMCIP requires high-throughput event processing, reactive non-blocking I/O, strong typing, and cloud-native deployment. Java 21 virtual threads simplify concurrent programming compared to explicit reactive chains.

*Alternatives rejected:* Go (smaller ecosystem), Node.js (single-threaded, type safety), Python (GIL + performance), Kotlin (smaller talent pool at project start).

[appendix]
== ADR-002: Event-Driven Architecture with Kafka

*Status:* Accepted (2026-04-15).

*Decision:* All inter-service communication uses Kafka topics (JSON, 3 partitions each). No synchronous HTTP between pipeline services. At-least-once delivery with idempotent consumers.

*Context:* Multi-stage pipeline (ingest → classify → evaluate → respond → audit) requires backpressure handling, independent scaling, and replay capability. Eight topics defined (see §6 Kafka Topic Reference).

*Alternatives rejected:* RabbitMQ (no replay), Pulsar (smaller ecosystem), NATS (less durable), HTTP/gRPC (tight coupling).

[appendix]
== ADR-003: Data Persistence with PostgreSQL

*Status:* Accepted (2026-04-15). *Amendment:* Migrated from R2DBC to JPA during Phase 2.

*Decision:* PostgreSQL 16 as primary database. JPA/Hibernate (JDBC) for Phase 2/3 services with complex domain models. Liquibase for all schema migrations.

*Context:* Initial R2DBC approach was replaced because JPA provided better Spring Data integration, easier testing, and simpler transaction management for services with entity associations.

[appendix]
== ADR-004: R2DBC and Reactive Stack for Phase 4 Services

*Status:* Accepted (2026-04-21).

*Decision:* R2DBC with Spring Data R2DBC is approved for `emcip-moderation-service`, `emcip-audit-service`, and `emcip-admin-api`. These three services have flat schemas, high I/O throughput, and a fully reactive WebFlux runtime — the profile where R2DBC outperforms JPA.

*Relationship to ADR-003:* ADR-003 remains authoritative for Phase 2/3 services. ADR-004 extends it for Phase 4 services only. The rule is: JPA for complex domain models; R2DBC for flat schemas + WebFlux.

*Consequence:* All R2DBC services include `spring-boot-starter-jdbc` as `runtime` scope for Liquibase only.
```

- [ ] **Step 2: Commit**

```bash
git add documentation/architecture-guide.adoc
git commit -m "docs: add architecture guide AsciiDoc"
```

---

## Task 8: Write `developer-guide.adoc`

**Files:**
- Create: `documentation/developer-guide.adoc`

- [ ] **Step 1: Create the file with this exact content**

```asciidoc
= EMCIP Developer Guide
:toc:
:toclevels: 3
:sectnums:
:icons: font
:source-highlighter: rouge

== Quick Start

*Prerequisites:* Java 21+, Docker Desktop, Maven 3.9+.

[source,bash]
----
# 1. Clone
git clone <repo-url>
cd emcip

# 2. Build (skip tests for speed)
mvn install -DskipTests

# 3. Start infrastructure
docker compose up -d postgres kafka zookeeper

# 4. Run a service (example: admin-api)
mvn spring-boot:run -pl emcip-admin-api
----

The admin-api starts on port 9087. Health check: `curl http://localhost:9087/actuator/health`.

NOTE: For the complete Docker Compose setup including all profiles and port assignments, see the _Operations Guide_.

NOTE: For the architectural context of these services, see the _Architecture Guide_.

== Module Structure

The project is a Maven multi-module build with a single parent POM (`community-intelligence-parent:0.1.0-SNAPSHOT`).

[cols="2,1,3"]
|===
|Module |Type |Owns

|`emcip-core`
|Library
|Shared: `CommonKafkaConfig`, `TenantContext`, `TenantAwareKafkaSupport`, base event classes, common utilities.

|`emcip-tdlib-adapter`
|Service
|TDLib integration, raw message ingestion, `telegram.raw.messages` producer.

|`emcip-conversation-context`
|Service
|Chat / User / Message entities, thread tracking, `context.threads` producer.

|`emcip-intent-classifier`
|Service
|Intent classification via NLP, `messages.classified` producer.

|`emcip-policy-engine`
|Service
|Policy rule evaluation, versioned rules, `policies.decisions` producer.

|`emcip-llm-orchestrator`
|Service
|LLM provider routing (Claude, OpenAI, Ollama), `responses.generated` producer.

|`emcip-moderation-service`
|Service
|Keyword/regex/length rule enforcement, `moderation.flags` producer.

|`emcip-audit-service`
|Service
|Append-only audit log, aggregates events from all topics.

|`emcip-admin-api`
|Service
|REST management API (tenants, policy rules, audit, auth).

|`emcip-admin-ui`
|Service
|Spring Boot wrapper serving the React SPA on port 14009.

|`gatling-tests`
|Test module
|Gatling 3.11 load simulations. Excluded from standard `mvn test`. Run via `cd gatling-tests && mvn gatling:test`.
|===

== Development Patterns

=== JPA Entities (Phase 2/3 services)

Every JPA entity follows this pattern:

[source,java]
----
@Entity
@Table(name = "my_entity")
@Data
@NoArgsConstructor
public class MyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Version
    private Long version;          // optimistic locking

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private UUID tenantId;         // row-level tenant isolation

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
----

Key rules:
* UUID primary keys, never `BIGSERIAL`.
* `@Version` on every entity for optimistic locking.
* `@Column(nullable = false)` on every non-null column — never rely on database defaults.
* Use Lombok `@Data` + `@NoArgsConstructor` — never write manual getters/setters.
* Enable `@EnableJpaAuditing` on the `@SpringBootApplication` class for `@CreatedDate`/`@LastModifiedDate`.

=== Kafka Producers and Consumers

All Kafka configuration inherits from `CommonKafkaConfig` in `emcip-core`:

[source,java]
----
// Producing an event
@RequiredArgsConstructor
@Service
public class MessagePublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(TelegramRawMessageEvent event) {
        kafkaTemplate.send("telegram.raw.messages", event.getEventId().toString(), event);
    }
}
----

[source,java]
----
// Consuming events
@Slf4j
@Service
public class MessageConsumer {

    @KafkaListener(topics = "telegram.raw.messages", groupId = "intent-classifier")
    public void consume(TelegramRawMessageEvent event) {
        log.info("Received event: {}", event.getEventId());
        // process...
    }
}
----

*DLQ pattern:* Failed messages after retry exhaustion are routed to `<topic>.dlq` by `CommonKafkaConfig`. Monitor via Kafka UI (port 14004).

*Tenant propagation:* Use `TenantAwareKafkaSupport` when producing — it adds the `tenant_id` header automatically from `TenantContext`.

=== Liquibase Migrations

Every service has one master changelog at `src/main/resources/db/changelog/db.changelog-master.xml`.

[source,xml]
----
<!-- db.changelog-master.xml -->
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.0.xsd">
    <include file="db/changelog/2026-04-15-1-initial-schema.xml" relativeToChangelogFile="false"/>
    <!-- Add new changesets here. Never modify existing ones. -->
</databaseChangeLog>
----

Individual changeset naming: `YYYY-MM-DD-N-description.xml`.

*Rules:*
* **Never Flyway** — this project uses Liquibase exclusively.
* Never modify a committed changeset — always add a new one.
* For a new column: `<addColumn>`, not `<modifyColumn>`.
* Include `<rollback>` tags for destructive operations.

=== Spotless Formatting

Run before every commit:

[source,bash]
----
mvn spotless:apply
----

Expected output: `0 were changed to be clean` (the zero is the important number — it means no files needed formatting).

If files were changed: `git add -A && git commit --amend --no-edit`.

CI enforces `mvn spotless:check` in the `verify` phase. A failing Spotless check blocks the build.

== Kafka Topics Reference

[cols="2,2,2,2"]
|===
|Topic |Producer |Consumer(s) |Key fields

|`telegram.raw.messages`
|tdlib-adapter
|intent-classifier, audit-service
|`eventId`, `chatId`, `text`, `senderId`

|`telegram.raw.updates`
|tdlib-adapter
|conversation-context
|`eventId`, `updateType`, `payload`

|`messages.classified`
|intent-classifier
|policy-engine, audit-service
|`eventId`, `intentType`, `confidence`, `originalMessageId`

|`context.threads`
|conversation-context
|policy-engine
|`eventId`, `threadId`, `messageCount`

|`policies.decisions`
|policy-engine
|llm-orchestrator, moderation-service, audit-service
|`eventId`, `decision`, `ruleId`, `reasoning`

|`responses.generated`
|llm-orchestrator
|audit-service
|`eventId`, `model`, `promptTokens`, `completionTokens`

|`moderation.flags`
|moderation-service
|audit-service
|`eventId`, `flagType`, `pattern`, `action`

|`audit.events`
|all services
|audit-service
|`eventId`, `eventType`, `sourceService`, `payload`
|===

Full JSON schemas are in `documentation/archive/old-root-docs/EVENT_SCHEMAS.md`.

== Service APIs

=== Health and Metrics (all services)

Every service exposes standard Spring Boot Actuator endpoints:

[source,bash]
----
# Liveness / Readiness
curl http://localhost:<port>/actuator/health

# Prometheus scrape endpoint
curl http://localhost:<port>/actuator/prometheus
----

Ports: 9080 (tdlib-adapter) through 9087 (admin-api). See the _Operations Guide_ for the full port table.

=== Admin API Endpoints

[cols="1,2,2"]
|===
|Method + Path |Description |Auth

|`POST /api/auth/token`
|Issue JWT; body: `{username, password}`
|None

|`GET /api/tenants`
|List all tenants
|Bearer token

|`POST /api/tenants`
|Create tenant; body: `{name}`
|Bearer token

|`DELETE /api/tenants/{id}`
|Delete tenant (cascades data)
|Bearer token

|`GET /api/policy-rules`
|List active policy rules
|Bearer token

|`POST /api/policy-rules`
|Create new version; deactivates previous
|Bearer token

|`GET /api/policy-rules/{name}/history`
|Full version history for a named rule
|Bearer token

|`GET /api/audit/events`
|Paginated audit events; params: `size`, `page`, `eventType`, `from`, `to`
|Bearer token

|`GET /api/audit/events/{id}`
|Single audit event with full payload
|Bearer token

|`GET /api/audit/summary`
|Event counts by type
|Bearer token

|`GET /api/admin/moderation-rules`
|List all moderation rules
|Bearer token

|`POST /api/admin/moderation-rules`
|Create rule; body: `{ruleType, pattern, action, enabled}`
|Bearer token

|`DELETE /api/admin/moderation-rules/{id}`
|Delete moderation rule
|Bearer token
|===

== Testing

=== Testcontainers (Integration Tests)

Services with database operations use `TestcontainersInitializer` to spin up a real PostgreSQL container:

[source,java]
----
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersInitializer.class)
class PolicyEngineIntegrationTest {

    @Autowired
    private PolicyRuleRepository policyRuleRepository;

    @Test
    void shouldEvaluateActiveRule() {
        // Arrange — insert rule using real DB
        var rule = new PolicyRuleConfig();
        rule.setName("spam-block");
        rule.setAction("BLOCK");
        rule.setActive(true);
        policyRuleRepository.save(rule);

        // Act
        var decision = policyEvaluationService.evaluate("SPAM", "tenantA");

        // Assert
        assertThat(decision.getDecision()).isEqualTo("BLOCK");
    }
}
----

=== Unit Tests (Mockito)

[source,java]
----
@ExtendWith(MockitoExtension.class)
class IntentClassifierServiceTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private IntentClassifierService service;

    @Test
    void shouldClassifyGreetingIntent() {
        var result = service.classify("Hello, how are you?");
        assertThat(result.getIntentType()).isEqualTo("GREETING");
    }
}
----

=== Backup/Restore Integration Test

Gated by the environment variable `ECIP_IT_ENABLED=true`:

[source,bash]
----
ECIP_IT_ENABLED=true mvn test -pl emcip-audit-service -Dtest=BackupRestoreIT
----

=== Gatling Load Tests

[source,bash]
----
cd gatling-tests
mvn gatling:test
# Results at: gatling-tests/target/gatling/*/index.html
----

Simulations are in `gatling-tests/src/test/java/io/emcip/perf/`. They are excluded from the standard `mvn test` run via surefire `<skipTests>true</skipTests>`.

== TDLib Integration

=== Why TDLib (not Bot API)

EMCIP connects as a *real Telegram user*, not a bot. This provides access to private groups, full message history, and member lists — capabilities unavailable via the Bot API.

=== Docker Build

The TDLib adapter builds TDLib from source inside Docker. The first build takes 15–30 minutes. Subsequent builds use the Docker layer cache.

[source,bash]
----
# Build tdlib-adapter image (runs from repo root)
docker compose build tdlib-adapter

# Or with no-cache to force recompile
docker compose build --no-cache tdlib-adapter
----

=== Authentication

Required environment variables:

[source,bash]
----
TELEGRAM_API_ID=<your-api-id>
TELEGRAM_API_HASH=<your-api-hash>
TELEGRAM_PHONE_NUMBER=+<country><number>
----

Obtain `API_ID` and `API_HASH` from https://my.telegram.org. On first startup, TDLib prompts for the SMS verification code via stdin.

=== Troubleshooting `UnsatisfiedLinkError`

If the service throws `java.lang.UnsatisfiedLinkError: libtdjni.so: cannot open shared object file`, the native library was not compiled or is in the wrong path.

[source,bash]
----
# Rebuild from scratch
docker compose build --no-cache tdlib-adapter

# Verify the .so file is present
docker compose run --rm tdlib-adapter ls /usr/local/lib/libtdjni.so
----

== Message Pipeline Walkthrough

The three diagrams below trace a message from Telegram arrival to audit log entry.

=== Full Message Lifecycle

[plantuml,seq-lifecycle,png]
----
include::diagrams/sequence-full-message-lifecycle.puml[]
----

=== Policy Evaluation Detail

[plantuml,seq-policy-eval,png]
----
include::diagrams/sequence-policy-evaluation.puml[]
----

=== LLM Orchestration Detail

[plantuml,seq-llm,png]
----
include::diagrams/sequence-llm-orchestration.puml[]
----

== Contributing

=== Branch Strategy

[source,bash]
----
# Feature work
git checkout -b feat/my-feature

# Bug fix
git checkout -b fix/my-bug

# After all tests pass and Spotless is clean:
git push origin feat/my-feature
# Open PR → main
----

=== Commit Format

[source]
----
feat(scope): short description

Longer explanation of why the change was made.
Reference any user story: US-3.2.1.
----

Common scopes: `policy-engine`, `admin-api`, `kafka`, `liquibase`, `diagrams`.

One commit minimum per user story. Squash WIP commits before opening a PR.

=== Pre-commit Checklist

[source,bash]
----
mvn spotless:apply          # Format code
mvn test                    # All tests pass
mvn spotless:check          # Verify formatting (CI will check this)
----

CI runs `mvn verify` on every PR. A Spotless violation or failing test blocks merge.
```

- [ ] **Step 2: Commit**

```bash
git add documentation/developer-guide.adoc
git commit -m "docs: add developer guide AsciiDoc"
```

---

## Task 9: Write `operations-guide.adoc`

**Files:**
- Create: `documentation/operations-guide.adoc`

- [ ] **Step 1: Create the file with this exact content**

```asciidoc
= EMCIP Operations Guide
:toc:
:toclevels: 3
:sectnums:
:icons: font
:source-highlighter: rouge

NOTE: For the architectural context of these services, see the _Architecture Guide_.

== Infrastructure Overview

[plantuml,deploy-local,png]
----
include::diagrams/deployment-local-docker.puml[]
----

The local Docker Compose environment runs 8 application services and 9 infrastructure services:

[cols="2,3"]
|===
|Category |Services

|Application (8)
|tdlib-adapter, conversation-context, intent-classifier, policy-engine, llm-orchestrator, moderation-service, audit-service, admin-api

|Infrastructure (9)
|Zookeeper, Kafka broker, Kafka UI, PostgreSQL, pgAdmin, Grafana, Loki, Promtail, Admin UI
|===

== Docker Compose Quickstart

=== Default Startup (infrastructure only)

[source,bash]
----
docker compose up -d
----

Starts: Zookeeper, Kafka, Kafka UI, PostgreSQL, pgAdmin. Application services are not started by default — they are managed per-profile.

=== Profiles

[source,bash]
----
# All application services
docker compose --profile full up -d

# LLM Orchestrator (requires ANTHROPIC_API_KEY)
docker compose --profile llm up -d

# TDLib Adapter (requires Telegram credentials)
docker compose --profile telegram up -d

# Observability stack (Grafana, Loki, Promtail)
docker compose --profile observability up -d
----

=== `.env` File Setup

Create a `.env` file in the project root (never commit it):

[source,bash]
----
# Telegram (profile: telegram)
TELEGRAM_API_ID=12345678
TELEGRAM_API_HASH=abcdef1234567890abcdef1234567890
TELEGRAM_PHONE_NUMBER=+491234567890

# LLM (profile: llm)
ANTHROPIC_API_KEY=sk-ant-...

# Admin API JWT secret
ADMIN_JWT_SECRET=change-me-in-production-minimum-32-chars

# PostgreSQL (defaults work for local dev)
POSTGRES_USER=emcip
POSTGRES_PASSWORD=emcip
POSTGRES_DB=emcip
----

== Port Reference

[cols="2,1,3"]
|===
|Service |Port |Purpose

|emcip-tdlib-adapter
|9080
|Telegram TDLib integration

|emcip-conversation-context
|9081
|Thread and message tracking

|emcip-intent-classifier
|9082
|NLP intent classification

|emcip-policy-engine
|9083
|Policy rule evaluation

|emcip-llm-orchestrator
|9084
|LLM provider routing

|emcip-moderation-service
|9085
|Content moderation rules

|emcip-audit-service
|9086
|Audit log and metrics

|emcip-admin-api
|9087
|Admin REST API

|Zookeeper
|14001
|Kafka coordination

|Kafka (internal)
|14002
|Broker — service-to-service

|Kafka (external)
|14003
|Broker — host access, `KAFKA_BOOTSTRAP_SERVERS`

|Kafka UI
|14004
|Kafka management UI

|PostgreSQL
|14005
|Primary database

|pgAdmin
|14006
|PostgreSQL admin UI (admin@emcip.io / admin)

|Grafana
|14007
|Observability dashboards (admin / admin)

|Loki
|14008
|Log aggregation backend

|Admin UI
|14009
|React SPA for platform administration
|===

=== Port Conflict Check

[source,bash]
----
for port in 9080 9081 9082 9083 9084 9085 9086 9087 \
            14001 14002 14003 14004 14005 14006 14007 14008 14009; do
  if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "CONFLICT: port $port in use"
  fi
done
----

== Observability

=== Grafana Dashboards

Open http://localhost:14007 (credentials: `admin` / `admin`).

Three pre-built dashboards are provisioned automatically on startup:

[cols="1,3"]
|===
|Dashboard |Shows

|*Service Health*
|Actuator UP/DOWN status per service, JVM heap used, GC pause time.

|*Kafka Consumer Lag*
|Consumer group lag per topic. Alert threshold: > 1000 messages.

|*Audit Throughput*
|Audit events/minute and moderation flags/minute over time.
|===

=== Loki Log Queries

Open http://localhost:14008 or use the Grafana → Explore → Loki datasource.

[source]
----
# All ERROR logs across services
{job="emcip"} |= "ERROR"

# Errors from policy-engine only
{job="emcip", service="emcip-policy-engine"} | json | level="ERROR"

# Messages by trace ID
{job="emcip"} | json | traceId="<trace-id>"

# Kafka consumer errors
{job="emcip"} |= "KafkaListenerErrorHandler"
----

=== Prometheus Metrics

Key metrics to watch:

[cols="2,1,2"]
|===
|Metric |Service |Meaning

|`kafka_consumer_fetch_manager_records_lag_max`
|all consumers
|Maximum consumer lag — spike indicates backpressure

|`hikaricp_connections_active`
|JPA services
|Active DB connections — saturation if near `maximum-pool-size` (20)

|`jvm_memory_used_bytes`
|all services
|Heap usage — watch for growth trend

|`http_server_requests_seconds_max`
|admin-api
|Worst-case request latency

|`spring_kafka_listener_seconds_max`
|all consumers
|Worst-case consumer processing time
|===

=== Structured Log Fields

All services emit JSON logs via Spring Boot 4 native structured logging. Key fields:

[cols="1,2"]
|===
|Field |Example value

|`@timestamp`
|`2026-04-22T10:15:30.123Z`

|`level`
|`INFO`, `WARN`, `ERROR`

|`logger_name`
|`io.emcip.policyengine.PolicyEvaluationService`

|`message`
|`Policy decision: BLOCK for intent SPAM`

|`traceId`
|`4bf92f3577b34da6` (populated by Micrometer Tracing)

|`spanId`
|`00f067aa0ba902b7`
|===

== Backup & Restore

=== Creating a Backup

[source,bash]
----
# Uses defaults: localhost:14005, database emcip, user emcip
./scripts/db/backup.sh

# Custom connection
DB_HOST=myhost DB_PORT=14005 DB_NAME=emcip DB_USER=emcip \
  PGPASSWORD=secret ./scripts/db/backup.sh
----

Output: `backup_YYYYMMDD_HHMMSS.dump` in the current directory.

=== Restore Procedure

[source,bash]
----
# Step 1: Stop all application services
docker compose stop tdlib-adapter conversation-context intent-classifier \
  policy-engine llm-orchestrator moderation-service audit-service admin-api

# Step 2: Restore from dump file
./scripts/db/restore.sh backup_20260422_120000.dump

# Step 3: Verify row counts
docker exec -it $(docker compose ps -q postgres) \
  psql -U emcip -d emcip -c "
    SELECT schemaname, tablename, n_live_tup
    FROM pg_stat_user_tables
    ORDER BY n_live_tup DESC
    LIMIT 10;
  "

# Step 4: Restart services
docker compose --profile full up -d
----

=== Environment Variables for Scripts

[cols="1,1,2"]
|===
|Variable |Default |Description

|`DB_HOST`
|`localhost`
|PostgreSQL host

|`DB_PORT`
|`14005`
|PostgreSQL port (EMCIP custom range)

|`DB_NAME`
|`emcip`
|Database name

|`DB_USER`
|`emcip`
|PostgreSQL username

|`PGPASSWORD`
|`emcip`
|PostgreSQL password (read by pg_dump/pg_restore)
|===

== Performance Tuning

=== SLOs

[cols="2,1"]
|===
|Metric |Target

|p95 intent classification latency
|< 200ms

|p95 policy evaluation latency
|< 100ms

|p99 end-to-end pipeline (ingest → audit)
|< 2s

|Kafka throughput (sustained)
|500 msg/s
|===

=== HikariCP Connection Pool

`emcip-policy-engine` is the most DB-intensive service. Current tuning in `application.yml`:

[source,yaml]
----
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000    # 30s
      idle-timeout: 600000         # 10min
----

Monitor `hikaricp_connections_active`. If it saturates at 20 under load, increase `maximum-pool-size` and verify PostgreSQL `max_connections` (default 100).

=== Kafka Consumer Tuning

`emcip-intent-classifier` handles the highest message volume:

[source,yaml]
----
spring:
  kafka:
    consumer:
      max-poll-records: 500
----

Higher values increase throughput but require more heap. Monitor `jvm_memory_used_bytes` when increasing.

=== Java Flight Recorder Profiling

[source,bash]
----
# Start recording on a running service (replace PID)
jcmd <pid> JFR.start duration=60s filename=/tmp/profile.jfr

# Or at JVM startup (add to JAVA_OPTS in docker-compose.yml)
-XX:StartFlightRecording=duration=120s,filename=/tmp/profile.jfr,settings=profile
----

Open the `.jfr` file in IntelliJ or JDK Mission Control.

=== Running Load Tests

[source,bash]
----
cd gatling-tests
mvn gatling:test
# Review: gatling-tests/target/gatling/*/index.html
----

Simulations cover: Admin API auth + CRUD, Kafka publish throughput, policy evaluation endpoint.

== Error Handling & DLQ

[plantuml,seq-errors,png]
----
include::diagrams/sequence-error-handling.puml[]
----

=== Retry Configuration

Configured in `CommonKafkaConfig` (emcip-core):

* **Retries:** 3 attempts with exponential backoff (500ms, 1s, 2s).
* **Retryable exceptions:** `RetryableException`, `TransientDataAccessException`, network errors.
* **Non-retryable exceptions:** `DataIntegrityViolationException`, `IllegalArgumentException`, parse errors — sent directly to DLQ without retry.

=== DLQ Naming Convention

Each topic has a corresponding DLQ:

[source]
----
telegram.raw.messages  →  telegram.raw.messages.dlq
messages.classified    →  messages.classified.dlq
policies.decisions     →  policies.decisions.dlq
...
----

=== Monitoring DLQ

[source,bash]
----
# View DLQ messages via Kafka UI (port 14004)
open http://localhost:14004

# Or via CLI
docker exec -it $(docker compose ps -q kafka) \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:14002 \
    --topic telegram.raw.messages.dlq \
    --from-beginning
----

The `DeadLetterQueueConsumer` in `emcip-audit-service` writes a DLQ event to the audit log with the original payload and failure reason.

== Moderation Rules

Rules are evaluated by `emcip-moderation-service` against every `policies.decisions` event.

=== Rule Types

[cols="1,3,2"]
|===
|Type |Behaviour |Example pattern

|`KEYWORD`
|Exact case-insensitive word match in message text
|`spam`

|`REGEX`
|Full Java `Pattern.compile()` regex applied to message text
|`\b(buy|sell|crypto)\b`

|`LENGTH`
|Fires when message character count exceeds the numeric pattern value
|`500`
|===

=== Configuring Rules via Admin API

[source,bash]
----
# Create a keyword rule
curl -X POST http://localhost:9087/api/admin/moderation-rules \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "ruleType": "KEYWORD",
    "pattern": "scam",
    "action": "FLAG",
    "enabled": true
  }'

# List all rules
curl http://localhost:9087/api/admin/moderation-rules \
  -H "Authorization: Bearer <token>"

# Delete a rule
curl -X DELETE http://localhost:9087/api/admin/moderation-rules/<id> \
  -H "Authorization: Bearer <token>"
----

=== Cache Refresh

The moderation-service caches active rules with a 5-minute TTL. Changes made via the Admin API take effect within 5 minutes without a service restart.

== Troubleshooting

[cols="2,2,3"]
|===
|Symptom |Diagnosis command |Fix

|Port already in use
|`lsof -i :<port>`
|Stop the process using that port, or remap in `docker-compose.yml`.

|Kafka `Connection refused`
|`docker compose ps kafka`
|Ensure `KAFKA_BOOTSTRAP_SERVERS=localhost:14003` in `.env`. Internal services use `kafka:14002`.

|PostgreSQL `Connection refused`
|`docker compose ps postgres`
|Wait for Liquibase migration to complete. Check `docker compose logs postgres`.

|Liquibase migration fails on startup
|`docker compose logs <service> | grep Liquibase`
|A changeset is locked or malformed. Run `mvn liquibase:releaseLocks -pl <module>` against the dev DB.

|TDLib `TELEGRAM_PHONE_NUMBER` not set
|`docker compose logs tdlib-adapter`
|Add `TELEGRAM_PHONE_NUMBER=+<number>` to `.env` and restart.

|Logback startup errors
|`docker compose logs <service> | grep logback`
|Ensure `logstash-logback-encoder` is NOT on the classpath. Use `logging.structured.format.console: logstash` in `application.yml` instead.

|Grafana shows no data
|`curl http://localhost:14008/ready`
|Loki not ready. Wait 30s and retry. Check `docker compose logs loki`.
|===
```

- [ ] **Step 2: Commit**

```bash
git add documentation/operations-guide.adoc
git commit -m "docs: add operations guide AsciiDoc"
```

---

## Task 10: Write `user-guide.adoc`

**Files:**
- Create: `documentation/user-guide.adoc`

- [ ] **Step 1: Create the file with this exact content**

```asciidoc
= EMCIP User Guide
:toc:
:toclevels: 3
:sectnums:
:icons: font
:source-highlighter: rouge

This guide covers two audiences:

* **Part I — Admin UI**: Platform administrators using the browser-based interface at http://localhost:14009.
* **Part II — REST API**: Developers and automation scripts calling the Admin API directly at http://localhost:9087.

NOTE: For JWT internals and the full security architecture, see the _Architecture Guide_.

== Part I — Admin UI

=== Login

Open http://localhost:14009.

[plantuml,seq-admin-auth,png]
----
include::diagrams/sequence-admin-auth.puml[]
----

Enter your username and password in the login form and click *Sign In*. The UI calls `POST /api/auth/token` and stores the returned JWT in a JavaScript variable.

[IMPORTANT]
====
The JWT is stored in memory (a JS variable), not in `localStorage`. If you reload the page, you will be logged out and must sign in again. This is intentional — it prevents token theft via XSS.
====

Default credentials are set via the `ADMIN_JWT_SECRET` and admin user seed in the `admin_users` table. For local development, use the credentials from your `.env` file.

=== Tenant Management

A *tenant* is a logical boundary for data isolation. All policy rules, audit events, and moderation flags belong to exactly one tenant. This enables a single EMCIP installation to serve multiple independent communities.

==== Create a Tenant

1. Navigate to *Tenants* in the sidebar.
2. Click *New Tenant*.
3. Enter the community name in the *Name* field.
4. Click *Create*. The new tenant appears in the list with its UUID.

==== Delete a Tenant

[WARNING]
====
Deleting a tenant is irreversible. All policy rules, audit events, and moderation flags belonging to the tenant are permanently deleted via cascading database operations. There is no soft-delete or undo.
====

1. Find the tenant in the list.
2. Click the *Delete* button (trash icon).
3. Confirm in the warning dialog.

==== Tenant IDs

The UUID shown in the tenant list is the `X-Tenant-Id` value required by all API calls scoped to that tenant.

=== Policy Rules

A *policy rule* maps an intent type to a moderation action. When the Policy Engine classifies a message as a given intent, it looks up the highest-priority active rule for that intent and applies the configured action.

==== Rule Actions

[cols="1,3"]
|===
|Action |Effect

|`ALLOW`
|No action taken. Message passes through.

|`WARN`
|Emits a `moderation.flags` event with severity WARN. No message action.

|`MUTE`
|Triggers a mute action on the sender (via TDLib Adapter).

|`BAN`
|Triggers a ban action on the sender (via TDLib Adapter).
|===

==== Creating a New Rule Version

Policy rules are versioned. Creating a new version automatically deactivates the previous version for the same rule name.

1. Navigate to *Policy Rules*.
2. Click *New Rule Version*.
3. Fill in:
   * *Name*: unique identifier for this rule (e.g., `spam-block`).
   * *Action*: one of `ALLOW`, `WARN`, `MUTE`, `BAN`.
   * *Priority*: lower numbers are evaluated first.
   * *Effective From* (optional): activation date/time.
   * *Effective To* (optional): expiry date/time for time-limited rules.
4. Click *Save*. The previous version for this name is automatically deactivated.

==== Rule Version History

Click the *History* button (clock icon) next to any rule to open the version history modal. It shows all versions with their activation timestamps, deactivation timestamps, and actions.

=== Audit Log

The audit log is an append-only record of every significant event in the pipeline.

==== Event Types

[cols="1,3"]
|===
|Type |Description

|`MESSAGE_RECEIVED`
|A Telegram message was ingested by the TDLib Adapter.

|`POLICY_DECISION`
|The Policy Engine made a decision (includes ruleId and reasoning).

|`LLM_RESPONSE`
|The LLM Orchestrator generated a response (includes model and token counts).

|`MODERATION_FLAG`
|The Moderation Service flagged a message (includes rule type and pattern).
|===

==== Filtering Events

Use the filter bar at the top of the Audit Log view:

* *Event Type* — select one type or leave blank for all.
* *From* / *To* — date range picker.
* Click *Apply* to refresh results.

Results are paginated (50 per page). Use *Previous* / *Next* to navigate.

==== Inspecting Event Payload

Click any row to expand the event payload. The raw JSON is displayed with syntax highlighting for inspection.

---

== Part II — REST API Reference

All API requests require the `Authorization: Bearer <token>` header unless marked as public. Obtain a token from §5 Authentication.

=== Authentication

[cols="1,1,1,2"]
|===
|Method |Path |Auth |Description

|`POST`
|`/api/auth/token`
|None
|Issue a JWT
|===

==== Request

[source,json]
----
POST /api/auth/token
Content-Type: application/json

{
  "username": "admin",
  "password": "changeme"
}
----

==== Response

[source,json]
----
HTTP/1.1 200 OK
Content-Type: application/json

{
  "token": "eyJhbGciOiJIUzI1NiJ9..."
}
----

Use the token value in all subsequent requests:

[source,bash]
----
curl -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
     http://localhost:9087/api/tenants
----

Tokens expire after the duration configured in `app.jwt.expiration` (default: 24 hours). Re-authenticate to get a new token.

=== Tenants API

[cols="1,1,3"]
|===
|Method + Path |Description |Notes

|`GET /api/tenants`
|List all tenants
|Returns array of `{id, name, createdAt}`

|`POST /api/tenants`
|Create tenant
|Body: `{name}`. Returns created tenant with UUID.

|`DELETE /api/tenants/{id}`
|Delete tenant
|Cascades all tenant data. Irreversible.
|===

==== Create Tenant Example

[source,bash]
----
curl -X POST http://localhost:9087/api/tenants \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"name": "Community Alpha"}'
----

Response:
[source,json]
----
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "name": "Community Alpha",
  "createdAt": "2026-04-22T10:00:00Z"
}
----

=== Policy Rules API

[cols="1,1,3"]
|===
|Method + Path |Description |Notes

|`GET /api/policy-rules`
|List active rules
|Only returns `is_active = true` rules.

|`POST /api/policy-rules`
|Create new version
|Auto-deactivates previous version with same `name`.

|`GET /api/policy-rules/{name}/history`
|Version history
|All versions for the named rule, ordered by version DESC.
|===

==== Rule Schema

[source,json]
----
{
  "name": "spam-block",
  "action": "BLOCK",
  "priority": 10,
  "active": true,
  "ruleVersion": 3,
  "effectiveFrom": "2026-04-01T00:00:00Z",
  "effectiveTo": null
}
----

==== Create New Version Example

[source,bash]
----
curl -X POST http://localhost:9087/api/policy-rules \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "spam-block",
    "action": "BAN",
    "priority": 10,
    "effectiveFrom": "2026-04-22T00:00:00Z"
  }'
----

The previous version of `spam-block` (if any) is automatically deactivated.

=== Audit Events API

[cols="1,1,3"]
|===
|Method + Path |Description |Notes

|`GET /api/audit/events`
|Paginated event list
|Query params: `size` (default 50), `page` (default 0), `eventType`, `from`, `to`

|`GET /api/audit/events/{id}`
|Single event
|Full payload JSON included

|`GET /api/audit/summary`
|Count by type
|Returns `{eventType: count}` map
|===

==== Paginated Response Envelope

[source,json]
----
{
  "content": [ { ... }, { ... } ],
  "page": 0,
  "size": 50,
  "totalElements": 12453,
  "totalPages": 250
}
----

==== Filtered Query Example

[source,bash]
----
curl "http://localhost:9087/api/audit/events?eventType=POLICY_DECISION&from=2026-04-22T00:00:00Z&size=20&page=0" \
  -H "Authorization: Bearer <token>"
----

=== Moderation Rules API

[cols="1,1,3"]
|===
|Method + Path |Description |Notes

|`GET /api/admin/moderation-rules`
|List all rules
|Includes disabled rules (`enabled: false`).

|`POST /api/admin/moderation-rules`
|Create rule
|Body: `{ruleType, pattern, action, enabled}`

|`DELETE /api/admin/moderation-rules/{id}`
|Delete rule
|Permanent. Cache refreshes within 5 minutes.
|===

==== Rule Schema

[source,json]
----
{
  "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "ruleType": "KEYWORD",
  "pattern": "scam",
  "action": "FLAG",
  "enabled": true,
  "createdAt": "2026-04-22T09:00:00Z"
}
----

==== Rule Types

[cols="1,2,2"]
|===
|`ruleType` |Matches when |Pattern example

|`KEYWORD`
|Message contains the exact word (case-insensitive)
|`scam`

|`REGEX`
|Message matches Java regex pattern
|`\b(buy\|sell\|crypto)\b`

|`LENGTH`
|Message character count exceeds the numeric value
|`500`
|===

=== Health & Metrics

Every service exposes standard actuator endpoints. No authentication required.

[cols="1,1,2"]
|===
|Endpoint |Port range |Response

|`GET /actuator/health`
|9080–9087
|`{"status": "UP"}` or `{"status": "DOWN", "components": {...}}`

|`GET /actuator/prometheus`
|9080–9087
|Prometheus text format metrics scrape
|===

==== Health Check Examples

[source,bash]
----
# Check all services
for port in 9080 9081 9082 9083 9084 9085 9086 9087; do
  echo -n "Port $port: "
  curl -s http://localhost:$port/actuator/health | python3 -c \
    "import sys,json; d=json.load(sys.stdin); print(d.get('status','?'))"
done
----

Expected output when all services are healthy:
[source]
----
Port 9080: UP
Port 9081: UP
Port 9082: UP
Port 9083: UP
Port 9084: UP
Port 9085: UP
Port 9086: UP
Port 9087: UP
----
```

- [ ] **Step 2: Commit**

```bash
git add documentation/user-guide.adoc
git commit -m "docs: add user guide AsciiDoc"
```

---

## Task 11: Build verification

**Files:** None — verification only.

- [ ] **Step 1: Verify the Maven build picks up all 4 adoc files**

```bash
mvn asciidoctor:process-asciidoc -N
```

The `-N` flag runs only the root module (skips submodules). Expected output:

```
[INFO] --- asciidoctor-maven-plugin:3.0.0:process-asciidoc (generate-pdf-docs) ---
[INFO] Using AsciidoctorJ 3.x.x / Asciidoctor 2.x.x
[INFO] Processing file: documentation/architecture-guide.adoc
[INFO] Processing file: documentation/developer-guide.adoc
[INFO] Processing file: documentation/operations-guide.adoc
[INFO] Processing file: documentation/user-guide.adoc
[INFO] BUILD SUCCESS
```

- [ ] **Step 2: Verify 4 PDFs were created**

```bash
ls documentation/generated/
```

Expected:
```
architecture-guide.pdf
developer-guide.pdf
operations-guide.pdf
user-guide.pdf
```

- [ ] **Step 3: Spot-check each PDF**

Open each PDF and verify:
* `architecture-guide.pdf` — TOC visible, PlantUML diagram images render, Appendix A–D present.
* `developer-guide.pdf` — Quick Start code blocks, Kafka table, pipeline diagrams.
* `operations-guide.pdf` — Port Reference table (17 rows), troubleshooting table.
* `user-guide.pdf` — Part I and Part II sections, API example code blocks.

If PlantUML diagrams show as broken images, ensure `asciidoctorj-diagram` is on the classpath and `graphviz` is installed: `sudo apt install graphviz` (Ubuntu) or `brew install graphviz` (macOS).

- [ ] **Step 4: Commit final verification result**

```bash
git add -A
git commit -m "docs: verify documentation suite builds — 4 PDFs generated successfully"
```

- [ ] **Step 5: Open a PR**

```bash
git push origin chore/claude-config-and-docs
gh pr create \
  --title "docs: replace scattered MD files with 4-document AsciiDoc PDF suite" \
  --body "Produces architecture-guide, developer-guide, operations-guide, and user-guide PDFs. Archives 25 scattered markdown files. Adds asciidoctor-maven-plugin. Creates 3 new PlantUML diagrams and fixes 2 PUML rendering bugs."
```
