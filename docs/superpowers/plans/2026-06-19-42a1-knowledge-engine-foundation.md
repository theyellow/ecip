# Epic 42 — Knowledge Enrichment: Knowledge-Engine Foundation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add DB schema, JPA layer, connector interface, API key resolution, 6-stage ingestion pipeline, cron scheduler, and Kafka consumers to `emcip-knowledge-engine` — everything needed before the first real connector can be plugged in.

**Architecture:** New tables (`ke_vendor_api_keys`, `ke_enrichment_sources`, `ke_enrichment_runs`) live in knowledge-engine's Liquibase changelog. All business logic (pipeline, scheduler, consumers) is JPA/blocking; connectors return `List<EnrichmentResult>` — no reactive types here. The `StubConnector` used in tests is a test-only Spring bean registered via `@TestConfiguration`.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, Liquibase XML, `@Scheduled`, `@KafkaListener`, RestClient (for connector HTTP calls in Plan B), Testcontainers (`@IntegrationTest`)

**Spec:** `docs/superpowers/specs/2026-06-19-42-knowledge-enrichment-connectors-design.md`

---

## File Map

**New — Liquibase**
- `emcip-knowledge-engine/src/main/resources/db/changelog/changes/011-create-vendor-api-keys.xml`
- `emcip-knowledge-engine/src/main/resources/db/changelog/changes/012-create-enrichment-sources.xml`
- `emcip-knowledge-engine/src/main/resources/db/changelog/changes/013-create-enrichment-runs.xml`
- `emcip-knowledge-engine/src/main/resources/db/changelog/changes/014-seed-enrichment-sources.xml`

**Modify**
- `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml` — add 4 `<include>` entries

**New — JPA entities**
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/TriggerType.java`
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/RunStatus.java`
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/VendorApiKey.java`
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/EnrichmentSource.java`
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/EnrichmentRun.java`

**New — JPA repositories**
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/VendorApiKeyRepository.java`
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/EnrichmentSourceRepository.java`
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/EnrichmentRunRepository.java`

**New — Connector interface package** (`io.emcip.knowledge.engine.connector`)
- `KnowledgeConnector.java`
- `ConnectorContext.java`
- `EnrichmentRequest.java`
- `EnrichmentResult.java`
- `TriggerMode.java`
- `ConnectorException.java`
- `EnrichmentConnectorRegistry.java`

**New — Enrichment services**
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ApiKeyResolver.java`
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EnrichmentPipelineService.java`
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EnrichmentScheduler.java`

**New — Kafka consumers**
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EntityEnrichmentConsumer.java`
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ManualEnrichmentConsumer.java`

**Modify — existing services**
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeEventPublisher.java` — add `publishEntityCreated`
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java` — call publisher after entity extraction
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/KnowledgeEngineApplication.java` — add `@EnableScheduling`

**New — existing `KnowledgeDocumentRepository`**
- Add `existsBySourceRefAndSourceType` query method (deduplication check)

**New — tests**
- `src/test/java/io/emcip/knowledge/engine/repository/EnrichmentRepositoryTest.java`
- `src/test/java/io/emcip/knowledge/engine/service/ApiKeyResolverTest.java`
- `src/test/java/io/emcip/knowledge/engine/service/EnrichmentPipelineIntegrationTest.java`
- `src/test/java/io/emcip/knowledge/engine/service/EnrichmentSchedulerTest.java`
- `src/test/java/io/emcip/knowledge/engine/connector/TestStubConnector.java`

---

## Task 1: Liquibase migrations 011–014

