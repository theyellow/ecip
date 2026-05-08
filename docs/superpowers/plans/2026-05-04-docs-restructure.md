# Documentation Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Telegram section to the user guide, create a dedicated Docker Compose guide, and clean up the operations guide to be Kubernetes-focused.

**Architecture:** Three independent doc edits: (1) user-guide.adoc gains Telegram content in Part I and Part II; (2) a new docker-compose-guide.adoc is created from Appendix A + extracted Docker Compose subsections; (3) operations-guide.adoc is stripped of Docker Compose detail and references the new guide instead.

**Tech Stack:** AsciiDoc, no toolchain — verify by reading the resulting file after each change.

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `documentation/user-guide.adoc` | Modify | Add Telegram Accounts section (Admin UI) + Telegram API section |
| `documentation/docker-compose-guide.adoc` | Create | Standalone Docker Compose guide (Appendix A + extracted subsections) |
| `documentation/operations-guide.adoc` | Modify | Remove Appendix A + Docker Compose subsections; add references to new guide |

---

### Task 1: Add Telegram section to user-guide.adoc

**Files:**
- Modify: `documentation/user-guide.adoc` — insert after Audit Log section (before the `---` at line 143) and add API section before Health & Metrics

- [ ] **Step 1: Insert the Telegram Admin UI section**

In `documentation/user-guide.adoc`, replace the `---` separator between Part I and Part II (the line at line 143) with the Telegram section followed by the separator:

Replace:
```
---

== Part II — REST API Reference
```

With:
```
=== Telegram Account Management

EMCIP connects to Telegram as a full user-bot client (not a bot API). Each Telegram account
authenticates interactively — the platform sends a code to the account's phone and requires
entry via the Admin UI.

==== Adding a Telegram Account

. Navigate to *Telegram* in the sidebar.
. Click *Add Account*.
. Enter the phone number in international format (e.g., `+491234567890`).
. Click *Send Code*. Telegram sends a verification code to the phone.
. Enter the received code in the *Verification Code* field.
. Click *Authenticate*. The account status changes to *ACTIVE* when the session is established.

NOTE: The verification code arrives as a Telegram message from the Telegram service itself.
Check the Telegram app on the registered phone to retrieve it.

[WARNING]
====
Each Telegram account can only be active in one session at a time. Re-authenticating an
account after session expiry requires removing and re-adding it.
====

==== Account Status Values

[cols="1,3"]
|===
|Status |Meaning

|`PENDING`
|Account created; authentication not started.

|`AWAITING_CODE`
|Verification code sent to phone. Waiting for the operator to enter the code.

|`ACTIVE`
|Session established. The account is receiving Telegram updates.

|`FAILED`
|Authentication failed (wrong code, code expired, or session error).
Remove and re-add the account to retry.
|===

==== Watching Groups

Once an account is *ACTIVE*, you configure which Telegram groups it monitors.
EMCIP only publishes messages from *watched* groups to the processing pipeline —
unwatched groups are silently ignored.

===== Discovering Groups

. Find the account in the accounts table.
. Click *Groups*. An inline panel expands showing the account's watched groups.
. Click *Discover Groups*. The platform queries TDLib to list all groups the account
  is currently a member of.
. The discover modal shows: Name, Member Count, Type, and a *Watch* button per group.

NOTE: If the list appears empty, click *Refresh* in the modal header. TDLib may take a few
seconds to sync chats after a fresh session.

===== Watching a Group

In the Discover Groups modal, click *Watch* next to the group. The group moves to the watched
sub-table immediately. The *Watch* button is replaced by *Watching* (grayed out).

===== Unwatching a Group

In the watched groups sub-table, click *Unwatch*. Messages from that group stop reaching the
pipeline immediately.

===== Joining by Invite Link

At the bottom of the Discover Groups modal, enter a Telegram invite link
(e.g., `https://t.me/+abc123`) in the *Invite link* field and click *Join & Watch*.
The platform joins the group via TDLib and automatically adds it to the watch list.

NOTE: The account must be able to join the group. Restricted groups return an inline error.

==== Multi-Account Deduplication

When multiple accounts watch the same group, EMCIP deduplicates messages — each message
reaches the processing pipeline exactly once, regardless of how many accounts received it.

