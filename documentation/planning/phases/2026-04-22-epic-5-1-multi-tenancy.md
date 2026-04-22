# Epic 5.1 — Multi-Tenancy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add row-level tenant isolation to all services — every entity gets `tenant_id`, HTTP requests propagate it via `X-Tenant-Id` header, and Kafka messages carry it as a header.

**Architecture:** `TenantContext` (ThreadLocal) in `emcip-core` is the single source of truth for the current tenant. A `TenantContextFilter` binds it from HTTP headers; a `TenantAwareKafkaConsumerSupport` utility binds it from Kafka message headers. All Kafka producers include `tenant_id` from `TenantContext`. Entity migrations add `tenant_id UUID NOT NULL DEFAULT gen_random_uuid()` as a safe migration (existing rows get a generated UUID, enforcing non-null going forward). The `emcip-admin-api` gains a `Tenant` entity and CRUD endpoints.

**Tech Stack:** Java 21, Spring Boot 4, JPA + R2DBC (per module), Kafka headers, Liquibase, JUnit 5, Testcontainers.

---

### Task 1: TenantContext in emcip-core

**Files:**
- Create: `emcip-core/src/main/java/io/emcip/common/tenant/TenantContext.java`
- Create: `emcip-core/src/main/java/io/emcip/common/tenant/TenantContextFilter.java`
- Create: `emcip-core/src/test/java/io/emcip/common/tenant/TenantContextTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void setAndGetTenantId() {
        TenantContext.setTenantId("tenant-abc");
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-abc");
    }

    @Test
    void clearRemovesTenantId() {
        TenantContext.setTenantId("tenant-abc");
        TenantContext.clear();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void defaultIsNull() {
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl emcip-core -Dtest=TenantContextTest
```

Expected: FAIL — `TenantContext not found`

- [ ] **Step 3: Implement TenantContext**

```java
package io.emcip.common.tenant;

/** Holds the current tenant ID for the request thread. */
public final class TenantContext {

    public static final String HEADER_NAME = "X-Tenant-Id";
    public static final String KAFKA_HEADER = "tenant_id";

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -pl emcip-core -Dtest=TenantContextTest
```

Expected: PASS

- [ ] **Step 5: Implement TenantContextFilter**

```java
package io.emcip.common.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that extracts the X-Tenant-Id header and binds it to TenantContext.
 * Register as a Spring bean in each service that exposes HTTP endpoints.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String tenantId = request.getHeader(TenantContext.HEADER_NAME);
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.setTenantId(tenantId);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add emcip-core/src/main/java/io/emcip/common/tenant/ \
        emcip-core/src/test/java/io/emcip/common/tenant/
git commit -m "feat(5.1): add TenantContext and TenantContextFilter to emcip-core"
```

---

### Task 2: TenantAwareKafkaConsumerSupport in emcip-core

**Files:**
- Create: `emcip-core/src/main/java/io/emcip/common/tenant/TenantAwareKafkaSupport.java`
- Create: `emcip-core/src/test/java/io/emcip/common/tenant/TenantAwareKafkaSupportTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantAwareKafkaSupportTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void extractsTenantIdFromHeader() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(TenantContext.KAFKA_HEADER, "tenant-xyz".getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("topic", 0, 0L, "key", "value", headers);

        TenantAwareKafkaSupport.bindTenantFromRecord(record);

        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-xyz");
    }

    @Test
    void doesNotFailWhenHeaderAbsent() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("topic", 0, 0L, "key", "value");

        TenantAwareKafkaSupport.bindTenantFromRecord(record);

        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void addsTenantHeaderToProducerRecord() {
        TenantContext.setTenantId("tenant-abc");

        org.apache.kafka.clients.producer.ProducerRecord<String, String> record =
                new org.apache.kafka.clients.producer.ProducerRecord<>("topic", "key", "value");
        TenantAwareKafkaSupport.addTenantHeader(record);

        byte[] headerValue = record.headers().lastHeader(TenantContext.KAFKA_HEADER).value();
        assertThat(new String(headerValue, StandardCharsets.UTF_8)).isEqualTo("tenant-abc");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl emcip-core -Dtest=TenantAwareKafkaSupportTest
```

Expected: FAIL

- [ ] **Step 3: Implement TenantAwareKafkaSupport**