**Files:**
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/011-create-vendor-api-keys.xml`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/012-create-enrichment-sources.xml`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/013-create-enrichment-runs.xml`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/014-seed-enrichment-sources.xml`
- Modify: `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Create 011-create-vendor-api-keys.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-11" author="knowledge-engine">
        <createTable tableName="ke_vendor_api_keys"
                     remarks="Global and per-tenant API keys for enrichment connectors">
            <column name="id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="vendor_id" type="VARCHAR(64)">
                <constraints nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID">
                <constraints nullable="true"/>
            </column>
            <column name="api_key" type="VARCHAR(512)">
                <constraints nullable="false"/>
            </column>
            <column name="enabled" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP WITH TIME ZONE" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP WITH TIME ZONE" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <sql>
            ALTER TABLE ke_vendor_api_keys
            ADD CONSTRAINT uq_ke_vendor_api_keys_vendor_tenant
            UNIQUE NULLS NOT DISTINCT (vendor_id, tenant_id);
        </sql>

        <createIndex indexName="idx_ke_vendor_api_keys_vendor"
                     tableName="ke_vendor_api_keys">
            <column name="vendor_id"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Create 012-create-enrichment-sources.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-12" author="knowledge-engine">
        <createTable tableName="ke_enrichment_sources"
                     remarks="Per-vendor enrichment source configuration with schedule">
            <column name="id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="vendor_id" type="VARCHAR(64)">
                <constraints nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID">
                <constraints nullable="true"/>
            </column>
            <column name="enabled" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="schedule_cron" type="VARCHAR(64)">
                <constraints nullable="true"/>
            </column>
            <column name="last_run_at" type="TIMESTAMP WITH TIME ZONE">
                <constraints nullable="true"/>
            </column>
            <column name="last_run_status" type="VARCHAR(16)">
                <constraints nullable="true"/>
            </column>
            <column name="config" type="JSONB" defaultValue="{}">
                <constraints nullable="false"/>
            </column>
            <column name="version" type="BIGINT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP WITH TIME ZONE" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP WITH TIME ZONE" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_ke_enrichment_sources_vendor_tenant"
                     tableName="ke_enrichment_sources">
            <column name="vendor_id"/>
            <column name="tenant_id"/>
        </createIndex>

        <createIndex indexName="idx_ke_enrichment_sources_enabled"
                     tableName="ke_enrichment_sources">
            <column name="enabled"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Create 013-create-enrichment-runs.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-13" author="knowledge-engine">
        <createTable tableName="ke_enrichment_runs"
                     remarks="Audit log of enrichment pipeline executions">
            <column name="id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="source_id" type="UUID">
                <constraints nullable="false"
                             foreignKeyName="fk_ke_enrichment_runs_source"
                             references="ke_enrichment_sources(id)"/>
            </column>
            <column name="trigger_type" type="VARCHAR(16)">
                <constraints nullable="false"/>
            </column>
            <column name="started_at" type="TIMESTAMP WITH TIME ZONE" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
            <column name="completed_at" type="TIMESTAMP WITH TIME ZONE">
                <constraints nullable="true"/>
            </column>
            <column name="status" type="VARCHAR(16)" defaultValue="RUNNING">
                <constraints nullable="false"/>
            </column>
            <column name="items_fetched" type="INT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
            <column name="items_ingested" type="INT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
            <column name="error_message" type="VARCHAR(1024)">
                <constraints nullable="true"/>
            </column>
        </createTable>

        <createIndex indexName="idx_ke_enrichment_runs_source_started"
                     tableName="ke_enrichment_runs">
            <column name="source_id"/>
            <column name="started_at" descending="true"/>
        </createIndex>

        <createIndex indexName="idx_ke_enrichment_runs_status"
                     tableName="ke_enrichment_runs">
            <column name="status"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 4: Create 014-seed-enrichment-sources.xml**

One global source row per vendor with a non-round cron offset (6-field Spring format: seconds minutes hours day month weekday).

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-14" author="knowledge-engine">
        <!-- tenant_id NULL = global default. Cron offsets are non-round per CLAUDE.md rule. -->
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="wikipedia"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 17 3 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="arxiv"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 41 4 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="pubmed"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 7 5 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="wikidata"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 53 6 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="openalex"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 23 2 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="semantic-scholar"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 47 3 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="biorxiv"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 11 4 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="core"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 37 5 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="zenodo"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 29 6 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="unpaywall"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 53 1 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="doaj"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 19 7 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="exa"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 43 2 * * *"/>
            <column name="config" value="{}"/>
        </insert>
        <insert tableName="ke_enrichment_sources">
            <column name="vendor_id" value="brave"/>
            <column name="enabled" valueBoolean="true"/>
            <column name="schedule_cron" value="0 7 3 * * *"/>
            <column name="config" value="{}"/>
        </insert>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 5: Add 4 includes to db.changelog-master.xml**

Add after the existing `010-ingestion-jobs.xml` include:

```xml
    <include file="changes/011-create-vendor-api-keys.xml" relativeToChangelogFile="true"/>
    <include file="changes/012-create-enrichment-sources.xml" relativeToChangelogFile="true"/>
    <include file="changes/013-create-enrichment-runs.xml" relativeToChangelogFile="true"/>
    <include file="changes/014-seed-enrichment-sources.xml" relativeToChangelogFile="true"/>
```

- [ ] **Step 6: Verify migrations run cleanly**

```bash
cd emcip-knowledge-engine
mvn liquibase:update -Dliquibase.url=jdbc:postgresql://localhost:14005/emcip \
  -Dliquibase.username=emcip -Dliquibase.password=emcip | cat
```

Expected: `Liquibase: Update has been successful.` and 4 new changesets applied (`ke-11` through `ke-14`).

- [ ] **Step 7: Commit**

```bash
git add emcip-knowledge-engine/src/main/resources/db/
git commit -m "feat(42): add Liquibase migrations 011-014 for enrichment tables and seed sources"
```

---

## Task 2: JPA enums

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/TriggerType.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/RunStatus.java`

- [ ] **Step 1: Create TriggerType.java**

```java
package io.emcip.knowledge.engine.entity;

public enum TriggerType {
    SCHEDULED,
    TOPIC_DRIVEN,
    MANUAL
}
```

- [ ] **Step 2: Create RunStatus.java**

```java
package io.emcip.knowledge.engine.entity;

public enum RunStatus {
    RUNNING,
    SUCCESS,
    PARTIAL,
    FAILURE
}
```

- [ ] **Step 3: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/
git commit -m "feat(42): add TriggerType and RunStatus enums"
```

---

## Task 3: JPA entities

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/VendorApiKey.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/EnrichmentSource.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/EnrichmentRun.java`

- [ ] **Step 1: Create VendorApiKey.java**

```java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Entity
@Table(name = "ke_vendor_api_keys")
@Data
public class VendorApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "vendor_id", nullable = false, length = 64)
    private String vendorId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "api_key", nullable = false, length = 512)
    private String apiKey;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
```

- [ ] **Step 2: Create EnrichmentSource.java**

