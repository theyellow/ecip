# Red Team Wave 1 — Infrastructure Hardening

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden infrastructure layer: fail-closed Kafka tenant validation, per-service DB users, database + ingress TLS, audit event publishing with tamper resistance.

**Architecture:** Six items: W1.1 adds a `validateTenantHeader()` method to `TenantAwareKafkaSupport` in emcip-core and updates all 8 Kafka consumers to reject messages with missing tenant headers (except the DLQ monitor). W1.2 creates per-service PostgreSQL roles via Liquibase. W1.3/W1.4 add SSL to database connections and real CA certs to the ingress. W1.5 publishes admin operation audit events to the existing `audit.events` Kafka topic. W1.6 adds tamper resistance (UPDATE-prevention trigger, hash chaining, retention job, verification job) to audit records.

**Tech Stack:** Java 21, Spring Boot 4, Kafka, PostgreSQL, Liquibase, Helm, Docker Compose

## Global Constraints

- Liquibase only (never Flyway)
- Spotless: `mvn spotless:apply` before every commit
- Lombok: `@Slf4j`, `@RequiredArgsConstructor`
- Kafka port 14003, use `CommonKafkaConfig` from emcip-core
- Jackson 2 annotations (`com.fasterxml.jackson.annotation`) — NOT `tools.jackson.annotation`
- Cron: never schedule at exact round times; always add offset seconds/millis

---

### Task 1: Tenant fail-closed on Kafka consumers (W1.1)

**Files:**
- Modify: `emcip-core/src/main/java/io/emcip/common/tenant/TenantAwareKafkaSupport.java`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java`
- Modify: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/TelegramMessageConsumer.java`
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/service/IntentClassificationConsumer.java`
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/PolicyDecisionConsumer.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/PolicyDecisionConsumer.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumer.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EntityEnrichmentConsumer.java`
- Modify: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/service/TelegramMessageConsumer.java`
- Modify: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/service/IntentClassificationConsumer.java`
- NOT modified: `emcip-core/src/main/java/io/emcip/common/kafka/DeadLetterQueueConsumer.java` (monitoring — must consume everything)
- NOT modified: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ManualEnrichmentConsumer.java` (tenant comes from entity, not header)
- Test: `emcip-core/src/test/java/io/emcip/common/tenant/TenantAwareKafkaSupportTest.java`

**Interfaces:**
- Produces: `TenantAwareKafkaSupport.validateTenantHeader(ConsumerRecord<?, ?> record)` returns `UUID`, throws `IllegalStateException` if header is missing or unparseable.

- [ ] **Step 1: Write the failing test for `validateTenantHeader`**

Create `emcip-core/src/test/java/io/emcip/common/tenant/TenantAwareKafkaSupportTest.java`:

```java
package io.emcip.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

class TenantAwareKafkaSupportTest {

    @Test
    void validateTenantHeader_returnsTenantUuid() {
        UUID tenantId = UUID.randomUUID();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test-topic", 0, 0, "key", "value");
        record.headers().add(new RecordHeader("tenant_id",
                tenantId.toString().getBytes(StandardCharsets.UTF_8)));

        UUID result = TenantAwareKafkaSupport.validateTenantHeader(record);

        assertThat(result).isEqualTo(tenantId);
    }

    @Test
    void validateTenantHeader_missingHeader_throws() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test-topic", 0, 0, "key", "value");