---

== Part II — REST API Reference
```

- [ ] **Step 2: Insert the Telegram Accounts API section**

In `documentation/user-guide.adoc`, replace:
```
=== Health & Metrics
```
with:
```
=== Telegram Accounts API

The Telegram Accounts API manages user-bot account lifecycle and group watch configuration.
All endpoints require `Authorization: Bearer <token>`.

==== Account Management

[cols="1,1,3"]
|===
|Method + Path |Description |Notes

|`GET /api/telegram/accounts`
|List accounts
|Returns array of `{id, phoneNumber, status, createdAt}`

|`POST /api/telegram/accounts`
|Add account
|Body: `{phoneNumber}`. Creates account in `PENDING` state.

|`GET /api/telegram/accounts/{id}/status`
|Get status
|Returns `{id, status}`. Poll after code submission to detect `ACTIVE`.
|===

==== Group Watching

[cols="1,1,3"]
|===
|Method + Path |Description |Notes

|`GET /api/telegram/accounts/{id}/chats`
|Discover groups
|Returns groups the account is a member of: `[{chatId, title, memberCount, type}]`.
Account must be ACTIVE.

|`GET /api/telegram/accounts/{id}/watched`
|List watched groups
|Returns `[{chatId, groupProfileId, name, moderationLevel}]`.

|`POST /api/telegram/accounts/{id}/watch`
|Watch a group
|Body: `{chatId, title, memberCount}`. Returns 201 with GroupProfile. Idempotent.

|`DELETE /api/telegram/accounts/{id}/watch/{chatId}`
|Unwatch a group
|Returns 204. GroupProfile is not deleted (other accounts may reference it).

|`POST /api/telegram/accounts/{id}/join`
|Join & watch by invite link
|Body: `{inviteLink}`. Joins via TDLib then adds to watch list. Returns 201 with GroupProfile.
|===

==== Watch a Group Example

[source,bash]
----
curl -X POST http://localhost:9087/api/telegram/accounts/<account-id>/watch \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "chatId": -1001234567890,
    "title": "Community Alpha Chat",
    "memberCount": 1500
  }'
----

Response:

[source,json]
----
HTTP/1.1 201 Created
Content-Type: application/json

{
  "id": 42,
  "telegramChatId": -1001234567890,
  "name": "Community Alpha Chat",
  "moderationLevel": "STANDARD"
}
----

==== Unwatch a Group Example

[source,bash]
----
curl -X DELETE \
  "http://localhost:9087/api/telegram/accounts/<account-id>/watch/-1001234567890" \
  -H "Authorization: Bearer <token>"
----

Response: `HTTP/1.1 204 No Content`

=== Health & Metrics
```

- [ ] **Step 3: Verify the result**

Read `documentation/user-guide.adoc` and confirm:
- "Telegram Account Management" section appears in Part I (after Audit Log)
- "Telegram Accounts API" section appears in Part II (before Health & Metrics)
- No unclosed AsciiDoc blocks (every `====` or `|===` that opens must close)

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip
git add documentation/user-guide.adoc
git commit -m "docs(user-guide): add Telegram account management and group watching section"
```

---

### Task 2: Create docker-compose-guide.adoc

**Files:**
- Create: `documentation/docker-compose-guide.adoc`

This guide consolidates all Docker Compose content: Appendix A (Quick Start, Profiles, .env, Port Reference) + Docker Compose subsections extracted from Operations Guide (Default Credentials, Observability URLs, DLQ monitoring, Troubleshooting, Backup & Restore).

- [ ] **Step 1: Write the file**

Create `documentation/docker-compose-guide.adoc` with the following content:

```adoc
= EMCIP Docker Compose Guide
:toc:
:toclevels: 3
:sectnums:
:icons: font
:source-highlighter: rouge

NOTE: Docker Compose is the local development and quick-patch environment.
For production deployments, see the _Operations Guide_.

[plantuml,deploy-local,png]
----
include::diagrams/deployment-local-docker.puml[]
----

== Infrastructure Overview

The local Docker Compose environment runs 8 application services and 9 infrastructure services:

[cols="2,3"]
|===
|Category |Services

|Application (8)
|tdlib-adapter, conversation-context, intent-classifier, policy-engine, llm-orchestrator,
moderation-service, audit-service, admin-api

|Infrastructure (9)
|Zookeeper, Kafka broker, Kafka UI, PostgreSQL, pgAdmin, Grafana, Loki, Promtail, Admin UI
|===

== Quick Start

=== Default Startup (infrastructure only)

[source,bash]
----
docker compose up -d
----

Starts: Zookeeper, Kafka, Kafka UI, PostgreSQL, pgAdmin. Application services are not started
by default — they are managed per-profile.

== Profiles

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

== .env File Setup

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

== Default Credentials

[cols="2,1,1,1,2"]
|===
|UI |Port |Username |Password |How to change

|Admin UI
|14009
|`admin`
|`changeme`
|Update `admin_users` table or edit `emcip-admin-api/.../db/changelog/changes/002-seed-admin-user.xml`

|pgAdmin
|14006
|`admin@ecip.io`
|`admin`
|`docker-compose.yml` — `PGADMIN_DEFAULT_EMAIL` / `PGADMIN_DEFAULT_PASSWORD`

|Grafana
|14007
|`admin`
|`admin`
|`docker-compose.yml` — `GF_SECURITY_ADMIN_USER` / `GF_SECURITY_ADMIN_PASSWORD`

|Kafka UI
|14004
|(none)
|(none)
|No auth — open access for local development
|===

To change the Admin UI password on a running Docker Compose instance:

[source,bash]
----
# Generate a new bcrypt hash (rounds=12)
python3 -c "import bcrypt; print(bcrypt.hashpw(b'newpassword', bcrypt.gensalt(12)).decode())"

# Apply it
docker exec -it $(docker compose ps -q postgres) \
  psql -U emcip -d emcip -c \
  "UPDATE admin_users SET password_hash = '<hash>' WHERE username = 'admin';"
----

== Observability

=== Grafana Dashboards

Open http://localhost:14007 (admin / admin).

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

Open http://localhost:14008 or Grafana → Explore → Loki datasource.

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

== Error Handling & DLQ

=== Monitoring DLQ

[source,bash]
----
# View DLQ via Kafka UI
open http://localhost:14004

# CLI
docker exec -it $(docker compose ps -q kafka) \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:14002 \
    --topic telegram.raw.messages.dlq \
    --from-beginning
----

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
|`docker compose logs <service> \| grep Liquibase`
|A changeset is locked or malformed. Run `mvn liquibase:releaseLocks -pl <module>` against the dev DB.

|TDLib auth fails
|`docker compose logs tdlib-adapter`
|Add `TELEGRAM_PHONE_NUMBER=+<number>` to `.env` and restart.

|Logback startup errors
|`docker compose logs <service> \| grep logback`
|Ensure `logstash-logback-encoder` is NOT on the classpath.
Use `logging.structured.format.console: logstash` in `application.yml` instead.

|Grafana shows no data
|`curl http://localhost:14008/ready`
|Loki not ready. Wait 30s and retry. Check `docker compose logs loki`.
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
```

- [ ] **Step 2: Verify the file**

Read `documentation/docker-compose-guide.adoc` and confirm:
- File starts with `= EMCIP Docker Compose Guide`
- All sections present: Infrastructure Overview, Quick Start, Profiles, .env, Port Reference, Default Credentials, Observability, Troubleshooting, Backup & Restore
- No unclosed AsciiDoc blocks

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
git add documentation/docker-compose-guide.adoc
git commit -m "docs: add standalone Docker Compose guide"
```

---

### Task 3: Clean up operations-guide.adoc

**Files:**
- Modify: `documentation/operations-guide.adoc` — remove Docker Compose subsections throughout, remove Appendix A, add references to docker-compose-guide.adoc

Apply the following 7 edits in order. Each `old_string` is unique in the file.

- [ ] **Step 1: Update Docker Compose deployment path reference**

Replace:
```
Docker Compose is the recommended local development and quick-patch environment.
Use it when developing features, debugging locally, or testing configuration changes before rolling to Kubernetes.
See <<appendix-docker-compose>> for setup instructions.
```