```java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ke_enrichment_sources")
@Data
public class EnrichmentSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "vendor_id", nullable = false, length = 64)
    private String vendorId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "schedule_cron", length = 64)
    private String scheduleCron;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", length = 16)
    private RunStatus lastRunStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> config = Map.of();

    @Version
    @Column(nullable = false)
    private long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
```

- [ ] **Step 3: Create EnrichmentRun.java**

```java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Entity
@Table(name = "ke_enrichment_runs")
@Data
public class EnrichmentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16)
    private TriggerType triggerType;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status = RunStatus.RUNNING;

    @Column(name = "items_fetched", nullable = false)
    private int itemsFetched = 0;

    @Column(name = "items_ingested", nullable = false)
    private int itemsIngested = 0;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @PrePersist
    void prePersist() {
        if (startedAt == null) startedAt = Instant.now();
    }
}
```

- [ ] **Step 4: Compile check**

```bash
cd emcip-knowledge-engine
mvn compile -q | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/
git commit -m "feat(42): add VendorApiKey, EnrichmentSource, EnrichmentRun JPA entities"
```

---

## Task 4: JPA repositories + deduplication query

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/VendorApiKeyRepository.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/EnrichmentSourceRepository.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/EnrichmentRunRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/KnowledgeDocumentRepository.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/repository/EnrichmentRepositoryTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.entity.TriggerType;
import io.emcip.knowledge.engine.entity.VendorApiKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class EnrichmentRepositoryTest {

    @Autowired VendorApiKeyRepository keyRepo;
    @Autowired EnrichmentSourceRepository sourceRepo;
    @Autowired EnrichmentRunRepository runRepo;

    @Test
    void vendorApiKey_tenantLookupThenGlobalFallback() {
        UUID tenantId = UUID.randomUUID();
        VendorApiKey global = new VendorApiKey();
        global.setVendorId("exa");
        global.setApiKey("global-key");
        keyRepo.save(global);

        VendorApiKey tenant = new VendorApiKey();
        tenant.setVendorId("exa");
        tenant.setTenantId(tenantId);
        tenant.setApiKey("tenant-key");
        keyRepo.save(tenant);

        Optional<VendorApiKey> found = keyRepo.findByVendorIdAndTenantId("exa", tenantId);
        assertThat(found).isPresent();
        assertThat(found.get().getApiKey()).isEqualTo("tenant-key");

        Optional<VendorApiKey> fallback = keyRepo.findByVendorIdAndTenantIdIsNull("exa");
        assertThat(fallback).isPresent();
        assertThat(fallback.get().getApiKey()).isEqualTo("global-key");
    }

    @Test
    void enrichmentSource_findEnabled() {
        // The 14 seeded rows are enabled=true globally; we just check they loaded.
        List<EnrichmentSource> all = sourceRepo.findAllByEnabledTrue();
        assertThat(all).isNotEmpty();
        assertThat(all).allMatch(s -> s.getScheduleCron() != null);
    }

    @Test
    void enrichmentRun_saveAndFindBySourceId() {
        EnrichmentSource src = sourceRepo.findAll().get(0);

        EnrichmentRun run = new EnrichmentRun();
        run.setSourceId(src.getId());
        run.setTriggerType(TriggerType.MANUAL);
        run.setStatus(RunStatus.RUNNING);
        EnrichmentRun saved = runRepo.save(run);

        List<EnrichmentRun> runs = runRepo.findBySourceIdOrderByStartedAtDesc(src.getId());
        assertThat(runs).anyMatch(r -> r.getId().equals(saved.getId()));
    }
}
```

- [ ] **Step 2: Run — verify it fails**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=EnrichmentRepositoryTest | cat
```

Expected: FAIL — `VendorApiKeyRepository`, `EnrichmentSourceRepository`, `EnrichmentRunRepository` do not exist.

- [ ] **Step 3: Create VendorApiKeyRepository.java**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.VendorApiKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorApiKeyRepository extends JpaRepository<VendorApiKey, UUID> {

    Optional<VendorApiKey> findByVendorIdAndTenantId(String vendorId, UUID tenantId);

    Optional<VendorApiKey> findByVendorIdAndTenantIdIsNull(String vendorId);
}
```

- [ ] **Step 4: Create EnrichmentSourceRepository.java**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.EnrichmentSource;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrichmentSourceRepository extends JpaRepository<EnrichmentSource, UUID> {

    List<EnrichmentSource> findAllByEnabledTrue();

    List<EnrichmentSource> findAllByEnabledTrueAndTenantIdIsNull();

    List<EnrichmentSource> findAllByEnabledTrueAndTenantId(UUID tenantId);
}
```

- [ ] **Step 5: Create EnrichmentRunRepository.java**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.EnrichmentRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrichmentRunRepository extends JpaRepository<EnrichmentRun, UUID> {

    List<EnrichmentRun> findBySourceIdOrderByStartedAtDesc(UUID sourceId);
}
```

- [ ] **Step 6: Add deduplication query to KnowledgeDocumentRepository**

Read `KnowledgeDocumentRepository.java`, then add one method:

```java
boolean existsBySourceRefAndSourceType(String sourceRef, String sourceType);
```

- [ ] **Step 7: Run test — verify it passes**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=EnrichmentRepositoryTest | cat
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 8: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/
git commit -m "feat(42): add enrichment repositories and deduplication query"
```

---

## Task 5: Connector interface package