        assertThatThrownBy(() -> TenantAwareKafkaSupport.validateTenantHeader(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void validateTenantHeader_invalidUuid_throws() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test-topic", 0, 0, "key", "value");
        record.headers().add(new RecordHeader("tenant_id",
                "not-a-uuid".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> TenantAwareKafkaSupport.validateTenantHeader(record))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-core -Dtest=TenantAwareKafkaSupportTest -q 2>&1 | cat`
Expected: FAIL — `validateTenantHeader` method does not exist.

- [ ] **Step 3: Implement `validateTenantHeader` in TenantAwareKafkaSupport**

Add this method to `TenantAwareKafkaSupport.java` (after `bindTenantFromRecord`):

```java
/**
 * Extracts and validates the tenant_id header from a Kafka record.
 * @return parsed tenant UUID
 * @throws IllegalStateException if header is missing or not a valid UUID
 */
public static UUID validateTenantHeader(ConsumerRecord<?, ?> record) {
    var header = record.headers().lastHeader(TenantContext.KAFKA_HEADER);
    if (header == null) {
        throw new IllegalStateException(
                "Missing required tenant_id header on topic "
                        + record.topic() + " offset " + record.offset());
    }
    String raw = new String(header.value(), StandardCharsets.UTF_8);
    try {
        return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
        throw new IllegalStateException(
                "Invalid tenant_id header '" + raw + "' on topic "
                        + record.topic() + " offset " + record.offset(), e);
    }
}
```

Add `import java.util.UUID;` to the imports.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-core -Dtest=TenantAwareKafkaSupportTest -q 2>&1 | cat`
Expected: PASS (3 tests)

- [ ] **Step 5: Update all Kafka consumers to use `validateTenantHeader`**

For each consumer listed below, replace the current tenant header extraction with the fail-closed pattern. The general pattern is:

```java
try {
    UUID tenantId = TenantAwareKafkaSupport.validateTenantHeader(record);
    TenantContext.setTenantId(tenantId.toString());
    // ... existing processing logic ...
} catch (IllegalStateException e) {
    log.error("Rejecting record: {}", e.getMessage());
    // acknowledge to prevent redelivery loop (bad data won't fix itself)
    acknowledgment.acknowledge(); // if manual ack
    return;
} finally {
    TenantContext.clear();
}
```

**Consumer-specific changes:**

**a) AuditEventConsumer** (`emcip-audit-service/.../kafka/AuditEventConsumer.java`):
- In `processAuditEvent()`, replace the `bindTenantFromRecord` + warn-on-null pattern (lines 170-179) with `validateTenantHeader`. On `IllegalStateException`, acknowledge and return.
- Remove the `tenantUuid == null` warning block.

**b) TelegramMessageConsumer** (`emcip-intent-classifier/.../service/TelegramMessageConsumer.java`):
- Replace the inline `record.headers().lastHeader("tenant_id")` + manual decode with `validateTenantHeader`.
- Pass the resulting `UUID` to `classificationService.classify()`.
- Add `try/catch(IllegalStateException)` that logs and returns.

**c) IntentClassificationConsumer** (`emcip-policy-engine/.../service/IntentClassificationConsumer.java`):
- Replace the inline header read + `UUID.fromString` with `validateTenantHeader`.
- Add `try/catch(IllegalStateException)` that logs and returns.

**d) PolicyDecisionConsumer** (`emcip-moderation-service/.../kafka/PolicyDecisionConsumer.java`):
- Already uses `bindTenantFromRecord` and skips on null. Replace the `bindTenantFromRecord` + null-skip with `validateTenantHeader`.
- On `IllegalStateException`, acknowledge and return.

**e) PolicyDecisionConsumer** (`emcip-llm-orchestrator/.../service/PolicyDecisionConsumer.java`):
- Replace `bindTenantFromRecord` with `validateTenantHeader`.
- Add `try/catch(IllegalStateException)` that logs and returns.

**f) KnowledgeMessageConsumer** (`emcip-knowledge-engine/.../service/KnowledgeMessageConsumer.java`):
- Replace the private `extractTenantId()` method with `validateTenantHeader`.
- Remove the `extractTenantId()` helper method.

**g) EntityEnrichmentConsumer** (`emcip-knowledge-engine/.../service/EntityEnrichmentConsumer.java`):
- Replace the private `extractTenantId()` with `validateTenantHeader`.
- Remove the `extractTenantId()` helper method.

**h) TelegramMessageConsumer** (`emcip-conversation-context/.../service/TelegramMessageConsumer.java`):
- Add `validateTenantHeader` call at the top of `consume()`.
- On `IllegalStateException`, log and return.

**i) IntentClassificationConsumer** (`emcip-conversation-context/.../service/IntentClassificationConsumer.java`):
- Same pattern as (h).

- [ ] **Step 6: Run all tests across affected modules**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-core,emcip-audit-service,emcip-intent-classifier,emcip-policy-engine,emcip-moderation-service,emcip-llm-orchestrator,emcip-knowledge-engine,emcip-conversation-context -q 2>&1 | cat`
Expected: All tests pass. Fix any failures caused by consumers that now require a tenant header in their test records.

- [ ] **Step 7: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -q 2>&1 | cat
git add emcip-core/src/main/java/io/emcip/common/tenant/TenantAwareKafkaSupport.java \
        emcip-core/src/test/java/io/emcip/common/tenant/TenantAwareKafkaSupportTest.java \
        emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java \
        emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/TelegramMessageConsumer.java \
        emcip-policy-engine/src/main/java/io/emcip/policy/engine/service/IntentClassificationConsumer.java \
        emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/PolicyDecisionConsumer.java \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/PolicyDecisionConsumer.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeMessageConsumer.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EntityEnrichmentConsumer.java \
        emcip-conversation-context/src/main/java/io/emcip/conversation/context/service/TelegramMessageConsumer.java \
        emcip-conversation-context/src/main/java/io/emcip/conversation/context/service/IntentClassificationConsumer.java
git commit -m "fix(security): fail-closed tenant validation on all Kafka consumers (RT-007)

Add TenantAwareKafkaSupport.validateTenantHeader() that throws
IllegalStateException on missing/invalid tenant_id header. All consumers
now reject messages without valid tenant headers instead of proceeding
with null tenant. DeadLetterQueueConsumer and ManualEnrichmentConsumer
are excluded (monitoring and entity-sourced tenant respectively)."
```