With:
```
Docker Compose is the recommended local development and quick-patch environment.
Use it when developing features, debugging locally, or testing configuration changes before rolling to Kubernetes.
See the _Docker Compose Guide_ (`documentation/docker-compose-guide.adoc`) for setup instructions.
```

- [ ] **Step 2: Clean up Observability - Grafana section**

Replace:
```
In Kubernetes: open http://emcip.local/grafana (admin / <grafana-admin-password> from K8s secret). +
In Docker Compose: open http://localhost:14007 (admin / admin).
```

With:
```
Open http://emcip.local/grafana (admin / <grafana-admin-password> from K8s secret).
For Docker Compose, see the _Docker Compose Guide_.
```

- [ ] **Step 3: Clean up Observability - Loki section**

Replace:
```
In Kubernetes: Grafana → Explore → Loki datasource. +
In Docker Compose: http://localhost:14008 or Grafana → Explore.
```

With:
```
Grafana → Explore → Loki datasource (http://emcip.local/grafana).
For Docker Compose, see the _Docker Compose Guide_.
```

- [ ] **Step 4: Remove Docker Compose Default Credentials subsection**

Replace:
```
==== Docker Compose

[cols="2,1,1,1,2"]
|===
|UI |Port |Username |Password |How to change

|Admin UI
|14009
|`admin`
|`changeme`
|Update `admin_users` table or edit `emcip-admin-api/.../db/changelog/changes/002-seed-admin-user.xml`

|pgAdmin
|14006
|`admin@ecip.io`
|`admin`
|`docker-compose.yml` — `PGADMIN_DEFAULT_EMAIL` / `PGADMIN_DEFAULT_PASSWORD`

|Grafana
|14007
|`admin`
|`admin`
|`docker-compose.yml` — `GF_SECURITY_ADMIN_USER` / `GF_SECURITY_ADMIN_PASSWORD`

|Kafka UI
|14004
|(none)
|(none)
|No auth — open access for local development
|===

To change the Admin UI password on a running Docker Compose instance:

[source,bash]
----
# Generate a new bcrypt hash (rounds=12)
python3 -c "import bcrypt; print(bcrypt.hashpw(b'newpassword', bcrypt.gensalt(12)).decode())"

# Apply it
docker exec -it $(docker compose ps -q postgres) \
  psql -U emcip -d emcip -c \
  "UPDATE admin_users SET password_hash = '<hash>' WHERE username = 'admin';"
----

=== Performance Tuning
```

With:
```
For Docker Compose default credentials, see the _Docker Compose Guide_.

=== Performance Tuning
```

- [ ] **Step 5: Remove Docker Compose DLQ monitoring commands**

Replace:
```
[source,bash]
----
# Kubernetes — view DLQ via Kafka CLI
kubectl exec -it $(kubectl get pod -l app=emcip-kafka -n emcip \
  -o jsonpath='{.items[0].metadata.name}') -n emcip -- \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic telegram.raw.messages.dlq \
    --from-beginning

# Docker Compose — view DLQ via Kafka UI (port 14004)
open http://localhost:14004

# Docker Compose — CLI
docker exec -it $(docker compose ps -q kafka) \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:14002 \
    --topic telegram.raw.messages.dlq \
    --from-beginning
----
```

With:
```
[source,bash]
----
# Kubernetes — view DLQ via Kafka CLI
kubectl exec -it $(kubectl get pod -l app=emcip-kafka -n emcip \
  -o jsonpath='{.items[0].metadata.name}') -n emcip -- \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic telegram.raw.messages.dlq \
    --from-beginning
----

For Docker Compose DLQ monitoring, see the _Docker Compose Guide_.
```

- [ ] **Step 6: Remove Docker Compose rows from Troubleshooting table**