```java
package io.emcip.common.tenant;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Utility for propagating tenant context through Kafka message headers. */
public final class TenantAwareKafkaSupport {

    private TenantAwareKafkaSupport() {}

    /** Extract tenant_id header from a consumer record and bind to TenantContext. */
    public static void bindTenantFromRecord(ConsumerRecord<?, ?> record) {
        var header = record.headers().lastHeader(TenantContext.KAFKA_HEADER);
        if (header != null) {
            TenantContext.setTenantId(new String(header.value(), StandardCharsets.UTF_8));
        }
    }

    /** Add the current TenantContext tenant_id as a Kafka message header. */
    public static void addTenantHeader(ProducerRecord<?, ?> record) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            record.headers().add(
                    TenantContext.KAFKA_HEADER,
                    tenantId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -pl emcip-core -Dtest=TenantAwareKafkaSupportTest
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add emcip-core/src/main/java/io/emcip/common/tenant/TenantAwareKafkaSupport.java \
        emcip-core/src/test/java/io/emcip/common/tenant/TenantAwareKafkaSupportTest.java
git commit -m "feat(5.1): add TenantAwareKafkaSupport for header propagation"
```

---

### Task 3: tenant_id migration — conversation-context

**Files:**
- Create: `emcip-conversation-context/src/main/resources/db/changelog/changes/005-add-tenant-id.xml`
- Modify: `emcip-conversation-context/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Create changeset**

The `DEFAULT gen_random_uuid()` allows the migration to run on existing rows (they get a generated UUID). Production would replace defaults with actual tenant IDs in a follow-up data migration.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="005" author="phase5">
        <addColumn tableName="messages">
            <column name="tenant_id" type="UUID"
                    defaultValueComputed="gen_random_uuid()">
                <constraints nullable="false"/>
            </column>
        </addColumn>

        <addColumn tableName="message_threads">
            <column name="tenant_id" type="UUID"
                    defaultValueComputed="gen_random_uuid()">
                <constraints nullable="false"/>
            </column>
        </addColumn>

        <addColumn tableName="users">
            <column name="tenant_id" type="UUID"
                    defaultValueComputed="gen_random_uuid()">
                <constraints nullable="false"/>
            </column>
        </addColumn>

        <createIndex indexName="idx_messages_tenant_id" tableName="messages">
            <column name="tenant_id"/>
        </createIndex>
        <createIndex indexName="idx_threads_tenant_id" tableName="message_threads">
            <column name="tenant_id"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Add to master changelog**

```xml
<include file="changes/005-add-tenant-id.xml" relativeToChangelogFile="true"/>
```

- [ ] **Step 3: Commit**

```bash
git add emcip-conversation-context/src/main/resources/db/
git commit -m "feat(5.1): add tenant_id to conversation-context tables"
```

---

### Task 4: tenant_id migration — policy-engine, llm-orchestrator, moderation-service, audit-service

**Files:**
- Create: `emcip-policy-engine/src/main/resources/db/changelog/changes/004-add-tenant-id.xml`
- Create: `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/006-add-tenant-id.xml`
- Create: `emcip-moderation-service/src/main/resources/db/changelog/changes/003-add-tenant-id.xml`
- Create: `emcip-audit-service/src/main/resources/db/changelog/changes/002-add-tenant-id.xml`
- Modify: each module's `db.changelog-master.xml`

- [ ] **Step 1: policy-engine changeset** (`004-add-tenant-id.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">
    <changeSet id="004" author="phase5">
        <addColumn tableName="policy_rules">
            <column name="tenant_id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints nullable="false"/>
            </column>
        </addColumn>
        <addColumn tableName="policy_decisions">
            <column name="tenant_id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints nullable="false"/>
            </column>
        </addColumn>
        <createIndex indexName="idx_policy_rules_tenant" tableName="policy_rules">
            <column name="tenant_id"/>
        </createIndex>
        <createIndex indexName="idx_policy_decisions_tenant" tableName="policy_decisions">
            <column name="tenant_id"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 2: llm-orchestrator changeset** (`006-add-tenant-id.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">
    <changeSet id="006" author="phase5">
        <addColumn tableName="model_cost_logs">
            <column name="tenant_id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints nullable="false"/>
            </column>
        </addColumn>
        <addColumn tableName="prompt_templates">
            <column name="tenant_id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints nullable="false"/>
            </column>
        </addColumn>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 3: moderation-service changeset** (`003-add-tenant-id.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">
    <changeSet id="003" author="phase5">
        <addColumn tableName="moderation_flags">
            <column name="tenant_id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints nullable="false"/>
            </column>
        </addColumn>
        <createIndex indexName="idx_moderation_flags_tenant" tableName="moderation_flags">
            <column name="tenant_id"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 4: audit-service changeset** (`002-add-tenant-id.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">
    <changeSet id="002-tenant" author="phase5">
        <addColumn tableName="audit_events">
            <column name="tenant_id" type="UUID" defaultValueComputed="gen_random_uuid()">
                <constraints nullable="false"/>
            </column>
        </addColumn>
        <createIndex indexName="idx_audit_events_tenant" tableName="audit_events">
            <column name="tenant_id"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 5: Add includes to all 4 master changelogs**

For each module, add to `db.changelog-master.xml`:
```xml
<include file="changes/00X-add-tenant-id.xml" relativeToChangelogFile="true"/>
```
(replace `00X` with the correct file name per module)

- [ ] **Step 6: Commit**

```bash
git add emcip-policy-engine/src/main/resources/db/ \
        emcip-llm-orchestrator/src/main/resources/db/ \
        emcip-moderation-service/src/main/resources/db/ \
        emcip-audit-service/src/main/resources/db/
git commit -m "feat(5.1): add tenant_id to policy-engine, llm-orchestrator, moderation, audit tables"
```

---

### Task 5: Tenant entity and CRUD in emcip-admin-api

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/Tenant.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/TenantRepository.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TenantController.java`
- Create: `emcip-admin-api/src/main/resources/db/changelog/changes/003-create-tenants-table.xml`
- Modify: `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml`

Note: `emcip-admin-api` uses R2DBC (Spring Data Reactive). Use `@Table` from `org.springframework.data.relational.core.mapping`.

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(TenantController.class)
class TenantControllerTest {

    @Autowired WebTestClient webTestClient;
    @MockBean TenantRepository tenantRepository;

    @Test
    void listTenantsReturnsAll() {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setName("Tenant A");
        when(tenantRepository.findAll()).thenReturn(Flux.just(t));

        webTestClient.get().uri("/api/tenants")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Tenant.class).hasSize(1);
    }

    @Test
    void createTenantReturns201() {
        Tenant input = new Tenant();
        input.setName("New Tenant");
        Tenant saved = new Tenant();
        saved.setId(UUID.randomUUID());
        saved.setName("New Tenant");
        when(tenantRepository.save(any())).thenReturn(Mono.just(saved));

        webTestClient.post().uri("/api/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"New Tenant\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.id").isNotEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl emcip-admin-api -Dtest=TenantControllerTest
```

Expected: FAIL

- [ ] **Step 3: Create Tenant entity**

```java
package io.emcip.admin.api.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("tenants")
@Data
public class Tenant {

    @Id private UUID id;

    @Column("name")
    private String name;

    @Column("description")
    private String description;

    @Column("llm_model_override")
    private String llmModelOverride;

    @Column("created_at")
    private Instant createdAt;
}
```

- [ ] **Step 4: Create TenantRepository**

```java
package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.Tenant;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface TenantRepository extends ReactiveCrudRepository<Tenant, UUID> {}
```

- [ ] **Step 5: Create TenantController**

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.TenantRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantRepository tenantRepository;

    @GetMapping
    public Flux<Tenant> listTenants() {
        return tenantRepository.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Tenant> createTenant(@RequestBody Tenant tenant) {
        tenant.setId(UUID.randomUUID());
        tenant.setCreatedAt(Instant.now());
        return tenantRepository.save(tenant);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTenant(@PathVariable UUID id) {
        return tenantRepository.deleteById(id);
    }
}
```

- [ ] **Step 6: Create Liquibase migration**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">
    <changeSet id="003" author="phase5">
        <createTable tableName="tenants">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="name" type="VARCHAR(128)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="description" type="VARCHAR(500)"/>
            <column name="llm_model_override" type="VARCHAR(64)"/>
            <column name="created_at" type="TIMESTAMPTZ">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>
</databaseChangeLog>
```

Save to `emcip-admin-api/src/main/resources/db/changelog/changes/003-create-tenants-table.xml`

Add to `db.changelog-master.xml`:
```xml
<include file="changes/003-create-tenants-table.xml" relativeToChangelogFile="true"/>
```

- [ ] **Step 7: Run test to verify it passes**

```bash
mvn test -pl emcip-admin-api -Dtest=TenantControllerTest
```

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add emcip-admin-api/src/
git commit -m "feat(5.1): add Tenant entity, repository, controller, and migration to admin-api"
```

---

### Task 6: Register TenantContextFilter in HTTP services

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java`
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/` (add filter bean)

Note: Only services with HTTP endpoints need the filter. Currently: `emcip-admin-api` (port 9087), `emcip-llm-orchestrator` (port 9084), `emcip-audit-service` (port 9086).

- [ ] **Step 1: Add filter bean to emcip-admin-api SecurityConfig**

In `emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java`, add:

```java
@Bean
public TenantContextFilter tenantContextFilter() {
    return new TenantContextFilter();
}
```

Add import: `import io.emcip.common.tenant.TenantContextFilter;`

- [ ] **Step 2: Add filter bean to emcip-llm-orchestrator and emcip-audit-service**

In each service's main `@Configuration` class (or create a new one named `TenantConfig.java`):

```java
package io.emcip.<module>.config;

import io.emcip.common.tenant.TenantContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TenantConfig {

    @Bean
    public TenantContextFilter tenantContextFilter() {
        return new TenantContextFilter();
    }
}
```

Create this file in:
- `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/TenantConfig.java`
- `emcip-audit-service/src/main/java/io/emcip/audit/service/config/TenantConfig.java`

- [ ] **Step 3: Commit**

```bash
git add emcip-admin-api/src/ emcip-llm-orchestrator/src/ emcip-audit-service/src/
git commit -m "feat(5.1): register TenantContextFilter in HTTP services"
```

---

### Task 7: Tenant isolation integration test

**Files:**
- Create: `emcip-policy-engine/src/test/java/io/emcip/policy/engine/TenantIsolationIT.java`

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.policy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.TenantContext;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Tag("tenant-isolation")
@EnabledIfEnvironmentVariable(named = "ECIP_IT_ENABLED", matches = "true")
class TenantIsolationIT {

    @Autowired PolicyRuleConfigRepository ruleRepository;

    private final String tenantA = UUID.randomUUID().toString();
    private final String tenantB = UUID.randomUUID().toString();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        ruleRepository.deleteAll();
    }

    @Test
    @Transactional
    void tenantACannotSeeTenanBRules() {
        // Save rule for tenant A
        TenantContext.setTenantId(tenantA);
        PolicyRuleConfig ruleA = buildRule("Rule for A", tenantA);
        ruleRepository.save(ruleA);

        // Save rule for tenant B
        TenantContext.setTenantId(tenantB);
        PolicyRuleConfig ruleB = buildRule("Rule for B", tenantB);
        ruleRepository.save(ruleB);

        // Query as tenant A — should only see tenant A's rule
        TenantContext.setTenantId(tenantA);
        List<PolicyRuleConfig> rulesForA =
                ruleRepository.findByTenantIdAndActiveTrueOrderByPriorityAsc(tenantA);

        assertThat(rulesForA).hasSize(1);
        assertThat(rulesForA.get(0).getName()).isEqualTo("Rule for A");
    }

    private PolicyRuleConfig buildRule(String name, String tenantId) {
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setId(UUID.randomUUID().toString());
        rule.setName(name);
        rule.setTargetIntent("SPAM");
        rule.setMinConfidence(0.8);
        rule.setAction("BLOCK");
        rule.setReason("test");
        rule.setPriority(1);
        rule.setActive(true);
        rule.setTenantId(UUID.fromString(tenantId));
        return rule;
    }
}
```

- [ ] **Step 2: Add tenantId field to PolicyRuleConfig entity**

In `emcip-policy-engine/src/main/java/io/emcip/policy/engine/entity/PolicyRuleConfig.java`, add:

```java
import java.util.UUID;

@Column(nullable = false)
private UUID tenantId;
```

- [ ] **Step 3: Add tenant-scoped query to PolicyRuleConfigRepository**

In `emcip-policy-engine/src/main/java/io/emcip/policy/engine/repository/PolicyRuleConfigRepository.java`, add:

```java
List<PolicyRuleConfig> findByTenantIdAndActiveTrueOrderByPriorityAsc(UUID tenantId);
```

- [ ] **Step 4: Run test**

```bash
docker compose up -d postgres
ECIP_IT_ENABLED=true mvn test -pl emcip-policy-engine -Dgroups=tenant-isolation
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add emcip-policy-engine/src/
git commit -m "test(5.1): add tenant isolation integration test; add tenantId to PolicyRuleConfig"
```

---

### Verification

```bash
# All core tests pass
mvn test -pl emcip-core

# Tenant CRUD via admin-api
curl -s -X POST http://localhost:9087/api/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])"
# Use returned token as JWT:
TOKEN=<token>

curl -s -X POST http://localhost:9087/api/tenants \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Tenant Alpha"}'
# → {"id":"<uuid>","name":"Tenant Alpha","createdAt":"..."}

curl -s http://localhost:9087/api/tenants \
  -H "Authorization: Bearer $TOKEN"
# → [{"id":"<uuid>","name":"Tenant Alpha",...}]
```