---

### Task 2: Per-service PostgreSQL users (W1.2)

**Files:**
- Create: `emcip-admin-api/src/main/resources/db/changelog/015-create-service-roles.xml`
- Modify: `docker-compose.yml` (per-service env vars)
- Modify: `helm/emcip/templates/apps/standard-deployments.yaml` (per-service secrets)
- Modify: `helm/emcip/values.yaml` (per-service username defaults)
- Modify: 8 service `application.yml` files (datasource username env var)

**Interfaces:**
- Produces: 8 PostgreSQL roles with table-level GRANT, one per service.

- [ ] **Step 1: Create the Liquibase migration**

Create `emcip-admin-api/src/main/resources/db/changelog/015-create-service-roles.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="015-create-service-roles" author="redteam-remediation"
               context="!test">
        <comment>RT-006: Create per-service PostgreSQL roles with least-privilege grants</comment>
        <sql splitStatements="true" endDelimiter=";">
            -- Role for admin-api
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'emcip_admin_api') THEN
                    CREATE ROLE emcip_admin_api LOGIN PASSWORD 'emcip_admin_api';
                END IF;
            END $$;
            GRANT SELECT, INSERT, UPDATE, DELETE ON admin_users, tenants,
                telegram_accounts, group_profiles, account_watched_groups,
                refresh_tokens, vendor_api_keys, enrichment_sources
                TO emcip_admin_api;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO emcip_admin_api;

            -- Role for audit-service
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'emcip_audit') THEN
                    CREATE ROLE emcip_audit LOGIN PASSWORD 'emcip_audit';
                END IF;
            END $$;
            GRANT SELECT, INSERT, UPDATE, DELETE ON audit_events, metrics_snapshots TO emcip_audit;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO emcip_audit;

            -- Role for moderation-service
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'emcip_moderation') THEN
                    CREATE ROLE emcip_moderation LOGIN PASSWORD 'emcip_moderation';
                END IF;
            END $$;
            GRANT SELECT, INSERT, UPDATE, DELETE ON moderation_rules TO emcip_moderation;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO emcip_moderation;

            -- Role for policy-engine
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'emcip_policy') THEN
                    CREATE ROLE emcip_policy LOGIN PASSWORD 'emcip_policy';
                END IF;
            END $$;
            GRANT SELECT, INSERT, UPDATE, DELETE ON policy_rules, policy_decisions, policy_rule_history TO emcip_policy;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO emcip_policy;

            -- Role for intent-classifier
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'emcip_intent') THEN
                    CREATE ROLE emcip_intent LOGIN PASSWORD 'emcip_intent';
                END IF;
            END $$;
            GRANT SELECT, INSERT, UPDATE, DELETE ON intent_rules, intent_signal_config TO emcip_intent;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO emcip_intent;

            -- Role for llm-orchestrator
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'emcip_llm') THEN
                    CREATE ROLE emcip_llm LOGIN PASSWORD 'emcip_llm';
                END IF;
            END $$;
            GRANT SELECT, INSERT, UPDATE, DELETE ON llm_provider_configs, llm_cost_records,
                prompt_templates, model_configs TO emcip_llm;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO emcip_llm;

            -- Role for knowledge-engine
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'emcip_knowledge') THEN
                    CREATE ROLE emcip_knowledge LOGIN PASSWORD 'emcip_knowledge';
                END IF;
            END $$;
            GRANT SELECT, INSERT, UPDATE, DELETE ON knowledge_documents, ke_ingestion_jobs,
                concept_types, relationship_types, graph_nodes, graph_relationships,
                enrichment_connector_runs, ke_research_sessions, ke_research_evidence,
                ke_research_reports, entity_aliases, entity_resolution_queue
                TO emcip_knowledge;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO emcip_knowledge;

            -- Role for conversation-context
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'emcip_context') THEN
                    CREATE ROLE emcip_context LOGIN PASSWORD 'emcip_context';
                END IF;
            END $$;
            GRANT SELECT, INSERT, UPDATE, DELETE ON conversation_context TO emcip_context;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO emcip_context;
        </sql>
    </changeSet>
</databaseChangeLog>
```

Add an include to the admin-api master changelog (`db.changelog-master.xml`) after the last existing include:

```xml
<include file="db/changelog/015-create-service-roles.xml"/>
```

- [ ] **Step 2: Update each service's application.yml for env-configurable credentials**

For each JPA service (`emcip-policy-engine`, `emcip-llm-orchestrator`, `emcip-intent-classifier`, `emcip-knowledge-engine`, `emcip-conversation-context`), update the `spring.datasource` block:

```yaml
spring:
  datasource:
    username: ${SPRING_DATASOURCE_USERNAME:emcip}
    password: ${SPRING_DATASOURCE_PASSWORD:emcip}
```

For R2DBC services (`emcip-admin-api`, `emcip-audit-service`, `emcip-moderation-service`), update both R2DBC and Liquibase datasource blocks:

```yaml
spring:
  r2dbc:
    username: ${SPRING_R2DBC_USERNAME:emcip}
    password: ${SPRING_R2DBC_PASSWORD:emcip}
  liquibase:
    user: ${SPRING_LIQUIBASE_USERNAME:emcip}
    password: ${SPRING_LIQUIBASE_PASSWORD:emcip}
```

Note: Liquibase still runs as `emcip` (the master migration user). Only runtime connections use per-service roles.

- [ ] **Step 3: Update docker-compose.yml with per-service defaults**

For each service in `docker-compose.yml`, add per-service username/password environment variables. Example for `audit-service`:

```yaml
  audit-service:
    environment:
      SPRING_R2DBC_USERNAME: ${AUDIT_DB_USER:-emcip}
      SPRING_R2DBC_PASSWORD: ${AUDIT_DB_PASS:-emcip}
      SPRING_LIQUIBASE_USERNAME: emcip
      SPRING_LIQUIBASE_PASSWORD: emcip
```

Default to `emcip` so local dev continues to work without configuration.

- [ ] **Step 4: Update Helm standard-deployments.yaml**

In the standard-deployments template, add optional per-service secret key references. For each service, add env vars sourced from `emcip-secrets` with service-specific keys:

```yaml
- name: SPRING_DATASOURCE_USERNAME
  valueFrom:
    secretKeyRef:
      name: emcip-secrets
      key: {{ $name }}-db-user
      optional: true
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: emcip-secrets
      key: {{ $name }}-db-password
      optional: true
```

The `optional: true` ensures backwards compatibility — if the secret key doesn't exist, the application.yml default (`emcip`) is used.

- [ ] **Step 5: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -q 2>&1 | cat
git add -A
git commit -m "feat(security): per-service PostgreSQL roles with least-privilege grants (RT-006)

Creates 8 database roles (emcip_admin_api, emcip_audit, etc.) via
Liquibase migration, each granted CRUD on only their own tables. Runtime
credentials configurable per service via env vars, defaulting to emcip
for local dev. Liquibase migrations continue to run as the master user."
```

---

### Task 3: Database SSL/TLS (W1.3)

**Files:**
- Modify: 5 JPA service `application.yml` files (JDBC URL sslmode param)
- Modify: 3 R2DBC service `application.yml` files (R2DBC URL sslMode param)
- Modify: `docker-compose.yml` (postgres SSL config)
- Modify: `helm/emcip/values.yaml` (DB_SSL_MODE default)

**Interfaces:**
- Consumes: Nothing from prior tasks
- Produces: `DB_SSL_MODE` env var convention used by all services

- [ ] **Step 1: Update all JPA service application.yml files**

For each JPA service (`emcip-policy-engine`, `emcip-llm-orchestrator`, `emcip-intent-classifier`, `emcip-knowledge-engine`, `emcip-conversation-context`), append `?sslmode=${DB_SSL_MODE:disable}` to the JDBC URL:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:14005/emcip?sslmode=${DB_SSL_MODE:disable}}
```

- [ ] **Step 2: Update all R2DBC service application.yml files**

For each R2DBC service (`emcip-admin-api`, `emcip-audit-service`, `emcip-moderation-service`), append `?sslMode=${DB_SSL_MODE:disable}` to the R2DBC URL (note: camelCase `sslMode` for R2DBC driver):

```yaml
spring:
  r2dbc:
    url: ${SPRING_R2DBC_URL:r2dbc:postgresql://localhost:14005/emcip?sslMode=${DB_SSL_MODE:disable}}
```

Also append `?sslmode=${DB_SSL_MODE:disable}` to the JDBC Liquibase URL in the same files.

- [ ] **Step 3: Update Helm values.yaml**

Add `DB_SSL_MODE` env var to the global env block or each service's env in `values.yaml`:

```yaml
# Under global or per-service env
DB_SSL_MODE: "disable"  # Set to "require" in values-prod.yaml
```

- [ ] **Step 4: Update docker-compose postgres service**

Add SSL configuration to the postgres service in `docker-compose.yml` as a commented-out block (for optional local testing):

```yaml
  postgres:
    # To enable SSL locally, uncomment and mount certs:
    # command: >
    #   -c ssl=on
    #   -c ssl_cert_file=/var/lib/postgresql/server.crt
    #   -c ssl_key_file=/var/lib/postgresql/server.key
```