Replace:
```
|Port already in use (Docker Compose)
|`lsof -i :<port>`
|Stop the process using that port, or remap in `docker-compose.yml`.

|Kafka `Connection refused` (Docker Compose)
|`docker compose ps kafka`
|Ensure `KAFKA_BOOTSTRAP_SERVERS=localhost:14003` in `.env`. Internal services use `kafka:14002`.

|PostgreSQL `Connection refused` (Docker Compose)
|`docker compose ps postgres`
|Wait for Liquibase migration to complete. Check `docker compose logs postgres`.

|Liquibase migration fails on startup (Docker Compose)
|`docker compose logs <service> \| grep Liquibase`
|A changeset is locked or malformed. Run `mvn liquibase:releaseLocks -pl <module>` against the dev DB.

|TDLib auth fails (Docker Compose)
|`docker compose logs tdlib-adapter`
|Add `TELEGRAM_PHONE_NUMBER=+<number>` to `.env` and restart.

|Logback startup errors
|`docker compose logs <service> \| grep logback`
|Ensure `logstash-logback-encoder` is NOT on the classpath. Use `logging.structured.format.console: logstash` in `application.yml` instead.

|Grafana shows no data (Docker Compose)
|`curl http://localhost:14008/ready`
|Loki not ready. Wait 30s and retry. Check `docker compose logs loki`.
|===
```

With:
```
|===

For Docker Compose troubleshooting, see the _Docker Compose Guide_.
```

- [ ] **Step 7: Replace Docker Compose Backup & Restore subsections and remove Appendix A**

Replace everything from the Docker Compose backup subsection through the end of the file:
```
==== Docker Compose

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

==== Kubernetes

[source,bash]
----
# Step 1: Scale down application services
kubectl scale deployment -l app.kubernetes.io/instance=emcip -n emcip --replicas=0

# Step 2: Copy dump file into postgres pod
PGPOD=$(kubectl get pod -l app=emcip-postgres -n emcip -o jsonpath='{.items[0].metadata.name}')
kubectl cp backup_20260422_120000.dump emcip/"$PGPOD":/tmp/backup.dump

# Step 3: Restore
kubectl exec "$PGPOD" -n emcip -- \
  pg_restore -U emcip -d emcip --clean /tmp/backup.dump

# Step 4: Restart services
kubectl scale deployment -l app.kubernetes.io/instance=emcip -n emcip --replicas=1
----

==== Docker Compose

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

[[appendix-docker-compose]]
[appendix]
== Appendix A: Docker Compose

NOTE: Docker Compose is the local development and quick-patch environment.
For production deployments, see <<kubernetes-deployment>>.

=== Infrastructure Overview

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

=== Quick Start

==== Default Startup (infrastructure only)

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

=== .env File Setup

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

=== Port Reference

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

==== Port Conflict Check

[source,bash]
----
for port in 9080 9081 9082 9083 9084 9085 9086 9087 \
            14001 14002 14003 14004 14005 14006 14007 14008 14009; do
  if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "CONFLICT: port $port in use"
  fi
done
----
```

With:
```
For Docker Compose backup and restore, see the _Docker Compose Guide_.

=== Restore Procedure

[source,bash]
----
# Step 1: Scale down application services
kubectl scale deployment -l app.kubernetes.io/instance=emcip -n emcip --replicas=0

# Step 2: Copy dump file into postgres pod
PGPOD=$(kubectl get pod -l app=emcip-postgres -n emcip -o jsonpath='{.items[0].metadata.name}')
kubectl cp backup_20260422_120000.dump emcip/"$PGPOD":/tmp/backup.dump

# Step 3: Restore
kubectl exec "$PGPOD" -n emcip -- \
  pg_restore -U emcip -d emcip --clean /tmp/backup.dump

# Step 4: Restart services
kubectl scale deployment -l app.kubernetes.io/instance=emcip -n emcip --replicas=1
----

For Docker Compose restore, see the _Docker Compose Guide_.
```

- [ ] **Step 8: Verify the result**

Read `documentation/operations-guide.adoc` and confirm:
- File ends after the Kubernetes restore steps (no more Appendix A)
- No more `docker compose` commands remain in the file (except the single Deployment Paths intro section which was already clean)
- References to "Docker Compose Guide" appear where content was removed
- `<<appendix-docker-compose>>` reference is gone

Run to confirm no `docker compose` commands remain:
```bash
grep -n "docker compose" documentation/operations-guide.adoc
```
Expected: 0 matches (or only references like "see the Docker Compose Guide").

- [ ] **Step 9: Commit**

```bash
cd /home/ben/Development/ecip
git add documentation/operations-guide.adoc
git commit -m "docs(ops-guide): extract Docker Compose content to dedicated guide, K8s-focus ops guide"
```