**Files (all new, package `io.emcip.knowledge.engine.connector`):**
- `TriggerMode.java`
- `ConnectorException.java`
- `ConnectorContext.java`
- `EnrichmentRequest.java`
- `EnrichmentResult.java`
- `KnowledgeConnector.java`
- `EnrichmentConnectorRegistry.java`

- [ ] **Step 1: Create TriggerMode.java**

```java
package io.emcip.knowledge.engine.connector;

public enum TriggerMode {
    SCHEDULED,
    TOPIC_DRIVEN,
    MANUAL
}
```

- [ ] **Step 2: Create ConnectorException.java**

```java
package io.emcip.knowledge.engine.connector;

public class ConnectorException extends RuntimeException {

    public ConnectorException(String message) {
        super(message);
    }

    public ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: Create ConnectorContext.java**

```java
package io.emcip.knowledge.engine.connector;

import java.time.Instant;
import java.util.UUID;
import org.springframework.lang.Nullable;

public record ConnectorContext(
        @Nullable String apiKey,
        UUID tenantId,
        Instant since) {}
```

- [ ] **Step 4: Create EnrichmentRequest.java**

```java
package io.emcip.knowledge.engine.connector;

import java.util.Map;
import org.springframework.lang.Nullable;

public record EnrichmentRequest(
        TriggerMode mode,
        @Nullable String query,
        @Nullable String externalId,
        Map<String, String> params) {}
```

- [ ] **Step 5: Create EnrichmentResult.java**

```java
package io.emcip.knowledge.engine.connector;

import java.time.Instant;
import java.util.Map;
import org.springframework.lang.Nullable;

public record EnrichmentResult(
        String externalId,
        String title,
        @Nullable String content,
        String url,
        String sourceVendorId,
        Instant publishedAt,
        Map<String, Object> metadata) {}
```

- [ ] **Step 6: Create KnowledgeConnector.java**

```java
package io.emcip.knowledge.engine.connector;

import java.util.List;

/**
 * Implemented by every enrichment connector. Spring-managed (@Component).
 * Returns a plain List — knowledge-engine is JPA/blocking; no reactive types here.
 */
public interface KnowledgeConnector {

    /** Unique identifier matching vendor_id in ke_vendor_api_keys / ke_enrichment_sources. */
    String vendorId();

    String displayName();

    boolean requiresApiKey();

    /**
     * Fetch results for the given request. Throws {@link ConnectorException} only for
     * connector-level failures (auth, network). Per-item errors must be caught inside
     * the implementation and excluded from the returned list.
     */
    List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx);
}
```

- [ ] **Step 7: Create EnrichmentConnectorRegistry.java**

```java
package io.emcip.knowledge.engine.connector;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class EnrichmentConnectorRegistry {

    private final Map<String, KnowledgeConnector> byVendorId;

    public EnrichmentConnectorRegistry(List<KnowledgeConnector> connectors) {
        this.byVendorId = connectors.stream()
                .collect(Collectors.toMap(KnowledgeConnector::vendorId, Function.identity()));
        log.info("Registered {} enrichment connectors: {}", byVendorId.size(), byVendorId.keySet());
    }

    public Optional<KnowledgeConnector> find(String vendorId) {
        return Optional.ofNullable(byVendorId.get(vendorId));
    }

    public List<KnowledgeConnector> all() {
        return List.copyOf(byVendorId.values());
    }
}
```

- [ ] **Step 8: Compile check**

```bash
cd emcip-knowledge-engine
mvn compile -q | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 9: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/connector/
git commit -m "feat(42): add KnowledgeConnector interface and EnrichmentConnectorRegistry"
```

---

## Task 6: ApiKeyResolver + test

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ApiKeyResolver.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ApiKeyResolverTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.entity.VendorApiKey;
import io.emcip.knowledge.engine.repository.VendorApiKeyRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiKeyResolverTest {

    @Mock private VendorApiKeyRepository repo;

    private ApiKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ApiKeyResolver(repo);
    }

    @Test
    void returnsTenanteSpecificKey_whenPresent() {
        UUID tenantId = UUID.randomUUID();
        VendorApiKey key = new VendorApiKey();
        key.setApiKey("tenant-key");
        when(repo.findByVendorIdAndTenantId("exa", tenantId)).thenReturn(Optional.of(key));

        Optional<String> result = resolver.resolve("exa", tenantId);
        assertThat(result).contains("tenant-key");
    }

    @Test
    void fallsBackToGlobal_whenNoTenantKey() {
        UUID tenantId = UUID.randomUUID();
        VendorApiKey global = new VendorApiKey();
        global.setApiKey("global-key");
        when(repo.findByVendorIdAndTenantId("exa", tenantId)).thenReturn(Optional.empty());
        when(repo.findByVendorIdAndTenantIdIsNull("exa")).thenReturn(Optional.of(global));

        Optional<String> result = resolver.resolve("exa", tenantId);
        assertThat(result).contains("global-key");
    }

    @Test
    void returnsEmpty_whenNoKeyAtAll() {
        UUID tenantId = UUID.randomUUID();
        when(repo.findByVendorIdAndTenantId("exa", tenantId)).thenReturn(Optional.empty());
        when(repo.findByVendorIdAndTenantIdIsNull("exa")).thenReturn(Optional.empty());

        Optional<String> result = resolver.resolve("exa", tenantId);
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=ApiKeyResolverTest | cat
```

Expected: FAIL — `ApiKeyResolver` does not exist.