- [ ] **Step 5: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -q 2>&1 | cat
git add -A
git commit -m "feat(security): database SSL/TLS support via DB_SSL_MODE env var (RT-016)

All JDBC connections append ?sslmode=\${DB_SSL_MODE:disable} and R2DBC
connections append ?sslMode=\${DB_SSL_MODE:disable}. Default is disabled
for local dev; production sets DB_SSL_MODE=require."
```

---

### Task 4: Ingress TLS with real CA (W1.4)

**Files:**
- Modify: `helm/emcip/templates/ingress.yaml` (already has TLS support — verify)
- Create: `helm/emcip/templates/cluster-issuer.yaml` (Let's Encrypt ClusterIssuer)
- Modify: `helm/emcip/values.yaml` (tls config block)
- Create: `helm/emcip/values-prod.yaml` (production defaults)
- Modify: `documentation/operations-guide.adoc` (cert-manager prerequisite docs)

**Interfaces:**
- Consumes: Nothing from prior tasks
- Produces: Working Let's Encrypt TLS for ingress when enabled

- [ ] **Step 1: Verify existing ingress.yaml TLS support**

The current `helm/emcip/templates/ingress.yaml` already has `tls.enabled` conditionals and `cert-manager.io/cluster-issuer` annotations. Verify the annotation uses `{{ .Values.ingress.tls.issuer }}` — it does. No changes needed to ingress.yaml.

- [ ] **Step 2: Create ClusterIssuer template**

Create `helm/emcip/templates/cluster-issuer.yaml`:

```yaml
{{- if .Values.ingress.tls.enabled }}
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: {{ .Values.ingress.tls.issuer }}
spec:
  acme:
    server: {{ .Values.ingress.tls.acmeServer | default "https://acme-v2.api.letsencrypt.org/directory" }}
    email: {{ required "ingress.tls.email is required when TLS is enabled" .Values.ingress.tls.email }}
    privateKeySecretRef:
      name: letsencrypt-account-key
    solvers:
      - http01:
          ingress:
            class: {{ .Values.ingress.className }}
{{- end }}
```

- [ ] **Step 3: Update values.yaml with TLS config defaults**

Ensure `helm/emcip/values.yaml` has the following under `ingress`:

```yaml
ingress:
  enabled: true
  className: nginx
  host: emcip.local
  tls:
    enabled: false
    issuer: letsencrypt-prod
    email: ""
    acmeServer: ""  # defaults to Let's Encrypt production
```

- [ ] **Step 4: Create values-prod.yaml**

Create `helm/emcip/values-prod.yaml`:

```yaml
# Production overrides — use with: helm install -f values.yaml -f values-prod.yaml
ingress:
  tls:
    enabled: true
    issuer: letsencrypt-prod
    email: ""  # MUST be set during install: --set ingress.tls.email=admin@example.com

# Database SSL
DB_SSL_MODE: "require"
```

- [ ] **Step 5: Document cert-manager prerequisite**

Add a section to `documentation/operations-guide.adoc`:

```asciidoc
=== TLS Certificate Management

EMCIP uses https://cert-manager.io[cert-manager] for automated TLS certificate provisioning via Let's Encrypt.

==== Prerequisites

cert-manager must be installed cluster-wide before enabling TLS:

[source,bash]
----
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.17.2/cert-manager.yaml
kubectl wait --for=condition=Available deployment/cert-manager -n cert-manager --timeout=120s
----

==== Enabling TLS

[source,bash]
----
helm upgrade emcip ./helm/emcip \
  -f helm/emcip/values.yaml \
  -f helm/emcip/values-prod.yaml \
  --set ingress.tls.email=admin@example.com
----

This creates a `ClusterIssuer` for Let's Encrypt and configures the Ingress to use it. Certificates are automatically provisioned and renewed.
```

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
git add helm/emcip/templates/cluster-issuer.yaml \
        helm/emcip/templates/ingress.yaml \
        helm/emcip/values.yaml \
        helm/emcip/values-prod.yaml \
        documentation/operations-guide.adoc
git commit -m "feat(security): Let's Encrypt TLS via cert-manager ClusterIssuer (RT-032)

Adds ClusterIssuer template for Let's Encrypt, values-prod.yaml with
production defaults, and operations guide documentation for cert-manager
setup. Ingress template already supports TLS conditionals."
```

---

### Task 5: Audit event publishing for admin operations (W1.5)

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/audit/AdminAuditPublisher.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/UserManagementService.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/audit/AdminAuditPublisherTest.java`

**Interfaces:**
- Produces: `AdminAuditPublisher.publish(action, resourceType, resourceId, tenantId, details)` — publishes `EventSchemas.AuditEvent` to `audit.events` topic.

- [ ] **Step 1: Write the failing test for AdminAuditPublisher**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/audit/AdminAuditPublisherTest.java`:

```java
package io.emcip.admin.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class AdminAuditPublisherTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks private AdminAuditPublisher publisher;

    @Captor private ArgumentCaptor<String> valueCaptor;

    @Test
    void publish_sendsToAuditEventsTopic() {
        UUID tenantId = UUID.randomUUID();

        publisher.publish("USER_CREATED", "User", "42", "admin",
                tenantId, Map.of("username", "newuser"));

        verify(kafkaTemplate).send(eq("audit.events"), eq("42"), valueCaptor.capture());
        String json = valueCaptor.getValue();
        assertThat(json).contains("USER_CREATED");
        assertThat(json).contains("\"actor\":\"admin\"");
        assertThat(json).contains("\"resourceType\":\"User\"");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -Dtest=AdminAuditPublisherTest -q 2>&1 | cat`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement AdminAuditPublisher**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/audit/AdminAuditPublisher.java`:

```java
package io.emcip.admin.api.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuditPublisher {

    private static final String TOPIC = "audit.events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Publishes an admin audit event to the audit.events Kafka topic.
     *
     * @param action    verb (e.g. LOGIN_SUCCESS, USER_CREATED, ROLE_CHANGED)
     * @param resourceType  entity type (e.g. User, Tenant, PolicyRule)
     * @param resourceId    ID of the affected resource
     * @param actor     username of the admin performing the action
     * @param tenantId  tenant context (nullable for ADMIN users)
     * @param details   additional key-value details (nullable)
     */
    public void publish(
            String action,
            String resourceType,
            String resourceId,
            String actor,
            UUID tenantId,
            Map<String, Object> details) {
        try {
            var event = new io.emcip.common.events.EventSchemas.AuditEvent(
                    UUID.randomUUID().toString(),
                    Instant.now().toString(),
                    null, // defaults to AUDIT_EVENT_V1
                    null, // defaults to "Audit"
                    null, // no sourceEventId for admin operations
                    action,
                    actor,
                    resourceType,
                    resourceId,
                    details,
                    "SUCCESS");

            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(TOPIC, resourceId, json);

            log.debug("Published audit event: action={}, resource={}/{}, actor={}",
                    action, resourceType, resourceId, actor);
        } catch (Exception e) {
            // Audit publishing must never break the main operation
            log.error("Failed to publish audit event: action={}, error={}",
                    action, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -Dtest=AdminAuditPublisherTest -q 2>&1 | cat`
Expected: PASS

- [ ] **Step 5: Instrument AuthService**

Modify `emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java`:

- Add `AdminAuditPublisher auditPublisher` as a constructor-injected dependency.
- In `authenticate()`, after successful token generation, add:
  ```java
  auditPublisher.publish("LOGIN_SUCCESS", "Session", user.getUsername(),
          user.getUsername(), user.getTenantId(), Map.of("ip", "request-context"));
  ```
- In the `.switchIfEmpty()` error path, the 401 is thrown directly. Add an `AccessDeniedHandler` in SecurityConfig instead (see Step 7).

- [ ] **Step 6: Instrument UserManagementService**

Modify `emcip-admin-api/src/main/java/io/emcip/admin/api/service/UserManagementService.java`:

- Add `AdminAuditPublisher auditPublisher` as a constructor-injected dependency.
- In `create()`, after successful save:
  ```java
  auditPublisher.publish("USER_CREATED", "User", user.getId().toString(),
          "system", user.getTenantId(), Map.of("username", user.getUsername(), "role", user.getRole().name()));
  ```
- In `update()`, after successful save:
  ```java
  auditPublisher.publish("USER_UPDATED", "User", user.getId().toString(),
          callerUsername, user.getTenantId(), Map.of("role", req.getRole().name()));
  ```
- In `delete()`, after successful delete:
  ```java
  auditPublisher.publish("USER_DELETED", "User", id.toString(),
          callerUsername, user.getTenantId(), Map.of("username", user.getUsername()));
  ```
- In `resetPassword()`, after successful save:
  ```java
  auditPublisher.publish("PASSWORD_CHANGED", "User", id.toString(),
          "system", user.getTenantId(), null);
  ```

- [ ] **Step 7: Add AccessDeniedHandler to SecurityConfig**

Modify `emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java`:

In the `securityWebFilterChain`, add an `exceptionHandling` block after the `authorizeExchange`:

```java
.exceptionHandling(ex -> ex
    .accessDeniedHandler((exchange, denied) -> {
        String path = exchange.getRequest().getPath().value();
        String user = exchange.getPrincipal()
                .map(p -> p.getName()).blockOptional().orElse("anonymous");
        auditPublisher.publish("ACCESS_DENIED", "Endpoint", path,
                user, null, Map.of("reason", denied.getMessage()));
        return Mono.error(denied);
    }))
```

Inject `AdminAuditPublisher` into `SecurityConfig` for this.

- [ ] **Step 8: Run all admin-api tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -q 2>&1 | cat`
Expected: All tests pass. Update existing tests that mock `AuthService` or `UserManagementService` constructors to include the new `AdminAuditPublisher` mock.

- [ ] **Step 9: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -q 2>&1 | cat
git add emcip-admin-api/src/main/java/io/emcip/admin/api/audit/AdminAuditPublisher.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/audit/AdminAuditPublisherTest.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/service/UserManagementService.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java
git commit -m "feat(security): audit event publishing for admin operations (RT-017)

Adds AdminAuditPublisher that sends AuditEvent records to the
audit.events Kafka topic. Instruments login, user CRUD, password changes,
and @PreAuthorize access denials. Publishing failures are logged but
never break the main operation."
```

---

### Task 6: Audit trail tamper resistance (W1.6)

**Files:**
- Create: `emcip-audit-service/src/main/resources/db/changelog/003-audit-tamper-resistance.xml`
- Modify: `emcip-audit-service/src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/entity/AuditEventEntity.java`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java`
- Create: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditRetentionJob.java`
- Create: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditChainVerificationJob.java`
- Modify: `emcip-audit-service/src/main/resources/application.yml`
- Test: `emcip-audit-service/src/test/java/io/emcip/audit/service/service/AuditRetentionJobTest.java`
- Test: `emcip-audit-service/src/test/java/io/emcip/audit/service/service/AuditChainVerificationJobTest.java`

**Interfaces:**
- Consumes: `AuditService.save(entity)` (existing)
- Produces: `integrity_hash` and `prev_hash` columns on `audit_events`, UPDATE-prevention trigger, retention job, verification job.

- [ ] **Step 1: Create the Liquibase migration**

Create `emcip-audit-service/src/main/resources/db/changelog/003-audit-tamper-resistance.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="003-audit-tamper-resistance-columns" author="redteam-remediation">
        <comment>RT-027: Add integrity hash columns for tamper-evident audit chain</comment>
        <addColumn tableName="audit_events">
            <column name="integrity_hash" type="VARCHAR(64)"/>
            <column name="prev_hash" type="VARCHAR(64)"/>
        </addColumn>
    </changeSet>

    <changeSet id="003-audit-prevent-update-trigger" author="redteam-remediation">
        <comment>RT-027: Prevent UPDATE on audit_events rows</comment>
        <sql splitStatements="false">
            CREATE OR REPLACE FUNCTION prevent_audit_update() RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'audit_events rows cannot be updated';
            END;
            $$ LANGUAGE plpgsql;

            CREATE TRIGGER audit_no_update
                BEFORE UPDATE ON audit_events
                FOR EACH ROW
                EXECUTE FUNCTION prevent_audit_update();
        </sql>
        <rollback>
            DROP TRIGGER IF EXISTS audit_no_update ON audit_events;
            DROP FUNCTION IF EXISTS prevent_audit_update();
        </rollback>
    </changeSet>
</databaseChangeLog>
```

Add include to `db.changelog-master.xml`:

```xml
<include file="db/changelog/003-audit-tamper-resistance.xml"/>
```

- [ ] **Step 2: Update AuditEventEntity with new columns**

Add to `emcip-audit-service/src/main/java/io/emcip/audit/service/entity/AuditEventEntity.java`:

```java
@Column(name = "integrity_hash", length = 64)
private String integrityHash;

@Column(name = "prev_hash", length = 64)
private String prevHash;
```

- [ ] **Step 3: Add hash computation to AuditService**

Modify `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java` to compute hashes on insert. Add a method:

```java
private String computeIntegrityHash(AuditEventEntity entity) {
    String input = entity.getEventId()
            + "|" + entity.getCreatedAt()
            + "|" + entity.getEventType()
            + "|" + entity.getActorId()
            + "|" + entity.getResourceType()
            + "|" + entity.getResourceId();
    return sha256Hex(input);
}

private String sha256Hex(String input) {
    try {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var sb = new StringBuilder(64);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
    }
}
```

Before saving, fetch the last record's `integrityHash` to use as `prevHash`, then compute and set both fields. Since this is R2DBC/reactive, the chain query must be:

```java
public Mono<AuditEventEntity> saveWithChain(AuditEventEntity entity) {
    return repository.findTopByOrderByIdDesc()
            .map(AuditEventEntity::getIntegrityHash)
            .defaultIfEmpty("")
            .flatMap(prevHash -> {
                entity.setPrevHash(prevHash.isEmpty() ? null : prevHash);
                entity.setIntegrityHash(computeIntegrityHash(entity));
                return repository.save(entity);
            });
}
```

Add `findTopByOrderByIdDesc()` to the repository:

```java
Mono<AuditEventEntity> findTopByOrderByIdDesc();
```

Note: Since the UPDATE trigger now blocks updates, the `AuditService` must use INSERT only (no update-after-save). The R2DBC `repository.save()` on a new entity does an INSERT, which is fine. Ensure no code path re-saves an existing audit entity.

- [ ] **Step 4: Add audit retention configuration**

Add to `emcip-audit-service/src/main/resources/application.yml`:

```yaml
audit:
  retention: ${AUDIT_RETENTION:P10Y}
  chain-verification:
    batch-size: 1000
    cron: "0 17 3 * * *"  # 03:00:17 daily (offset seconds per project convention)
  retention-cleanup:
    cron: "0 42 4 1 * *"  # 04:00:42 on 1st of month
```

- [ ] **Step 5: Implement AuditRetentionJob**

Create `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditRetentionJob.java`:

```java
package io.emcip.audit.service.service;

import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRetentionJob {

    private final AuditService auditService;

    @Value("${audit.retention:P10Y}")
    private String retentionPeriod;

    @Scheduled(cron = "${audit.retention-cleanup.cron:0 42 4 1 * *}")
    public void cleanupExpiredRecords() {
        Duration retention = Duration.parse(retentionPeriod);
        Instant cutoff = Instant.now().minus(retention);

        log.info("Starting audit retention cleanup, deleting records before {}", cutoff);

        auditService.deleteRecordsOlderThan(cutoff)
                .doOnSuccess(count -> log.info("Audit retention cleanup complete, deleted {} records", count))
                .doOnError(e -> log.error("Audit retention cleanup failed: {}", e.getMessage()))
                .subscribe();
    }
}
```

Add `deleteRecordsOlderThan(Instant cutoff)` to `AuditService`:

```java
public Mono<Long> deleteRecordsOlderThan(Instant cutoff) {
    return repository.findOldestBeforeCutoff(cutoff)
            .flatMap(oldest -> {
                // Record anchor event before purge
                String anchorHash = oldest.getIntegrityHash();
                return repository.deleteByCreatedAtBefore(cutoff)
                        .doOnSuccess(count -> {
                            if (count > 0) {
                                log.info("Purged {} audit records, anchor hash: {}", count, anchorHash);
                            }
                        });
            })
            .defaultIfEmpty(0L);
}
```

- [ ] **Step 6: Implement AuditChainVerificationJob**

Create `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditChainVerificationJob.java`:

```java
package io.emcip.audit.service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditChainVerificationJob {

    private final AuditService auditService;

    @Value("${audit.chain-verification.batch-size:1000}")
    private int batchSize;

    @Scheduled(cron = "${audit.chain-verification.cron:0 17 3 * * *}")
    public void verifyRecentChain() {
        log.info("Starting audit chain verification for last {} records", batchSize);

        auditService.verifyChain(batchSize)
                .doOnSuccess(result -> {
                    if (result.valid()) {
                        log.info("Audit chain verification passed: {} records verified", result.recordsChecked());
                    } else {
                        log.error("CRITICAL: Audit chain integrity violation detected at record {}! "
                                + "Expected prevHash={}, found={}", result.brokenAtId(),
                                result.expectedHash(), result.actualHash());
                    }
                })
                .doOnError(e -> log.error("Audit chain verification failed: {}", e.getMessage()))
                .subscribe();
    }
}
```

Add `verifyChain(int batchSize)` to `AuditService` that returns `Mono<ChainVerificationResult>`. The method queries the last N records ordered by ID descending, walks the chain comparing each record's `prevHash` with the previous record's `integrityHash`.

```java
public record ChainVerificationResult(
        boolean valid, int recordsChecked,
        Long brokenAtId, String expectedHash, String actualHash) {

    public static ChainVerificationResult ok(int count) {
        return new ChainVerificationResult(true, count, null, null, null);
    }

    public static ChainVerificationResult broken(int count, Long id, String expected, String actual) {
        return new ChainVerificationResult(false, count, id, expected, actual);
    }
}
```

- [ ] **Step 7: Run audit-service tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-audit-service -q 2>&1 | cat`
Expected: All tests pass. Existing tests that call `auditService.save()` may need adjustment if the method signature changed. The UPDATE trigger only exists in real PostgreSQL, not in H2/test, so unit tests won't hit it.

- [ ] **Step 8: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -q 2>&1 | cat
git add emcip-audit-service/
git commit -m "feat(security): audit trail tamper resistance with hash chaining (RT-027)

Adds UPDATE-prevention trigger on audit_events via Liquibase. Implements
SHA-256 hash chaining (integrity_hash + prev_hash) on insert.
Configurable retention cleanup (default P10Y) with chain anchor.
Periodic chain verification job logs CRITICAL on integrity violations."
```