- [ ] **Step 3: Create ApiKeyResolver.java**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.repository.VendorApiKeyRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ApiKeyResolver {

    private final VendorApiKeyRepository repo;

    /**
     * Returns the API key for the given vendor, preferring a tenant-specific key over the global
     * fallback. Returns empty if no key exists.
     */
    public Optional<String> resolve(String vendorId, UUID tenantId) {
        return repo.findByVendorIdAndTenantId(vendorId, tenantId)
                .or(() -> repo.findByVendorIdAndTenantIdIsNull(vendorId))
                .map(k -> k.getApiKey());
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=ApiKeyResolverTest | cat
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ApiKeyResolver.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ApiKeyResolverTest.java
git commit -m "feat(42): add ApiKeyResolver with tenant-then-global fallback"
```

---

## Task 7: EnrichmentPipelineService + integration test

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EnrichmentPipelineService.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/connector/TestStubConnector.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/EnrichmentPipelineIntegrationTest.java`

The pipeline: resolve key → fetch → deduplicate → embed → store → audit.

- [ ] **Step 1: Create TestStubConnector.java** (test-only `@TestComponent`)

```java
package io.emcip.knowledge.engine.connector;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestComponent;

/**
 * Stub connector for integration tests. Returns one deterministic result per call.
 * Registered only in the test Spring context.
 */
@TestComponent
public class TestStubConnector implements KnowledgeConnector {

    public static final String VENDOR_ID = "stub";

    @Override
    public String vendorId() {
        return VENDOR_ID;
    }

    @Override
    public String displayName() {
        return "Stub Connector";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        return List.of(
                new EnrichmentResult(
                        "stub-ext-001",
                        "Stub paper about " + request.query(),
                        "Abstract of stub paper.",
                        "https://stub.example/001",
                        VENDOR_ID,
                        Instant.parse("2026-01-15T00:00:00Z"),
                        Map.of("authors", List.of("Alice", "Bob"))));
    }
}
```

- [ ] **Step 2: Write failing integration test**

```java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.connector.TestStubConnector;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.entity.TriggerType;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import(EnrichmentPipelineIntegrationTest.TestConfig.class)
class EnrichmentPipelineIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        TestStubConnector testStubConnector() {
            return new TestStubConnector();
        }
    }

    @Autowired EnrichmentPipelineService pipelineService;
    @Autowired EnrichmentSourceRepository sourceRepo;
    @Autowired EnrichmentRunRepository runRepo;
    @Autowired KnowledgeDocumentRepository docRepo;

    @Test
    void pipeline_storesDocumentAndUpdatesRun() {
        EnrichmentSource source = new EnrichmentSource();
        source.setVendorId(TestStubConnector.VENDOR_ID);
        source.setConfig(Map.of());
        source = sourceRepo.save(source);

        EnrichmentRun run = new EnrichmentRun();
        run.setSourceId(source.getId());
        run.setTriggerType(TriggerType.MANUAL);
        run.setStatus(RunStatus.RUNNING);
        run = runRepo.save(run);

        UUID tenantId = UUID.randomUUID();
        pipelineService.execute(source, run, TriggerMode.MANUAL, "quantum computing", null, tenantId);

        EnrichmentRun updated = runRepo.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(updated.getItemsFetched()).isEqualTo(1);
        assertThat(updated.getItemsIngested()).isEqualTo(1);
        assertThat(updated.getCompletedAt()).isNotNull();

        // Document stored in knowledge_documents
        boolean stored = docRepo.existsBySourceRefAndSourceType("stub-ext-001", TestStubConnector.VENDOR_ID);
        assertThat(stored).isTrue();
    }

    @Test
    void pipeline_skipsDuplicate_onSecondRun() {
        EnrichmentSource source = new EnrichmentSource();
        source.setVendorId(TestStubConnector.VENDOR_ID);
        source.setConfig(Map.of());
        source = sourceRepo.save(source);

        UUID tenantId = UUID.randomUUID();

        EnrichmentRun run1 = new EnrichmentRun();
        run1.setSourceId(source.getId());
        run1.setTriggerType(TriggerType.MANUAL);
        run1.setStatus(RunStatus.RUNNING);
        run1 = runRepo.save(run1);
        pipelineService.execute(source, run1, TriggerMode.MANUAL, "topic", null, tenantId);

        EnrichmentRun run2 = new EnrichmentRun();
        run2.setSourceId(source.getId());
        run2.setTriggerType(TriggerType.MANUAL);
        run2.setStatus(RunStatus.RUNNING);
        run2 = runRepo.save(run2);
        pipelineService.execute(source, run2, TriggerMode.MANUAL, "topic", null, tenantId);

        EnrichmentRun updated2 = runRepo.findById(run2.getId()).orElseThrow();
        assertThat(updated2.getItemsFetched()).isEqualTo(1);
        assertThat(updated2.getItemsIngested()).isEqualTo(0); // duplicate skipped
        assertThat(updated2.getStatus()).isEqualTo(RunStatus.SUCCESS);
    }
}
```

- [ ] **Step 3: Run — verify it fails**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=EnrichmentPipelineIntegrationTest | cat
```

Expected: FAIL — `EnrichmentPipelineService` does not exist.

- [ ] **Step 4: Create EnrichmentPipelineService.java**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.ConnectorException;
import io.emcip.knowledge.engine.connector.EnrichmentConnectorRegistry;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class EnrichmentPipelineService {

    private final EnrichmentConnectorRegistry registry;
    private final ApiKeyResolver keyResolver;
    private final KnowledgeDocumentRepository docRepo;
    private final VectorSearchRepository vectorRepo;
    private final LlmOrchestratorClient llmClient;
    private final EnrichmentRunRepository runRepo;
    private final EnrichmentSourceRepository sourceRepo;

    /**
     * Executes the 6-stage enrichment pipeline for a single source+run pair.
     * Updates the run record on completion. Never throws — failures are recorded in the run.
     */
    @Transactional
    public void execute(
            EnrichmentSource source,
            EnrichmentRun run,
            TriggerMode mode,
            @Nullable String query,
            @Nullable String externalId,
            UUID tenantId) {

        Optional<KnowledgeConnector> connectorOpt = registry.find(source.getVendorId());
        if (connectorOpt.isEmpty()) {
            log.warn("No connector registered for vendor: {}", source.getVendorId());
            completeRun(run, RunStatus.FAILURE, "No connector registered for vendor: " + source.getVendorId());
            return;
        }
        KnowledgeConnector connector = connectorOpt.get();

        // Stage 1: Resolve key
        Optional<String> apiKey = Optional.empty();
        if (connector.requiresApiKey()) {
            apiKey = keyResolver.resolve(source.getVendorId(), tenantId);
            if (apiKey.isEmpty()) {
                log.warn("API key required but not found for vendor: {}", source.getVendorId());
                completeRun(run, RunStatus.FAILURE, "API key required but not configured for vendor: " + source.getVendorId());
                return;
            }
        }

        // Stage 2: Fetch
        List<EnrichmentResult> results;
        try {
            ConnectorContext ctx = new ConnectorContext(
                    apiKey.orElse(null),
                    tenantId,
                    Instant.now().minusSeconds(86400));
            Map<String, String> params = source.getConfig().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> String.valueOf(e.getValue())));
            EnrichmentRequest req = new EnrichmentRequest(mode, query, externalId, params);
            results = connector.fetch(req, ctx);
        } catch (ConnectorException e) {
            log.warn("Connector failed for vendor {}: {}", source.getVendorId(), e.getMessage());
            completeRun(run, RunStatus.FAILURE, e.getMessage());
            updateSourceLastRun(source, RunStatus.FAILURE);
            return;
        }

        run.setItemsFetched(results.size());

        // Stages 3–5: Deduplicate → Embed → Store
        int ingested = 0;
        int errors = 0;
        for (EnrichmentResult result : results) {
            try {
                // Stage 3: Deduplicate
                if (docRepo.existsBySourceRefAndSourceType(result.externalId(), result.sourceVendorId())) {
                    log.debug("Duplicate skipped: {} / {}", result.sourceVendorId(), result.externalId());
                    continue;
                }

                // Stage 4: Embed
                String textToEmbed = result.title() + (result.content() != null ? " " + result.content() : "");
                float[] embedding = llmClient.embed(textToEmbed);

                // Stage 5: Store
                KnowledgeDocument doc = new KnowledgeDocument();
                doc.setTenantId(tenantId);
                doc.setSourceType(result.sourceVendorId());
                doc.setSourceRef(result.externalId());
                doc.setContent(result.content() != null ? result.content() : result.title());
                doc.setMetadata(result.metadata());
                doc = docRepo.save(doc);

                if (embedding.length > 0) {
                    vectorRepo.storeEmbedding(doc.getId(), embedding);
                }

                ingested++;
            } catch (Exception e) {
                log.warn("Failed to ingest result {} from {}: {}", result.externalId(), source.getVendorId(), e.getMessage());
                errors++;
            }
        }

        run.setItemsIngested(ingested);
        RunStatus finalStatus = errors == 0 ? RunStatus.SUCCESS : RunStatus.PARTIAL;
        completeRun(run, finalStatus, null);
        updateSourceLastRun(source, finalStatus);
    }

    private void completeRun(EnrichmentRun run, RunStatus status, @Nullable String errorMessage) {
        run.setStatus(status);
        run.setCompletedAt(Instant.now());
        if (errorMessage != null) {
            run.setErrorMessage(errorMessage.length() > 1024
                    ? errorMessage.substring(0, 1024)
                    : errorMessage);
        }
        runRepo.save(run);
    }

    private void updateSourceLastRun(EnrichmentSource source, RunStatus status) {
        source.setLastRunAt(Instant.now());
        source.setLastRunStatus(status);
        sourceRepo.save(source);
    }
}
```

- [ ] **Step 5: Run test — verify it passes**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=EnrichmentPipelineIntegrationTest | cat
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EnrichmentPipelineService.java \
        emcip-knowledge-engine/src/test/
git commit -m "feat(42): add EnrichmentPipelineService with 6-stage ingestion"
```

---

## Task 8: Enable scheduling + EnrichmentScheduler

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/KnowledgeEngineApplication.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EnrichmentScheduler.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/EnrichmentSchedulerTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.emcip.knowledge.engine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnrichmentSchedulerTest {

    @Mock private EnrichmentSourceRepository sourceRepo;
    @Mock private EnrichmentRunRepository runRepo;
    @Mock private EnrichmentPipelineService pipelineService;

    private EnrichmentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EnrichmentScheduler(sourceRepo, runRepo, pipelineService);
    }

    @Test
    void dispatchesDueSources() {
        EnrichmentSource dueSource = new EnrichmentSource();
        dueSource.setId(UUID.randomUUID());
        dueSource.setVendorId("wikipedia");
        dueSource.setScheduleCron("0 * * * * *"); // every minute — always due

        when(sourceRepo.findAllByEnabledTrue()).thenReturn(List.of(dueSource));
        EnrichmentRun savedRun = new EnrichmentRun();
        savedRun.setId(UUID.randomUUID());
        when(runRepo.save(any())).thenReturn(savedRun);

        scheduler.tick();

        ArgumentCaptor<EnrichmentRun> runCaptor = ArgumentCaptor.forClass(EnrichmentRun.class);
        verify(runRepo).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getTriggerType())
                .isEqualTo(io.emcip.knowledge.engine.entity.TriggerType.SCHEDULED);

        verify(pipelineService, atLeastOnce())
                .execute(eq(dueSource), any(), eq(TriggerMode.SCHEDULED), isNull(), isNull(), isNull());
    }

    @Test
    void skipsSourceWithNoScheduleCron() {
        EnrichmentSource noSchedule = new EnrichmentSource();
        noSchedule.setVendorId("exa");
        noSchedule.setScheduleCron(null);

        when(sourceRepo.findAllByEnabledTrue()).thenReturn(List.of(noSchedule));

        scheduler.tick();

        verify(pipelineService, never()).execute(any(), any(), any(), any(), any(), any());
    }
}
```

Note: add `import static org.assertj.core.api.Assertions.assertThat;` at the top.

- [ ] **Step 2: Run — verify it fails**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=EnrichmentSchedulerTest | cat
```

Expected: FAIL — `EnrichmentScheduler` does not exist.

- [ ] **Step 3: Add @EnableScheduling to KnowledgeEngineApplication**

Add import and annotation:

```java
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "io.emcip.knowledge.engine.repository")
@EnableScheduling
public class KnowledgeEngineApplication {
```

- [ ] **Step 4: Create EnrichmentScheduler.java**

The master `@Scheduled` fires at second `:17` every minute. It checks each enabled source's own cron expression using Spring's `CronExpression`.

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.TriggerType;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class EnrichmentScheduler {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final EnrichmentSourceRepository sourceRepo;
    private final EnrichmentRunRepository runRepo;
    private final EnrichmentPipelineService pipelineService;

    /** Master tick fires at second :17 of every minute to avoid exact round times. */
    @Scheduled(cron = "17 * * * * *")
    public void tick() {
        List<EnrichmentSource> enabled = sourceRepo.findAllByEnabledTrue();
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime oneMinuteAgo = now.minusMinutes(1);

        for (EnrichmentSource source : enabled) {
            if (source.getScheduleCron() == null) continue;

            try {
                CronExpression expr = CronExpression.parse(source.getScheduleCron());
                ZonedDateTime next = expr.next(oneMinuteAgo);
                if (next != null && !next.isAfter(now)) {
                    dispatch(source);
                }
            } catch (Exception e) {
                log.warn("Invalid cron for source {}: {}", source.getId(), e.getMessage());
            }
        }
    }

    private void dispatch(EnrichmentSource source) {
        EnrichmentRun run = new EnrichmentRun();
        run.setSourceId(source.getId());
        run.setTriggerType(TriggerType.SCHEDULED);
        EnrichmentRun saved = runRepo.save(run);

        EXECUTOR.submit(() -> {
            try {
                pipelineService.execute(source, saved, TriggerMode.SCHEDULED, null, null, source.getTenantId());
            } catch (Exception e) {
                log.error("Scheduled enrichment failed for source {}: {}", source.getId(), e.getMessage(), e);
            }
        });
    }
}
```

- [ ] **Step 5: Run test — verify it passes**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=EnrichmentSchedulerTest | cat
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/
git commit -m "feat(42): add @EnableScheduling and EnrichmentScheduler with per-source cron check"
```

---

## Task 9: ENTITY_CREATED event + Kafka consumers

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeEventPublisher.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EntityEnrichmentConsumer.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ManualEnrichmentConsumer.java`

- [ ] **Step 1: Add publishEntityCreated to KnowledgeEventPublisher**

Read `KnowledgeEventPublisher.java`, then add this method before the private `publishEvent`:

```java
public void publishEntityCreated(String entityName, UUID tenantId) {
    publishEvent(
            "ENTITY_CREATED",
            Map.of("entityName", entityName, "tenantId", tenantId != null ? tenantId.toString() : ""),
            tenantId);
}
```

- [ ] **Step 2: Call publishEntityCreated from KnowledgeExtractionService**

Read `KnowledgeExtractionService.java`. After entities are stored (look for where `graphRepository` is called to persist entities), add a call to publish the event for each extracted entity name. Add `KnowledgeEventPublisher` as a constructor dependency and call:

```java
// After persisting each entity to the graph:
eventPublisher.publishEntityCreated(entity.getName(), tenantId);
```

The exact insertion point is after the loop that calls `graphRepository.createEntityNode(...)` or similar. Read the current service to find the right location.

- [ ] **Step 3: Create EntityEnrichmentConsumer.java**

Listens on `knowledge.events`, filters for `ENTITY_CREATED` events, and triggers enrichment for all globally enabled sources.

```java
package io.emcip.knowledge.engine.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.emcip.common.tenant.TenantContext;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.entity.TriggerType;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class EntityEnrichmentConsumer {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final EnrichmentSourceRepository sourceRepo;
    private final EnrichmentRunRepository runRepo;
    private final EnrichmentPipelineService pipelineService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "knowledge.events", groupId = "knowledge-engine-entity-enrichment")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> event = objectMapper.readValue(
                    record.value(), new TypeReference<Map<String, Object>>() {});

            String eventType = (String) event.get("eventType");
            if (!"ENTITY_CREATED".equals(eventType)) return;

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
            String entityName = (String) payload.get("entityName");

            UUID tenantId = extractTenantId(record);
            List<EnrichmentSource> sources = (tenantId != null)
                    ? sourceRepo.findAllByEnabledTrueAndTenantId(tenantId)
                    : sourceRepo.findAllByEnabledTrueAndTenantIdIsNull();

            for (EnrichmentSource source : sources) {
                EnrichmentRun run = new EnrichmentRun();
                run.setSourceId(source.getId());
                run.setTriggerType(TriggerType.TOPIC_DRIVEN);
                run.setStatus(RunStatus.RUNNING);
                EnrichmentRun saved = runRepo.save(run);
                UUID finalTenantId = tenantId;
                EXECUTOR.submit(() -> {
                    try {
                        pipelineService.execute(source, saved, TriggerMode.TOPIC_DRIVEN, entityName, null, finalTenantId);
                    } catch (Exception e) {
                        log.error("Topic-driven enrichment failed for source {}: {}", source.getId(), e.getMessage(), e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to process knowledge event: {}", e.getMessage(), e);
        }
    }

    private UUID extractTenantId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(TenantContext.KAFKA_HEADER);
        if (header == null) return null;
        try {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Create ManualEnrichmentConsumer.java**

Listens on `knowledge.enrichment.trigger` (published by admin-api when a manual run is requested).

```java
package io.emcip.knowledge.engine.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class ManualEnrichmentConsumer {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final EnrichmentSourceRepository sourceRepo;
    private final EnrichmentRunRepository runRepo;
    private final EnrichmentPipelineService pipelineService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "knowledge.enrichment.trigger", groupId = "knowledge-engine")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            Map<String, String> payload = objectMapper.readValue(
                    record.value(), new TypeReference<Map<String, String>>() {});

            UUID sourceId = UUID.fromString(payload.get("sourceId"));
            UUID runId = UUID.fromString(payload.get("runId"));

            Optional<EnrichmentSource> source = sourceRepo.findById(sourceId);
            Optional<EnrichmentRun> run = runRepo.findById(runId);

            if (source.isEmpty() || run.isEmpty()) {
                log.warn("Manual trigger: source {} or run {} not found", sourceId, runId);
                return;
            }

            EXECUTOR.submit(() -> {
                try {
                    pipelineService.execute(
                            source.get(), run.get(), TriggerMode.MANUAL,
                            null, null, source.get().getTenantId());
                } catch (Exception e) {
                    log.error("Manual enrichment failed for run {}: {}", runId, e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            log.error("Failed to process manual trigger event: {}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: Compile check**

```bash
cd emcip-knowledge-engine
mvn compile -q | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Run all knowledge-engine tests**

```bash
cd emcip-knowledge-engine
mvn test | cat
```

Expected: all existing tests still pass; new integration tests pass.

- [ ] **Step 7: Apply Spotless**

```bash
cd emcip-knowledge-engine
mvn spotless:apply | cat
```

Expected: `0 were changed to be clean` (or non-zero, then commit the style fix separately).

- [ ] **Step 8: Commit**

```bash
git add emcip-knowledge-engine/src/
git commit -m "feat(42): add ENTITY_CREATED event, EntityEnrichmentConsumer, ManualEnrichmentConsumer"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Covered in task |
|---|---|
| 4 Liquibase migrations (ke-11 → ke-14) | Task 1 |
| `UNIQUE NULLS NOT DISTINCT` for global key uniqueness | Task 1 Step 1 (via raw `<sql>`) |
| Seed: one row per vendor with non-round cron | Task 1 Step 4 |
| VendorApiKey, EnrichmentSource, EnrichmentRun entities | Task 3 |
| `@Version` optimistic lock on EnrichmentSource | Task 3 Step 2 |
| Connector interface returning `List<EnrichmentResult>` (JPA-safe) | Task 5 Step 6 |
| EnrichmentConnectorRegistry collecting all connectors | Task 5 Step 7 |
| ApiKeyResolver: tenant → global → empty | Task 6 |
| 6-stage pipeline: resolve → fetch → dedup → embed → store → audit | Task 7 |
| `onErrorContinue` per-item error handling → PARTIAL status | Task 7 Step 4 (errors counter) |
| `@Scheduled(cron = "17 * * * * *")` master tick | Task 8 Step 4 |
| `CronExpression.parse().next()` check per source | Task 8 Step 4 |
| `knowledge.events` listener filtering ENTITY_CREATED | Task 9 Step 3 |
| `knowledge.enrichment.trigger` listener for manual runs | Task 9 Step 4 |
| `publishEntityCreated` in KnowledgeEventPublisher | Task 9 Step 1 |

**No placeholders found.**

**Type consistency:** `TriggerMode` (connector package) is distinct from `TriggerType` (entity enum). `TriggerMode` is used in `EnrichmentRequest` and `EnrichmentPipelineService`; `TriggerType` is persisted in `EnrichmentRun`. Both are used consistently — no mismatch.
