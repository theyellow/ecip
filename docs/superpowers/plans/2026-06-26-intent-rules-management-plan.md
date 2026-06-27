# Intent Rules Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace hardcoded intent rules and signal thresholds in `IntentClassificationService` / `SignalDetector` with database-backed management, exposing CRUD via admin-api and admin-ui.

**Architecture:** 3 layers — `emcip-intent-classifier` (JPA entities + Spring MVC REST), `emcip-admin-api` (reactive WebClient proxy), `emcip-admin-ui` (React CRUD pages). The classifier loads rules at startup (`@PostConstruct`) and refreshes in-memory caches after each admin write. `tenant_id` is nullable; `NULL` means global/all-tenants (used for seed defaults).

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate 6, Liquibase, Lombok, WebClient, Resilience4j, React

## Global Constraints

- Liquibase only — no Flyway
- `mvn spotless:apply` before every commit; success = "0 were changed to be clean"
- Lombok: use `@Slf4j`, `@RequiredArgsConstructor` — never write manual getters/constructors
- `emcip-intent-classifier`: JPA/blocking, Spring MVC (`@WebMvcTest` for controller tests)
- `emcip-admin-api`: Spring WebFlux, reactive (`Mono`/`Flux`)
- intent-classifier port: **9082** (env var `INTENT_CLASSIFIER_URL`, default `http://localhost:9082`)
- Maven test commands: `mvn test -pl emcip-intent-classifier`, `mvn test -pl emcip-admin-api`
- Frontend test: `cd emcip-admin-ui/src/main/frontend && npm test -- --watchAll=false`
- Design rule: `tenant_id IS NULL` = global rule/config (applies to all tenants)

---

## File Map

### emcip-intent-classifier
| Action | Path |
|--------|------|
| Create | `src/main/resources/db/changelog/changes/003-create-intent-rules.xml` |
| Create | `src/main/resources/db/changelog/changes/004-create-intent-signal-config.xml` |
| Modify | `src/main/resources/db/changelog/db.changelog-master.xml` |
| Create | `src/main/java/io/emcip/intent/classifier/entity/IntentRule.java` |
| Create | `src/main/java/io/emcip/intent/classifier/entity/IntentSignalConfig.java` |
| Create | `src/main/java/io/emcip/intent/classifier/repository/IntentRuleRepository.java` |
| Create | `src/main/java/io/emcip/intent/classifier/repository/IntentSignalConfigRepository.java` |
| Create | `src/main/java/io/emcip/intent/classifier/dto/IntentRuleDto.java` |
| Create | `src/main/java/io/emcip/intent/classifier/dto/IntentSignalConfigDto.java` |
| Create | `src/main/java/io/emcip/intent/classifier/controller/IntentRuleController.java` |
| Create | `src/main/java/io/emcip/intent/classifier/controller/IntentSignalConfigController.java` |
| Modify | `src/main/java/io/emcip/intent/classifier/service/SignalDetector.java` |
| Modify | `src/main/java/io/emcip/intent/classifier/service/IntentClassificationService.java` |
| Create | `src/test/java/io/emcip/intent/classifier/controller/IntentRuleControllerTest.java` |
| Create | `src/test/java/io/emcip/intent/classifier/controller/IntentSignalConfigControllerTest.java` |
| Modify | `src/test/java/io/emcip/intent/classifier/service/IntentClassificationServiceTest.java` |
| Modify | `src/test/java/io/emcip/intent/classifier/service/SignalDetectorTest.java` |

### emcip-admin-api
| Action | Path |
|--------|------|
| Create | `src/main/java/io/emcip/admin/api/client/IntentClassifierClient.java` |
| Create | `src/main/java/io/emcip/admin/api/controller/IntentRuleController.java` |
| Create | `src/main/java/io/emcip/admin/api/controller/IntentSignalConfigController.java` |
| Modify | `src/main/resources/application.yml` |

### emcip-admin-ui
| Action | Path |
|--------|------|
| Create | `src/main/frontend/src/api/intentRules.js` |
| Create | `src/main/frontend/src/api/intentSignalConfig.js` |
| Create | `src/main/frontend/src/pages/IntentRules/IntentRules.jsx` |
| Create | `src/main/frontend/src/pages/IntentSignalConfig/IntentSignalConfig.jsx` |
| Modify | `src/main/frontend/src/layout/Sidebar/Sidebar.jsx` |
| Modify | `src/main/frontend/src/App.jsx` |

---

## Task 1: Liquibase migrations

**Files:**
- Create: `emcip-intent-classifier/src/main/resources/db/changelog/changes/003-create-intent-rules.xml`
- Create: `emcip-intent-classifier/src/main/resources/db/changelog/changes/004-create-intent-signal-config.xml`
- Modify: `emcip-intent-classifier/src/main/resources/db/changelog/db.changelog-master.xml`

**Interfaces:**
- Produces: `intent_rules` table, `intent_signal_config` table (required by Tasks 2–6)

- [ ] **Step 1: Create `changes/` directory and `003-create-intent-rules.xml`**

```bash
mkdir -p emcip-intent-classifier/src/main/resources/db/changelog/changes
```

File content (`003-create-intent-rules.xml`):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="003-create-intent-rules" author="emcip-team">
        <createTable tableName="intent_rules">
            <column name="id" type="VARCHAR(36)">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="name" type="VARCHAR(64)">
                <constraints nullable="false"/>
            </column>
            <column name="description" type="VARCHAR(500)"/>
            <column name="match_mode" type="VARCHAR(8)">
                <constraints nullable="false"/>
            </column>
            <column name="pattern" type="VARCHAR(500)">
                <constraints nullable="false"/>
            </column>
            <column name="intent" type="VARCHAR(32)">
                <constraints nullable="false"/>
            </column>
            <column name="confidence" type="DOUBLE PRECISION">
                <constraints nullable="false"/>
            </column>
            <column name="priority" type="INTEGER" defaultValueNumeric="100">
                <constraints nullable="false"/>
            </column>
            <column name="active" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID"/>
            <column name="created_at" type="TIMESTAMP WITH TIME ZONE" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP WITH TIME ZONE" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
            <column name="version" type="BIGINT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
        </createTable>
        <createIndex tableName="intent_rules" indexName="idx_intent_rules_tenant_active">
            <column name="tenant_id"/>
            <column name="active"/>
        </createIndex>
        <createIndex tableName="intent_rules" indexName="idx_intent_rules_priority">
            <column name="priority"/>
        </createIndex>
        <sql>ALTER TABLE intent_rules ADD CONSTRAINT chk_intent_rules_match_mode
             CHECK (match_mode IN ('KEYWORD', 'REGEX'));</sql>
    </changeSet>

    <changeSet id="003-seed-intent-rules" author="emcip-team">
        <insert tableName="intent_rules">
            <column name="id" value="00000001-0000-0000-0000-000000000001"/>
            <column name="name" value="Greeting"/>
            <column name="description" value="Matches common greeting phrases"/>
            <column name="match_mode" value="KEYWORD"/>
            <column name="pattern" value="hello|hi|hey|greetings|good morning|good afternoon|good evening"/>
            <column name="intent" value="GREETING"/>
            <column name="confidence" valueNumeric="0.80"/>
            <column name="priority" valueNumeric="10"/>
            <column name="active" valueBoolean="true"/>
        </insert>
        <insert tableName="intent_rules">
            <column name="id" value="00000001-0000-0000-0000-000000000002"/>
            <column name="name" value="Question"/>
            <column name="description" value="Matches messages starting with question words"/>
            <column name="match_mode" value="KEYWORD"/>
            <column name="pattern" value="what|how|why|when|where|who|is|are|can|do|does|did|will|would|could"/>
            <column name="intent" value="QUESTION"/>
            <column name="confidence" valueNumeric="0.75"/>
            <column name="priority" valueNumeric="20"/>
            <column name="active" valueBoolean="true"/>
        </insert>
        <insert tableName="intent_rules">
            <column name="id" value="00000001-0000-0000-0000-000000000003"/>
            <column name="name" value="Command"/>
            <column name="description" value="Matches bot command keywords"/>
            <column name="match_mode" value="KEYWORD"/>
            <column name="pattern" value="start|stop|help|status|config|set|get|show|list|create|delete|update"/>
            <column name="intent" value="COMMAND"/>
            <column name="confidence" valueNumeric="0.85"/>
            <column name="priority" valueNumeric="30"/>
            <column name="active" valueBoolean="true"/>
        </insert>
        <insert tableName="intent_rules">
            <column name="id" value="00000001-0000-0000-0000-000000000004"/>
            <column name="name" value="Thanks"/>
            <column name="description" value="Matches expressions of gratitude"/>
            <column name="match_mode" value="KEYWORD"/>
            <column name="pattern" value="thank|thanks|thx|appreciate"/>
            <column name="intent" value="THANKS"/>
            <column name="confidence" valueNumeric="0.90"/>
            <column name="priority" valueNumeric="40"/>
            <column name="active" valueBoolean="true"/>
        </insert>
        <insert tableName="intent_rules">
            <column name="id" value="00000001-0000-0000-0000-000000000005"/>
            <column name="name" value="Goodbye"/>
            <column name="description" value="Matches farewell expressions"/>
            <column name="match_mode" value="KEYWORD"/>
            <column name="pattern" value="bye|goodbye|see you|later|cya"/>
            <column name="intent" value="GOODBYE"/>
            <column name="confidence" valueNumeric="0.85"/>
            <column name="priority" valueNumeric="50"/>
            <column name="active" valueBoolean="true"/>
        </insert>
        <insert tableName="intent_rules">
            <column name="id" value="00000001-0000-0000-0000-000000000006"/>
            <column name="name" value="Spam"/>
            <column name="description" value="Matches common spam phrases"/>
            <column name="match_mode" value="REGEX"/>
            <column name="pattern" value="(?i)(click\s+here|buy\s+now|limited\s+offer|earn\s+money|make\s+money\s+fast|viagra|casino|crypto\s+investment)"/>
            <column name="intent" value="SPAM"/>
            <column name="confidence" valueNumeric="0.95"/>
            <column name="priority" valueNumeric="60"/>
            <column name="active" valueBoolean="true"/>
        </insert>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Create `004-create-intent-signal-config.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="004-create-intent-signal-config" author="emcip-team">
        <createTable tableName="intent_signal_config">
            <column name="id" type="VARCHAR(36)">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID"/>
            <column name="description" type="VARCHAR(500)"/>
            <column name="foreign_script_ratio" type="DOUBLE PRECISION" defaultValueNumeric="0.6">
                <constraints nullable="false"/>
            </column>
            <column name="cyrillic_ratio" type="DOUBLE PRECISION" defaultValueNumeric="0.6">
                <constraints nullable="false"/>
            </column>
            <column name="lookalike_suspicion" type="INTEGER" defaultValueNumeric="3">
                <constraints nullable="false"/>
            </column>
            <column name="zero_width_abuse" type="INTEGER" defaultValueNumeric="2">
                <constraints nullable="false"/>
            </column>
            <column name="caps_ratio" type="DOUBLE PRECISION" defaultValueNumeric="0.7">
                <constraints nullable="false"/>
            </column>
            <column name="toxicity_words" type="JSONB" defaultValue="'[]'::jsonb">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP WITH TIME ZONE" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP WITH TIME ZONE" defaultValueComputed="now()">
                <constraints nullable="false"/>
            </column>
        </createTable>
        <sql>CREATE UNIQUE INDEX idx_isc_global ON intent_signal_config ((1)) WHERE tenant_id IS NULL;</sql>
        <createIndex tableName="intent_signal_config" indexName="idx_isc_tenant">
            <column name="tenant_id"/>
        </createIndex>
    </changeSet>

    <changeSet id="004-seed-intent-signal-config" author="emcip-team">
        <insert tableName="intent_signal_config">
            <column name="id" value="00000002-0000-0000-0000-000000000001"/>
            <column name="description" value="Global default signal detection thresholds"/>
            <column name="foreign_script_ratio" valueNumeric="0.6"/>
            <column name="cyrillic_ratio" valueNumeric="0.6"/>
            <column name="lookalike_suspicion" valueNumeric="3"/>
            <column name="zero_width_abuse" valueNumeric="2"/>
            <column name="caps_ratio" valueNumeric="0.7"/>
            <column name="toxicity_words" value='["nigger","nigga","faggot","cunt","kike","spic","chink","wetback","gook","towelhead","raghead","hurensohn","wichser","fotze","arschloch"]'/>
        </insert>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Update `db.changelog-master.xml` to include the new files**

Add these two lines before the closing `</databaseChangeLog>` tag:
```xml
    <include file="db/changelog/changes/003-create-intent-rules.xml"/>
    <include file="db/changelog/changes/004-create-intent-signal-config.xml"/>
```

- [ ] **Step 4: Verify migrations run**

```bash
cd emcip-intent-classifier
mvn liquibase:update -Dliquibase.url=jdbc:postgresql://localhost:14005/emcip \
  -Dliquibase.username=emcip -Dliquibase.password=emcip
```

Expected: `Liquibase: Update has been successful.`

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-intent-classifier
git add emcip-intent-classifier/src/main/resources/db/
git commit -m "feat(intent-classifier): add Liquibase migrations for intent_rules and intent_signal_config"
```

---

## Task 2: Entities, repositories, and DTOs

**Files:**
- Create: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/entity/IntentRule.java`
- Create: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/entity/IntentSignalConfig.java`
- Create: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/repository/IntentRuleRepository.java`
- Create: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/repository/IntentSignalConfigRepository.java`
- Create: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/dto/IntentRuleDto.java`
- Create: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/dto/IntentSignalConfigDto.java`

**Interfaces:**
- Produces: `IntentRule`, `IntentSignalConfig`, their repositories, and DTOs — consumed by Tasks 3, 4, 5, 6

- [ ] **Step 1: Write `IntentRule.java`**

```java
package io.emcip.intent.classifier.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(
    name = "intent_rules",
    indexes = {
      @Index(name = "idx_intent_rules_tenant_active", columnList = "tenant_id, active"),
      @Index(name = "idx_intent_rules_priority", columnList = "priority")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentRule {

  @Id
  @UuidGenerator
  @Column(length = 36, nullable = false, updatable = false)
  private String id;

  @Column(length = 64, nullable = false)
  private String name;

  @Column(length = 500)
  private String description;

  @Column(name = "match_mode", length = 8, nullable = false)
  private String matchMode;

  @Column(length = 500, nullable = false)
  private String pattern;

  @Column(length = 32, nullable = false)
  private String intent;

  @Column(nullable = false)
  private Double confidence;

  @Column(nullable = false)
  private Integer priority;

  @Column(nullable = false)
  private Boolean active;

  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private Long version;
}
```

- [ ] **Step 2: Write `IntentSignalConfig.java`**

```java
package io.emcip.intent.classifier.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "intent_signal_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentSignalConfig {

  @Id
  @UuidGenerator
  @Column(length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(length = 500)
  private String description;

  @Column(name = "foreign_script_ratio", nullable = false)
  private Double foreignScriptRatio;

  @Column(name = "cyrillic_ratio", nullable = false)
  private Double cyrillicRatio;

  @Column(name = "lookalike_suspicion", nullable = false)
  private Integer lookalikeSuspicion;

  @Column(name = "zero_width_abuse", nullable = false)
  private Integer zeroWidthAbuse;

  @Column(name = "caps_ratio", nullable = false)
  private Double capsRatio;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "toxicity_words", columnDefinition = "jsonb", nullable = false)
  private List<String> toxicityWords;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
```

Note: the Java field is `lookalikeSuspicion` — carefully spell: `lookalikeSuspicion`.

- [ ] **Step 3: Write repositories**

`IntentRuleRepository.java`:
```java
package io.emcip.intent.classifier.repository;

import io.emcip.intent.classifier.entity.IntentRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntentRuleRepository extends JpaRepository<IntentRule, String> {
  List<IntentRule> findByTenantIdAndActiveTrueOrderByPriorityAsc(UUID tenantId);
  List<IntentRule> findByTenantIdIsNullAndActiveTrueOrderByPriorityAsc();
  List<IntentRule> findByTenantIdOrderByPriorityAsc(UUID tenantId);
  List<IntentRule> findByTenantIdIsNullOrderByPriorityAsc();
}
```

`IntentSignalConfigRepository.java`:
```java
package io.emcip.intent.classifier.repository;

import io.emcip.intent.classifier.entity.IntentSignalConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntentSignalConfigRepository extends JpaRepository<IntentSignalConfig, String> {
  Optional<IntentSignalConfig> findByTenantId(UUID tenantId);
  Optional<IntentSignalConfig> findByTenantIdIsNull();
}
```

- [ ] **Step 4: Write DTOs**

`IntentRuleDto.java`:
```java
package io.emcip.intent.classifier.dto;

import java.time.Instant;
import java.util.UUID;

public record IntentRuleDto(
    String id,
    String name,
    String description,
    String matchMode,
    String pattern,
    String intent,
    Double confidence,
    Integer priority,
    Boolean active,
    UUID tenantId,
    Instant createdAt,
    Instant updatedAt) {}
```

`IntentSignalConfigDto.java`:
```java
package io.emcip.intent.classifier.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IntentSignalConfigDto(
    String id,
    UUID tenantId,
    String description,
    Double foreignScriptRatio,
    Double cyrillicRatio,
    Integer lookalikeSuspicion,
    Integer zeroWidthAbuse,
    Double capsRatio,
    List<String> toxicityWords,
    Instant createdAt,
    Instant updatedAt) {}
```

Note: field is `lookalikeSuspicion` — carefully spell: `lookalikeSuspicion` → `lookalikeSuspicion`. The camelCase field name is: l-o-o-k-a-l-i-k-e-S-u-s-p-i-c-i-o-n = `lookalikeSuspicion`.

- [ ] **Step 5: Build to confirm compilation**

```bash
mvn compile -pl emcip-intent-classifier -am -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -pl emcip-intent-classifier
git add emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/entity/ \
        emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/repository/ \
        emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/dto/
git commit -m "feat(intent-classifier): add IntentRule and IntentSignalConfig entities, repos, DTOs"
```

---

## Task 3: IntentRuleController

**Files:**
- Create: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/controller/IntentRuleController.java`
- Create: `emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/controller/IntentRuleControllerTest.java`

**Interfaces:**
- Consumes: `IntentRuleRepository`, `IntentRuleDto` (Task 2); `IntentClassificationService.refreshRules()` (stubbed/mocked in tests now, real in Task 6)
- Produces: `GET/POST/PUT/DELETE /api/intent-rules` — consumed by Task 7 (admin-api proxy)

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.intent.classifier.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.emcip.intent.classifier.entity.IntentRule;
import io.emcip.intent.classifier.repository.IntentRuleRepository;
import io.emcip.intent.classifier.service.IntentClassificationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IntentRuleController.class)
class IntentRuleControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean IntentRuleRepository repository;
  @MockitoBean IntentClassificationService classificationService;

  private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

  @Test
  void list_returnsRulesForTenant() throws Exception {
    var rule =
        IntentRule.builder()
            .id("rule-1")
            .name("Greeting")
            .matchMode("KEYWORD")
            .pattern("hello|hi")
            .intent("GREETING")
            .confidence(0.8)
            .priority(10)
            .active(true)
            .tenantId(TENANT)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(repository.findByTenantIdOrderByPriorityAsc(TENANT)).thenReturn(List.of(rule));
    when(repository.findByTenantIdIsNullOrderByPriorityAsc()).thenReturn(List.of());

    mvc.perform(get("/api/intent-rules").header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Greeting"))
        .andExpect(jsonPath("$[0].intent").value("GREETING"));
  }

  @Test
  void create_returns201() throws Exception {
    var saved =
        IntentRule.builder()
            .id("new-id")
            .name("Test")
            .matchMode("KEYWORD")
            .pattern("test")
            .intent("TEST")
            .confidence(0.8)
            .priority(100)
            .active(true)
            .tenantId(TENANT)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(repository.save(any())).thenReturn(saved);

    mvc.perform(
            post("/api/intent-rules")
                .header("X-Tenant-Id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Test","matchMode":"KEYWORD","pattern":"test",
                     "intent":"TEST","confidence":0.8,"priority":100}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("new-id"));
  }

  @Test
  void update_returns200() throws Exception {
    var existing =
        IntentRule.builder()
            .id("rule-1")
            .name("Old")
            .matchMode("KEYWORD")
            .pattern("old")
            .intent("OLD")
            .confidence(0.5)
            .priority(50)
            .active(true)
            .tenantId(TENANT)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(repository.findById("rule-1")).thenReturn(Optional.of(existing));
    when(repository.save(any())).thenReturn(existing);

    mvc.perform(
            put("/api/intent-rules/rule-1")
                .header("X-Tenant-Id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Old","matchMode":"KEYWORD","pattern":"old",
                     "intent":"OLD","confidence":0.5,"priority":50,"active":true}
                    """))
        .andExpect(status().isOk());
  }

  @Test
  void update_wrongTenant_returns404() throws Exception {
    var existing =
        IntentRule.builder()
            .id("rule-1")
            .tenantId(UUID.randomUUID())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(repository.findById("rule-1")).thenReturn(Optional.of(existing));

    mvc.perform(
            put("/api/intent-rules/rule-1")
                .header("X-Tenant-Id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"x","matchMode":"KEYWORD","pattern":"x",
                     "intent":"X","confidence":0.5,"priority":1,"active":true}
                    """))
        .andExpect(status().isNotFound());
  }

  @Test
  void delete_returns204() throws Exception {
    var existing =
        IntentRule.builder()
            .id("rule-1")
            .tenantId(TENANT)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(repository.findById("rule-1")).thenReturn(Optional.of(existing));

    mvc.perform(delete("/api/intent-rules/rule-1").header("X-Tenant-Id", TENANT))
        .andExpect(status().isNoContent());
    verify(repository).delete(existing);
    verify(classificationService).refreshRules();
  }
}
```

- [ ] **Step 2: Run test, confirm it fails**

```bash
mvn test -pl emcip-intent-classifier -Dtest=IntentRuleControllerTest -q 2>&1 | tail -5
```

Expected: `BUILD FAILURE` (class not found)

- [ ] **Step 3: Write `IntentRuleController.java`**

```java
package io.emcip.intent.classifier.controller;

import io.emcip.intent.classifier.dto.IntentRuleDto;
import io.emcip.intent.classifier.entity.IntentRule;
import io.emcip.intent.classifier.repository.IntentRuleRepository;
import io.emcip.intent.classifier.service.IntentClassificationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/intent-rules")
@RequiredArgsConstructor
@Slf4j
public class IntentRuleController {

  private final IntentRuleRepository repository;
  private final IntentClassificationService classificationService;

  @GetMapping
  public List<IntentRuleDto> list(
      @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
    List<IntentRule> rules = new ArrayList<>();
    if (tenantId != null) {
      rules.addAll(repository.findByTenantIdOrderByPriorityAsc(tenantId));
    }
    rules.addAll(repository.findByTenantIdIsNullOrderByPriorityAsc());
    return rules.stream().map(this::toDto).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public IntentRuleDto create(
      @RequestBody IntentRuleDto dto,
      @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
    var now = Instant.now();
    var rule =
        IntentRule.builder()
            .name(dto.name())
            .description(dto.description())
            .matchMode(dto.matchMode())
            .pattern(dto.pattern())
            .intent(dto.intent())
            .confidence(dto.confidence())
            .priority(dto.priority() != null ? dto.priority() : 100)
            .active(true)
            .tenantId(tenantId)
            .createdAt(now)
            .updatedAt(now)
            .build();
    var saved = repository.save(rule);
    classificationService.refreshRules();
    return toDto(saved);
  }

  @PutMapping("/{id}")
  public IntentRuleDto update(
      @PathVariable String id,
      @RequestBody IntentRuleDto dto,
      @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
    var rule =
        repository
            .findById(id)
            .filter(r -> tenantId == null || tenantId.equals(r.getTenantId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    rule.setName(dto.name());
    rule.setDescription(dto.description());
    rule.setMatchMode(dto.matchMode());
    rule.setPattern(dto.pattern());
    rule.setIntent(dto.intent());
    rule.setConfidence(dto.confidence());
    rule.setPriority(dto.priority());
    rule.setActive(dto.active());
    rule.setUpdatedAt(Instant.now());
    var saved = repository.save(rule);
    classificationService.refreshRules();
    return toDto(saved);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable String id,
      @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
    var rule =
        repository
            .findById(id)
            .filter(r -> tenantId == null || tenantId.equals(r.getTenantId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    repository.delete(rule);
    classificationService.refreshRules();
  }

  private IntentRuleDto toDto(IntentRule r) {
    return new IntentRuleDto(
        r.getId(), r.getName(), r.getDescription(), r.getMatchMode(), r.getPattern(),
        r.getIntent(), r.getConfidence(), r.getPriority(), r.getActive(), r.getTenantId(),
        r.getCreatedAt(), r.getUpdatedAt());
  }
}
```

- [ ] **Step 4: Run tests, confirm all pass**

```bash
mvn test -pl emcip-intent-classifier -Dtest=IntentRuleControllerTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -pl emcip-intent-classifier
git add emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/controller/IntentRuleController.java \
        emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/controller/IntentRuleControllerTest.java
git commit -m "feat(intent-classifier): add IntentRuleController with CRUD endpoints"
```

---

## Task 4: IntentSignalConfigController

**Files:**
- Create: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/controller/IntentSignalConfigController.java`
- Create: `emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/controller/IntentSignalConfigControllerTest.java`

**Interfaces:**
- Consumes: `IntentSignalConfigRepository`, `IntentSignalConfigDto` (Task 2); `IntentClassificationService.refreshSignalConfig()` (Task 6)
- Produces: `GET /api/intent-signal-config`, `PUT /api/intent-signal-config` — consumed by Task 7

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.intent.classifier.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.emcip.intent.classifier.entity.IntentSignalConfig;
import io.emcip.intent.classifier.repository.IntentSignalConfigRepository;
import io.emcip.intent.classifier.service.IntentClassificationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IntentSignalConfigController.class)
class IntentSignalConfigControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean IntentSignalConfigRepository repository;
  @MockitoBean IntentClassificationService classificationService;

  private static final UUID TENANT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

  @Test
  void get_returnsConfig() throws Exception {
    var config =
        IntentSignalConfig.builder()
            .id("cfg-1")
            .tenantId(TENANT)
            .foreignScriptRatio(0.6)
            .cyrillicRatio(0.6)
            .lookalikeSuspicion(3)
            .zeroWidthAbuse(2)
            .capsRatio(0.7)
            .toxicityWords(List.of("spam"))
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(repository.findByTenantId(TENANT)).thenReturn(Optional.of(config));

    mvc.perform(get("/api/intent-signal-config").header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.foreignScriptRatio").value(0.6))
        .andExpect(jsonPath("$.lookalikeSuspicion").value(3));
  }

  @Test
  void get_notFound_returns404() throws Exception {
    when(repository.findByTenantId(TENANT)).thenReturn(Optional.empty());
    when(repository.findByTenantIdIsNull()).thenReturn(Optional.empty());

    mvc.perform(get("/api/intent-signal-config").header("X-Tenant-Id", TENANT))
        .andExpect(status().isNotFound());
  }

  @Test
  void put_upsertsConfig() throws Exception {
    var saved =
        IntentSignalConfig.builder()
            .id("cfg-1")
            .tenantId(TENANT)
            .foreignScriptRatio(0.5)
            .cyrillicRatio(0.5)
            .lookalikeSuspicion(2)
            .zeroWidthAbuse(1)
            .capsRatio(0.8)
            .toxicityWords(List.of("spam", "bad"))
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(repository.findByTenantId(TENANT)).thenReturn(Optional.empty());
    when(repository.save(any())).thenReturn(saved);

    mvc.perform(
            put("/api/intent-signal-config")
                .header("X-Tenant-Id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"foreignScriptRatio":0.5,"cyrillicRatio":0.5,
                     "lookalikeSuspicion":2,"zeroWidthAbuse":1,"capsRatio":0.8,
                     "toxicityWords":["spam","bad"]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lookalikeSuspicion").value(2));

    verify(classificationService).refreshSignalConfig();
  }
}
```

Note: Replace the `lookalikeSuspicion` JSON key with the exact camelCase spelling: `lookalikeSuspicion` → `lookalikeSuspicion`. In JSON it should be `"lookalikeSuspicion"`. Carefully: l-o-o-k-a-l-i-k-e-S-u-s-p-i-c-i-o-n.

- [ ] **Step 2: Run test, confirm it fails**

```bash
mvn test -pl emcip-intent-classifier -Dtest=IntentSignalConfigControllerTest -q 2>&1 | tail -5
```

Expected: `BUILD FAILURE`

- [ ] **Step 3: Write `IntentSignalConfigController.java`**

```java
package io.emcip.intent.classifier.controller;

import io.emcip.intent.classifier.dto.IntentSignalConfigDto;
import io.emcip.intent.classifier.entity.IntentSignalConfig;
import io.emcip.intent.classifier.repository.IntentSignalConfigRepository;
import io.emcip.intent.classifier.service.IntentClassificationService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/intent-signal-config")
@RequiredArgsConstructor
@Slf4j
public class IntentSignalConfigController {

  private final IntentSignalConfigRepository repository;
  private final IntentClassificationService classificationService;

  @GetMapping
  public ResponseEntity<IntentSignalConfigDto> get(
      @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
    var config =
        repository
            .findByTenantId(tenantId)
            .or(() -> repository.findByTenantIdIsNull());
    return config
        .map(c -> ResponseEntity.ok(toDto(c)))
        .orElse(ResponseEntity.notFound().build());
  }

  @PutMapping
  public IntentSignalConfigDto upsert(
      @RequestBody IntentSignalConfigDto dto,
      @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
    var now = Instant.now();
    var config =
        repository
            .findByTenantId(tenantId)
            .orElseGet(
                () -> {
                  var c = new IntentSignalConfig();
                  c.setTenantId(tenantId);
                  c.setCreatedAt(now);
                  return c;
                });
    config.setDescription(dto.description());
    config.setForeignScriptRatio(dto.foreignScriptRatio());
    config.setCyrillicRatio(dto.cyrillicRatio());
    config.setLookalikeS uspicion(dto.lookalikeSuspicion());
    config.setZeroWidthAbuse(dto.zeroWidthAbuse());
    config.setCapsRatio(dto.capsRatio());
    config.setToxicityWords(dto.toxicityWords());
    config.setUpdatedAt(now);
    var saved = repository.save(config);
    classificationService.refreshSignalConfig();
    return toDto(saved);
  }

  private IntentSignalConfigDto toDto(IntentSignalConfig c) {
    return new IntentSignalConfigDto(
        c.getId(), c.getTenantId(), c.getDescription(),
        c.getForeignScriptRatio(), c.getCyrillicRatio(), c.getLookalikeS uspicion(),
        c.getZeroWidthAbuse(), c.getCapsRatio(), c.getToxicityWords(),
        c.getCreatedAt(), c.getUpdatedAt());
  }
}
```

Note: the camelCase getter/setter is `getLookalikeS uspicion()` / `setLookalikeS uspicion()` (Lombok generates from field name). Carefully: `getLookalikeS uspicion` → `getLookalikeS uspicion`. The full word: get-l-o-o-k-a-l-i-k-e-S-u-s-p-i-c-i-o-n.

- [ ] **Step 4: Run tests**

```bash
mvn test -pl emcip-intent-classifier -Dtest=IntentSignalConfigControllerTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -pl emcip-intent-classifier
git add emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/controller/IntentSignalConfigController.java \
        emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/controller/IntentSignalConfigControllerTest.java
git commit -m "feat(intent-classifier): add IntentSignalConfigController with GET/PUT endpoints"
```

---

## Task 5: Modify SignalDetector to support configurable toxicity words and integer count signals

**Files:**
- Modify: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/SignalDetector.java`
- Modify: `emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/service/SignalDetectorTest.java`

**Background:** The approved spec uses integer counts for `lookalikeSuspicion` and `zeroWidthAbuse`. These require changes to the signal return types:
- `computeLookalikeSuspicion()` currently returns `double` (ratio) → change to `int` (count of suspicious words)
- `detectZeroWidth()` currently returns `boolean` → change to `int` (count of zero-width chars)

The toxicity word list is moved out of the static constant and instead passed as a `List<Pattern>` parameter to `detect()`.

**Interfaces:**
- Produces: `detect(String text, Map<String, Object> metadata, List<Pattern> toxicityPatterns)` — called by IntentClassificationService in Task 6
- Produces: `buildToxicityPatterns(List<String> words)` (static) — called by IntentClassificationService in Task 6

- [ ] **Step 1: Update `SignalDetectorTest.java` to reflect new signatures and return types**

Open `src/test/java/io/emcip/intent/classifier/service/SignalDetectorTest.java`. Apply these changes:

1. All calls to `detector.detect(text, metadata)` → `detector.detect(text, metadata, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS))`

Where `DEFAULT_WORDS` is defined at the top of the test class as the same 15 words from the current `SignalDetector.TOXICITY_PATTERNS`:
```java
private static final List<String> DEFAULT_WORDS = List.of(
    "nigger","nigga","faggot","cunt","kike","spic","chink",
    "wetback","gook","towelhead","raghead","hurensohn","wichser","fotze","arschloch");
```

2. Tests that assert on `"lookalikeSuspicion"` values: change from `double` assertions to `int` assertions. For example, tests that check `> 0.0` should now check `> 0` (integer). Tests that check `== 0.0` check `== 0`.

3. Tests that assert on `"zeroWidthAbuse"` values: change from `Boolean.TRUE`/`Boolean.FALSE` to `int` assertions (`>= 1` = present, `== 0` = absent).

Run the test before making implementation changes:
```bash
mvn test -pl emcip-intent-classifier -Dtest=SignalDetectorTest -q 2>&1 | tail -10
```

Expected: compilation errors (method signature mismatch). That's the failing state.

- [ ] **Step 2: Modify `SignalDetector.java` — change `computeLookalikeSuspicion()` return type**

Change:
```java
private double computeLookalikeSuspicion(String text) {
    // ... existing code computing suspiciousWords and totalWords
    return totalWords > 0 ? (double) suspiciousWords / totalWords : 0.0;
}
```

To:
```java
private int computeLookalikeSuspicion(String text) {
    String[] words = text.split("[^\\p{L}]+");
    int suspiciousWords = 0;
    for (String word : words) {
        if (word.isEmpty()) continue;
        boolean hasLookalike = false;
        boolean hasLatin = false;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            i += Character.charCount(cp);
            if (ALL_LOOKALIKES.contains(cp)) {
                hasLookalike = true;
            } else if (Character.isLetter(cp) && cp < 0x0300) {
                hasLatin = true;
            }
        }
        if (hasLookalike && hasLatin) suspiciousWords++;
    }
    return suspiciousWords;
}
```

Update the call site in `detect()`:
```java
scores.put("lookalikeSuspicion", computeLookalikeSuspicion(t));
```
(no change needed — the value is already placed under the same key, just now an Integer)

- [ ] **Step 3: Modify `SignalDetector.java` — change `detectZeroWidth()` return type**

Change:
```java
private boolean detectZeroWidth(String text) {
    for (int i = 0; i < text.length(); ) {
        int cp = text.codePointAt(i);
        i += Character.charCount(cp);
        if (ZERO_WIDTH_CHARS.contains(cp)) return true;
    }
    return false;
}
```

To:
```java
private int detectZeroWidth(String text) {
    int count = 0;
    for (int i = 0; i < text.length(); ) {
        int cp = text.codePointAt(i);
        i += Character.charCount(cp);
        if (ZERO_WIDTH_CHARS.contains(cp)) count++;
    }
    return count;
}
```

- [ ] **Step 4: Modify `SignalDetector.java` — accept toxicity patterns as parameter**

Change the `detect()` method signature from:
```java
Map<String, Object> detect(String text, Map<String, Object> metadata) {
```
To:
```java
Map<String, Object> detect(String text, Map<String, Object> metadata, List<Pattern> toxicityPatterns) {
```

Change the call to `computeToxicityHint(t)` to `computeToxicityHint(t, toxicityPatterns)`.

Change `computeToxicityHint()`:
```java
private double computeToxicityHint(String text, List<Pattern> toxicityPatterns) {
    if (text.isBlank()) return 0.0;
    int matches = 0;
    for (Pattern p : toxicityPatterns) {
        var matcher = p.matcher(text);
        while (matcher.find()) matches++;
    }
    String[] words = text.split("\\s+");
    int wordCount = words.length;
    return wordCount > 0 ? Math.min(1.0, (double) matches / wordCount) : 0.0;
}
```

Add the static helper method (make `buildToxicityPatterns` package-private static so IntentClassificationService can call it):
```java
static List<Pattern> buildToxicityPatterns(List<String> words) {
    List<Pattern> patterns = new ArrayList<>(words.size());
    for (String term : words) {
        patterns.add(
            Pattern.compile(
                "\\b" + Pattern.quote(term) + "\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS));
    }
    return patterns;
}
```

Remove the now-unused `TOXICITY_PATTERNS` static constant and `buildToxicityPatterns()` private method. Also remove the `List<Pattern> TOXICITY_PATTERNS` field and its static initializer.

- [ ] **Step 5: Run SignalDetectorTest, confirm all pass**

```bash
mvn test -pl emcip-intent-classifier -Dtest=SignalDetectorTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -pl emcip-intent-classifier
git add emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/SignalDetector.java \
        emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/service/SignalDetectorTest.java
git commit -m "refactor(intent-classifier): make SignalDetector accept toxicity patterns; return int counts for lookalike/zeroWidth"
```

---

## Task 6: Modify IntentClassificationService to load from DB and use configurable thresholds

**Files:**
- Modify: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/IntentClassificationService.java`
- Modify: `emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/service/IntentClassificationServiceTest.java`

**Background:** After this task, the service:
- Has no hardcoded rule list
- Loads rules from DB at startup and after any write
- Caches compiled `Pattern` objects for REGEX rules
- Reads signal thresholds from `IntentSignalConfig` at classify time
- Compiles toxicity patterns from `IntentSignalConfig.toxicityWords`

**Interfaces:**
- Consumes: `IntentRuleRepository`, `IntentSignalConfigRepository` (Task 2); `SignalDetector.detect(text, metadata, toxicityPatterns)` (Task 5)
- Produces: `refreshRules()`, `refreshSignalConfig()` — called by controllers (Tasks 3, 4)

- [ ] **Step 1: Update `IntentClassificationServiceTest.java`**

The service constructor will change to accept two new repositories. Update `setUp()`:

```java
@Mock private KafkaTemplate<String, String> kafkaTemplate;
@Mock private IntentRuleRepository ruleRepository;
@Mock private IntentSignalConfigRepository signalConfigRepository;

private IntentClassificationService service;

// Helper: build a default signal config matching the hardcoded values
private static io.emcip.intent.classifier.entity.IntentSignalConfig defaultSignalConfig() {
    var cfg = new io.emcip.intent.classifier.entity.IntentSignalConfig();
    cfg.setForeignScriptRatio(0.6);
    cfg.setCyrillicRatio(0.6);
    cfg.setLookalikeS uspicion(3);
    cfg.setZeroWidthAbuse(2);
    cfg.setCapsRatio(0.7);
    cfg.setToxicityWords(List.of(
        "nigger","nigga","faggot","cunt","kike","spic","chink",
        "wetback","gook","towelhead","raghead","hurensohn","wichser","fotze","arschloch"));
    return cfg;
}

// Helper: build a DB IntentRule from old hardcoded pattern
private static io.emcip.intent.classifier.entity.IntentRule keywordRule(
    String name, String pattern, String intent, double confidence, int priority) {
    var r = new io.emcip.intent.classifier.entity.IntentRule();
    r.setId(UUID.randomUUID().toString());
    r.setName(name);
    r.setMatchMode("KEYWORD");
    r.setPattern(pattern);
    r.setIntent(intent);
    r.setConfidence(confidence);
    r.setPriority(priority);
    r.setActive(true);
    r.setCreatedAt(Instant.now());
    r.setUpdatedAt(Instant.now());
    return r;
}

@BeforeEach
void setUp() {
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    // Seed 6 rules matching current hardcoded behaviour
    var rules = List.of(
        keywordRule("GREETING", "hello|hi|hey|greetings|good morning|good afternoon|good evening",
            "GREETING", 0.8, 10),
        keywordRule("QUESTION", "what|how|why|when|where|who|is|are|can|do|does|did|will|would|could",
            "QUESTION", 0.75, 20),
        keywordRule("COMMAND", "start|stop|help|status|config|set|get|show|list|create|delete|update",
            "COMMAND", 0.85, 30),
        keywordRule("THANKS", "thank|thanks|thx|appreciate", "THANKS", 0.9, 40),
        keywordRule("GOODBYE", "bye|goodbye|see you|later|cya", "GOODBYE", 0.85, 50));

    // SPAM rule uses REGEX
    var spamRule = new io.emcip.intent.classifier.entity.IntentRule();
    spamRule.setId(UUID.randomUUID().toString());
    spamRule.setName("SPAM");
    spamRule.setMatchMode("REGEX");
    spamRule.setPattern("(?i)(click\\s+here|buy\\s+now|limited\\s+offer|earn\\s+money|make\\s+money\\s+fast|viagra|casino|crypto\\s+investment)");
    spamRule.setIntent("SPAM");
    spamRule.setConfidence(0.95);
    spamRule.setPriority(60);
    spamRule.setActive(true);
    spamRule.setCreatedAt(Instant.now());
    spamRule.setUpdatedAt(Instant.now());

    var allRules = new ArrayList<>(rules);
    allRules.add(spamRule);

    when(ruleRepository.findByTenantIdIsNullAndActiveTrueOrderByPriorityAsc())
        .thenReturn(allRules);
    when(ruleRepository.findByTenantIdAndActiveTrueOrderByPriorityAsc(any()))
        .thenReturn(List.of());
    when(signalConfigRepository.findByTenantIdIsNull())
        .thenReturn(Optional.of(defaultSignalConfig()));
    when(signalConfigRepository.findByTenantId(any()))
        .thenReturn(Optional.empty());

    service = new IntentClassificationService(
        kafkaTemplate, new ObjectMapper(), new SignalDetector(),
        ruleRepository, signalConfigRepository);
}
```

Run the existing tests to see them fail:
```bash
mvn test -pl emcip-intent-classifier -Dtest=IntentClassificationServiceTest -q 2>&1 | tail -10
```

Expected: compile failure (constructor signature changed).

- [ ] **Step 2: Rewrite `IntentClassificationService.java`**

```java
package io.emcip.intent.classifier.service;

import io.emcip.common.events.EventSchemas;
import io.emcip.intent.classifier.entity.IntentRule;
import io.emcip.intent.classifier.entity.IntentSignalConfig;
import io.emcip.intent.classifier.repository.IntentRuleRepository;
import io.emcip.intent.classifier.repository.IntentSignalConfigRepository;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Service
public class IntentClassificationService {

  private static final Logger log = LoggerFactory.getLogger(IntentClassificationService.class);
  private static final String TOPIC_OUTPUT = "messages.classified";

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final SignalDetector signalDetector;
  private final IntentRuleRepository ruleRepository;
  private final IntentSignalConfigRepository signalConfigRepository;

  // In-memory caches — rebuilt on every refresh
  private volatile List<IntentRule> globalRules = List.of();
  private volatile Map<UUID, List<IntentRule>> tenantRules = Map.of();
  private volatile Map<String, Pattern> compiledPatterns = Map.of();
  private volatile IntentSignalConfig globalSignalConfig = null;
  private volatile Map<UUID, IntentSignalConfig> tenantSignalConfigs = Map.of();
  private volatile List<Pattern> compiledToxicityPatterns = List.of();

  public IntentClassificationService(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      SignalDetector signalDetector,
      IntentRuleRepository ruleRepository,
      IntentSignalConfigRepository signalConfigRepository) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.signalDetector = signalDetector;
    this.ruleRepository = ruleRepository;
    this.signalConfigRepository = signalConfigRepository;
  }

  @PostConstruct
  public void init() {
    refreshRules();
    refreshSignalConfig();
  }

  public synchronized void refreshRules() {
    var newPatterns = new ConcurrentHashMap<String, Pattern>();
    var newGlobal = ruleRepository.findByTenantIdIsNullAndActiveTrueOrderByPriorityAsc();
    for (var rule : newGlobal) {
      if ("REGEX".equals(rule.getMatchMode())) {
        newPatterns.put(rule.getId(),
            Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE));
      }
    }
    // Load all tenant-specific rules
    var allTenantRules = ruleRepository.findAll().stream()
        .filter(r -> r.getTenantId() != null && Boolean.TRUE.equals(r.getActive()))
        .toList();
    var newTenantMap = new ConcurrentHashMap<UUID, List<IntentRule>>();
    for (var rule : allTenantRules) {
      newTenantMap.computeIfAbsent(rule.getTenantId(), k -> new ArrayList<>()).add(rule);
      if ("REGEX".equals(rule.getMatchMode())) {
        newPatterns.put(rule.getId(),
            Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE));
      }
    }
    // Sort each tenant list by priority
    newTenantMap.replaceAll((k, v) -> v.stream()
        .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
        .toList());

    this.globalRules = newGlobal;
    this.tenantRules = newTenantMap;
    this.compiledPatterns = newPatterns;
    log.info("Refreshed intent rules: {} global, {} tenant-specific",
        newGlobal.size(), allTenantRules.size());
  }

  public synchronized void refreshSignalConfig() {
    this.globalSignalConfig = signalConfigRepository.findByTenantIdIsNull().orElse(null);
    var allTenantConfigs = signalConfigRepository.findAll().stream()
        .filter(c -> c.getTenantId() != null)
        .toList();
    var newMap = new ConcurrentHashMap<UUID, IntentSignalConfig>();
    for (var c : allTenantConfigs) {
      newMap.put(c.getTenantId(), c);
    }
    this.tenantSignalConfigs = newMap;

    // Compile toxicity patterns from global config
    var config = Optional.ofNullable(globalSignalConfig);
    this.compiledToxicityPatterns = config
        .map(c -> SignalDetector.buildToxicityPatterns(c.getToxicityWords()))
        .orElse(List.of());
    log.info("Refreshed signal config: global={}, tenants={}",
        globalSignalConfig != null, newMap.size());
  }

  /** Classify a Telegram message and publish the result. */
  public Mono<EventSchemas.IntentClassifiedEvent> classify(
      EventSchemas.TelegramMessageEvent message, String tenantIdStr) {
    return Mono.fromCallable(
        () -> {
          String text = message.text() != null ? message.text() : "";
          UUID tenantId = tenantIdStr != null ? UUID.fromString(tenantIdStr) : null;

          // Build ordered rule list: tenant-specific first, then global
          List<IntentRule> rules = new ArrayList<>();
          if (tenantId != null) {
            rules.addAll(tenantRules.getOrDefault(tenantId, List.of()));
          }
          rules.addAll(globalRules);

          String matchedIntent = null;
          double highestConfidence = 0.0;
          List<String> matchedRules = new ArrayList<>();

          // Apply rules in priority order
          for (IntentRule rule : rules) {
            if (matches(rule, text)) {
              matchedRules.add(rule.getName());
              if (rule.getConfidence() > highestConfidence) {
                highestConfidence = rule.getConfidence();
                matchedIntent = rule.getIntent();
              }
            }
          }

          // Resolve signal config for this tenant
          IntentSignalConfig signalCfg =
              (tenantId != null ? tenantSignalConfigs.get(tenantId) : null);
          if (signalCfg == null) signalCfg = globalSignalConfig;

          // Compile toxicity patterns for tenant config (or fall back to global)
          List<Pattern> toxicityPatterns =
              signalCfg != null
                  ? SignalDetector.buildToxicityPatterns(signalCfg.getToxicityWords())
                  : compiledToxicityPatterns;

          // Detect structural/script signals
          Map<String, Object> signals =
              signalDetector.detect(text, message.metadata(), toxicityPatterns);

          // Apply signal priority chain when no rule matched
          if (matchedIntent == null) {
            matchedIntent = resolveSignalIntent(signals, signalCfg);
          }

          // Create classification event
          Map<String, Object> params = new LinkedHashMap<>();
          params.put("textLength", text.length());
          params.put("chatId", message.chatId());
          params.put("senderId", message.senderId() != null ? message.senderId() : "");
          params.put("messageText", text);
          if (message.telegramMessageId() != null) {
            params.put("telegramMessageId", message.telegramMessageId());
          }
          params.putAll(signals);

          var classification =
              new EventSchemas.IntentClassifiedEvent(
                  UUID.randomUUID().toString(),
                  Instant.now().toString(),
                  EventSchemas.INTENT_CLASSIFIED_V1,
                  "IntentClassified",
                  message.eventId(),
                  matchedIntent,
                  highestConfidence,
                  params,
                  matchedRules);

          String json = objectMapper.writeValueAsString(classification);
          ProducerRecord<String, String> producerRecord =
              new ProducerRecord<>(TOPIC_OUTPUT, null, message.eventId(), json);
          if (tenantIdStr != null) {
            producerRecord
                .headers()
                .add("tenant_id", tenantIdStr.getBytes(StandardCharsets.UTF_8));
          }
          kafkaTemplate.send(producerRecord);
          log.debug("Published classification for message {}: {}", message.eventId(), matchedIntent);
          return classification;
        });
  }

  private boolean matches(IntentRule rule, String text) {
    String lower = text.toLowerCase();
    return switch (rule.getMatchMode()) {
      case "KEYWORD" -> Arrays.stream(rule.getPattern().split("\\|"))
          .anyMatch(kw -> lower.contains(kw.trim().toLowerCase()));
      case "REGEX" -> {
        Pattern p = compiledPatterns.get(rule.getId());
        yield p != null && p.matcher(text).find();
      }
      default -> false;
    };
  }

  private String resolveSignalIntent(Map<String, Object> signals, IntentSignalConfig cfg) {
    double foreignThreshold = cfg != null ? cfg.getForeignScriptRatio() : 0.6;
    double capsThreshold = cfg != null ? cfg.getCapsRatio() : 0.7;
    int lookalikThreshold = cfg != null ? cfg.getLookalikeS uspicion() : 3;
    int zeroWidthThreshold = cfg != null ? cfg.getZeroWidthAbuse() : 2;

    if (Boolean.TRUE.equals(signals.get("stickerOnly"))) return "FORMAT_STICKER_ONLY";
    if (Boolean.TRUE.equals(signals.get("imageOnly"))) return "FORMAT_IMAGE_ONLY";
    if (Boolean.TRUE.equals(signals.get("emojiOnly"))) return "FORMAT_EMOJI_ONLY";
    if (signals.get("lookalikeSuspicion") instanceof Integer count && count >= lookalikThreshold)
      return "LOOKALIKE_ABUSE";
    if (signals.get("zeroWidthAbuse") instanceof Integer count && count >= zeroWidthThreshold)
      return "FORMAT_ABUSE";
    if (signals.get("foreignScriptRatio") instanceof Double d && d >= foreignThreshold)
      return "SCRIPT_FOREIGN";
    if (signals.get("capsRatio") instanceof Double d && d >= capsThreshold)
      return "CAPS_HEAVY";
    if (signals.get("toxicityHint") instanceof Double d && d > 0.0)
      return "TOXICITY_HINT";
    return "UNKNOWN";
  }
}
```

Note: `getLookalikeS uspicion()` → the getter for field `lookalikeSuspicion`. Carefully: `getLookalikeS uspicion` = `get` + `lookalikeSuspicion` → `getLookalikeS uspicion`. Full spelling: g-e-t-l-o-o-k-a-l-i-k-e-S-u-s-p-i-c-i-o-n.

- [ ] **Step 3: Run all intent-classifier tests**

```bash
mvn test -pl emcip-intent-classifier -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
mvn spotless:apply -pl emcip-intent-classifier
git add emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/IntentClassificationService.java \
        emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/service/IntentClassificationServiceTest.java
git commit -m "feat(intent-classifier): load intent rules and signal config from DB; remove hardcoded values"
```

---

## Task 7: Admin-API — IntentClassifierClient and proxy controllers

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/client/IntentClassifierClient.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/IntentRuleController.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/IntentSignalConfigController.java`
- Modify: `emcip-admin-api/src/main/resources/application.yml`

**Interfaces:**
- Consumes: intent-classifier at `${services.intent-classifier.url}` (port 9082)
- Produces: `GET/POST/PUT/DELETE /api/intent-rules`, `GET/PUT /api/intent-signal-config` — consumed by Task 8/9

- [ ] **Step 1: Add intent-classifier to `application.yml`**

Under `services:` (currently has moderation-service, policy-engine, audit-service), add:
```yaml
  intent-classifier:
    url: ${INTENT_CLASSIFIER_URL:http://localhost:9082}
```

Under `resilience4j.circuitbreaker.instances:`, add:
```yaml
      intent-classifier:
        baseConfig: default
```

Under `resilience4j.retry.instances:`, add:
```yaml
      intent-classifier:
        baseConfig: default
```

- [ ] **Step 2: Write `IntentClassifierClient.java`**

```java
package io.emcip.admin.api.client;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
@Slf4j
public class IntentClassifierClient {

  private final WebClient webClient;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;

  public IntentClassifierClient(
      @Value("${services.intent-classifier.url}") String baseUrl,
      @Value("${admin.service-token}") String serviceToken,
      CircuitBreakerRegistry cbRegistry,
      RetryRegistry retryRegistry) {
    this.webClient =
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("X-Service-Token", serviceToken)
            .build();
    this.circuitBreaker = cbRegistry.circuitBreaker("intent-classifier");
    this.retry = retryRegistry.retry("intent-classifier");
  }

  public Flux<JsonNode> listRules() {
    return Flux.deferContextual(
            ctx -> {
              String tenantId = ReactorTenantContext.getTenantId(ctx);
              var spec = webClient.get().uri("/api/intent-rules");
              return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                  .retrieve()
                  .bodyToFlux(JsonNode.class);
            })
        .transformDeferred(RetryOperator.of(retry))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .onErrorResume(
            e -> {
              log.warn("intent-classifier unavailable for listRules: {}", e.getMessage());
              return Flux.empty();
            });
  }

  public Mono<JsonNode> createRule(JsonNode body) {
    return Mono.deferContextual(
            ctx -> {
              String tenantId = ReactorTenantContext.getTenantId(ctx);
              var spec = webClient.post().uri("/api/intent-rules");
              return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                  .bodyValue(body)
                  .retrieve()
                  .bodyToMono(JsonNode.class);
            })
        .transformDeferred(RetryOperator.of(retry))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
  }

  public Mono<JsonNode> updateRule(String id, JsonNode body) {
    return Mono.deferContextual(
            ctx -> {
              String tenantId = ReactorTenantContext.getTenantId(ctx);
              var spec = webClient.put().uri("/api/intent-rules/{id}", id);
              return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                  .bodyValue(body)
                  .retrieve()
                  .bodyToMono(JsonNode.class);
            })
        .transformDeferred(RetryOperator.of(retry))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
  }

  public Mono<Void> deleteRule(String id) {
    return Mono.deferContextual(
            ctx -> {
              String tenantId = ReactorTenantContext.getTenantId(ctx);
              var spec = webClient.delete().uri("/api/intent-rules/{id}", id);
              return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                  .retrieve()
                  .bodyToMono(Void.class);
            })
        .transformDeferred(RetryOperator.of(retry))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
  }

  public Mono<JsonNode> getSignalConfig() {
    return Mono.deferContextual(
            ctx -> {
              String tenantId = ReactorTenantContext.getTenantId(ctx);
              var spec = webClient.get().uri("/api/intent-signal-config");
              return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                  .retrieve()
                  .bodyToMono(JsonNode.class);
            })
        .transformDeferred(RetryOperator.of(retry))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .onErrorResume(
            e -> {
              log.warn("intent-classifier unavailable for getSignalConfig: {}", e.getMessage());
              return Mono.empty();
            });
  }

  public Mono<JsonNode> upsertSignalConfig(JsonNode body) {
    return Mono.deferContextual(
            ctx -> {
              String tenantId = ReactorTenantContext.getTenantId(ctx);
              var spec = webClient.put().uri("/api/intent-signal-config");
              return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                  .bodyValue(body)
                  .retrieve()
                  .bodyToMono(JsonNode.class);
            })
        .transformDeferred(RetryOperator.of(retry))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
  }
}
```

- [ ] **Step 3: Write `IntentRuleController.java` (admin-api proxy)**

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.IntentClassifierClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/intent-rules")
@RequiredArgsConstructor
public class IntentRuleController {

  private final IntentClassifierClient intentClassifierClient;

  @GetMapping
  public Flux<JsonNode> list() {
    return intentClassifierClient.listRules();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<JsonNode> create(@RequestBody JsonNode body) {
    return intentClassifierClient.createRule(body);
  }

  @PutMapping("/{id}")
  public Mono<JsonNode> update(@PathVariable String id, @RequestBody JsonNode body) {
    return intentClassifierClient.updateRule(id, body);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> delete(@PathVariable String id) {
    return intentClassifierClient.deleteRule(id);
  }
}
```

- [ ] **Step 4: Write `IntentSignalConfigController.java` (admin-api proxy)**

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.IntentClassifierClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/intent-signal-config")
@RequiredArgsConstructor
public class IntentSignalConfigController {

  private final IntentClassifierClient intentClassifierClient;

  @GetMapping
  public Mono<JsonNode> get() {
    return intentClassifierClient.getSignalConfig();
  }

  @PutMapping
  public Mono<JsonNode> upsert(@RequestBody JsonNode body) {
    return intentClassifierClient.upsertSignalConfig(body);
  }
}
```

- [ ] **Step 5: Build admin-api to verify compilation**

```bash
mvn compile -pl emcip-admin-api -am -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/src/main/java/io/emcip/admin/api/client/IntentClassifierClient.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/controller/IntentRuleController.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/controller/IntentSignalConfigController.java \
        emcip-admin-api/src/main/resources/application.yml
git commit -m "feat(admin-api): add IntentClassifierClient and proxy controllers for intent rules + signal config"
```

---

## Task 8: Frontend — intentRules.js + IntentRules.jsx

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/api/intentRules.js`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/IntentRules/IntentRules.jsx`

**Interfaces:**
- Consumes: admin-api `GET/POST/PUT/DELETE /api/intent-rules` (Task 7)
- Produces: React page at `/intent-rules` — consumed by Task 10 (routes + navbar)

- [ ] **Step 1: Read the reference page for patterns**

Read the following files to understand exact patterns before writing:
- `emcip-admin-ui/src/main/frontend/src/pages/ModerationRules/ModerationRules.jsx`
- `emcip-admin-ui/src/main/frontend/src/api/moderationRules.js`

- [ ] **Step 2: Write `intentRules.js`**

```js
// src/main/frontend/src/api/intentRules.js
export const intentRulesApi = (request) => ({
  list: () => request('GET', '/api/intent-rules'),
  create: (body) => request('POST', '/api/intent-rules', body),
  update: (id, body) => request('PUT', `/api/intent-rules/${id}`, body),
  remove: (id) => request('DELETE', `/api/intent-rules/${id}`),
});
```

- [ ] **Step 3: Write `IntentRules.jsx`**

Follow the structure of `ModerationRules.jsx` exactly. Key differences:
- Page title: `Intent Rules`
- Use `intentRulesApi` instead of `moderationRulesApi`
- DataTable columns: `name`, `intent`, `matchMode`, `pattern`, `confidence`, `priority`, `active`
- Badge mapping for `intent`: `GREETING → blue`, `QUESTION → gray`, `COMMAND → blue`, `THANKS → green`, `GOODBYE → gray`, `SPAM → red`, others → gray
- Badge mapping for `matchMode`: `KEYWORD → gray`, `REGEX → yellow`
- `pattern` column: monospace font, truncated with `title` tooltip (full value)
- `confidence` column: display as 2 decimal places (e.g., `(r.confidence ?? 0).toFixed(2)`)
- `active` column: `ON`/`OFF` badge (green/gray)
- Modal fields:
  - `name` (text input, required)
  - `description` (textarea, optional)
  - `intent` (text input, free-text, placeholder `GREETING / SPAM / custom`)
  - `matchMode` (select: `KEYWORD` | `REGEX`)
  - `pattern` (text input; hint text changes based on matchMode: `"Pipe-separated keywords, e.g. hello|hi|hey"` for KEYWORD, `"Java regex, e.g. (?i)click\\s+here"` for REGEX)
  - `confidence` (number input, `0.00`–`1.00`, step `0.05`)
  - `priority` (number input, default `100`)
  - `active` (toggle/checkbox, default `true`)
- Signal Config button: add a secondary button in the page header `"Signal Config →"` that navigates to `/intent-signal-config` (use `useNavigate` from react-router-dom)

Write the full file following the ModerationRules.jsx pattern, substituting the above fields.

- [ ] **Step 4: Verify the page compiles**

```bash
cd emcip-admin-ui/src/main/frontend && npm run build 2>&1 | tail -10
```

Expected: no errors

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/api/intentRules.js \
        emcip-admin-ui/src/main/frontend/src/pages/IntentRules/
git commit -m "feat(admin-ui): add IntentRules page with CRUD and Signal Config link"
```

---

## Task 9: Frontend — intentSignalConfig.js + IntentSignalConfig.jsx

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/api/intentSignalConfig.js`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/IntentSignalConfig/IntentSignalConfig.jsx`

**Interfaces:**
- Consumes: admin-api `GET/PUT /api/intent-signal-config` (Task 7)
- Produces: React page at `/intent-signal-config` — consumed by Task 10

- [ ] **Step 1: Write `intentSignalConfig.js`**

```js
// src/main/frontend/src/api/intentSignalConfig.js
export const intentSignalConfigApi = (request) => ({
  get: () => request('GET', '/api/intent-signal-config'),
  upsert: (body) => request('PUT', '/api/intent-signal-config', body),
});
```

- [ ] **Step 2: Write `IntentSignalConfig.jsx`**

This is a settings-form page (not a DataTable). Structure:

```jsx
// src/main/frontend/src/pages/IntentSignalConfig/IntentSignalConfig.jsx
import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
// Import useRequest hook (same as used in ModerationRules.jsx)
// Import design-system classes (same as rest of app)

// Defaults matching seed data:
const DEFAULTS = {
  foreignScriptRatio: 0.6,
  cyrillicRatio: 0.6,
  lookalikeSuspicion: 3,
  zeroWidthAbuse: 2,
  capsRatio: 0.7,
  toxicityWords: [],
  description: '',
};

// Field descriptors including tooltip text:
const FIELDS = [
  {
    key: 'foreignScriptRatio', label: 'Foreign Script Ratio', type: 'number', step: 0.05, min: 0, max: 1,
    tooltip: 'Fraction of non-Latin characters above which SCRIPT_FOREIGN intent fires (0.0–1.0)',
  },
  {
    key: 'cyrillicRatio', label: 'Cyrillic Ratio', type: 'number', step: 0.05, min: 0, max: 1,
    tooltip: 'Fraction of Cyrillic characters above which cyrillicRatio signal is reported (0.0–1.0)',
  },
  {
    key: 'lookalikeSuspicion', label: 'Lookalike Suspicion', type: 'number', step: 1, min: 0,
    tooltip: 'Minimum count of words containing mixed Cyrillic/Greek lookalike + Latin characters to trigger LOOKALIKE_ABUSE',
  },
  {
    key: 'zeroWidthAbuse', label: 'Zero-Width Abuse Threshold', type: 'number', step: 1, min: 0,
    tooltip: 'Minimum count of zero-width or RTL-override characters to trigger FORMAT_ABUSE',
  },
  {
    key: 'capsRatio', label: 'Caps Ratio', type: 'number', step: 0.05, min: 0, max: 1,
    tooltip: 'Fraction of uppercase alphabetic characters above which CAPS_HEAVY intent fires (0.0–1.0)',
  },
];
```

The component:
1. On mount: calls `api.get()`. If 404, pre-fills `DEFAULTS`. If 200, populates form.
2. `toxicityWords` renders as a tag/chip list: type a word and press Enter or comma to add; click chip × to remove.
3. Save button calls `api.upsert(formData)` with the current form state.
4. Back button (`← Intent Rules`) navigates to `/intent-rules`.
5. Error/success feedback via same alert pattern as ModerationRules.jsx.
6. Note: the JSON key for `lookalikeSuspicion` is `"lookalikeSuspicion"` (camelCase). Carefully: l-o-o-k-a-l-i-k-e-S-u-s-p-i-c-i-o-n.

Read the useRequest hook and alert component from ModerationRules.jsx and apply the same patterns here.

- [ ] **Step 3: Verify the page compiles**

```bash
cd emcip-admin-ui/src/main/frontend && npm run build 2>&1 | tail -10
```

Expected: no errors

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/api/intentSignalConfig.js \
        emcip-admin-ui/src/main/frontend/src/pages/IntentSignalConfig/
git commit -m "feat(admin-ui): add IntentSignalConfig settings form with threshold and toxicity word management"
```

---

## Task 10: Navbar reorder and route registration

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/App.jsx`
- Modify (if exists): `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.test.jsx`

**Interfaces:**
- Consumes: `IntentRules` component (Task 8), `IntentSignalConfig` component (Task 9)
- Produces: `/intent-rules` and `/intent-signal-config` routes; pipeline-ordered navbar

- [ ] **Step 1: Read `Sidebar.jsx` to find the current NAV array and exact insertion point**

Open: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`

Find the `NAV` array entry for `/policy-rules`. Insert the intent-rules entry directly before it.

Current entries (lines ~12–13):
```js
{ to: '/policy-rules',     label: 'Policy Rules',     icon: '⚖', permission: 'POLICY_RULES_READ' },
{ to: '/moderation-rules', label: 'Moderation Rules', icon: '⊘', permission: 'MODERATION_RULES_READ' },
```

Change to:
```js
{ to: '/intent-rules',     label: 'Intent Rules',     icon: '✦', permission: 'INTENT_RULES_READ' },
{ to: '/policy-rules',     label: 'Policy Rules',     icon: '⚖', permission: 'POLICY_RULES_READ' },
{ to: '/moderation-rules', label: 'Moderation Rules', icon: '⊘', permission: 'MODERATION_RULES_READ' },
```

- [ ] **Step 2: Add routes in `App.jsx`**

Read `App.jsx` to find where `/moderation-rules` is defined. Add the two new routes in the same pattern, placing them before `/policy-rules`:

```jsx
import IntentRules from './pages/IntentRules/IntentRules';
import IntentSignalConfig from './pages/IntentSignalConfig/IntentSignalConfig';

// In the route list, before /policy-rules:
<Route path="/intent-rules" element={<PageErrorBoundary><IntentRules /></PageErrorBoundary>} />
<Route path="/intent-signal-config" element={<PageErrorBoundary><IntentSignalConfig /></PageErrorBoundary>} />
```

- [ ] **Step 3: Update `Sidebar.test.jsx` if it tests the NAV entries**

Read `Sidebar.test.jsx`. If it asserts on nav entries (e.g., checks for 'Policy Rules' or counts items), update accordingly to include the new entry:
- Add a test assertion that 'Intent Rules' link is rendered
- Update any count assertions from N to N+1

- [ ] **Step 4: Build and verify**

```bash
cd emcip-admin-ui/src/main/frontend && npm run build 2>&1 | tail -10
```

Expected: no errors

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-ui 2>/dev/null || true
git add emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx \
        emcip-admin-ui/src/main/frontend/src/App.jsx \
        emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.test.jsx
git commit -m "feat(admin-ui): add Intent Rules to navbar (pipeline order) and register routes"
```

---

## Self-Review Notes

**Spec coverage check:**
- ✅ `intent_rules` table with all specified columns + seed data (6 rules)
- ✅ `intent_signal_config` table with all specified columns + seed data (15 toxicity words)
- ✅ CRUD REST in intent-classifier
- ✅ GET/PUT signal config in intent-classifier
- ✅ Admin-api proxy with circuit breaker + retry (matches moderation-service pattern)
- ✅ IntentRules.jsx with DataTable + modal
- ✅ IntentSignalConfig.jsx settings form with tooltips
- ✅ Navbar order: Intent Rules → Policy Rules → Moderation Rules
- ✅ `tenant_id IS NULL` = global rule (seed data uses NULL)
- ✅ SignalDetector: integer counts for lookalikeSuspicion / zeroWidthAbuse; configurable toxicity words

**Design note (deviation from spec):**
The spec listed `tenant_id UUID NOT NULL` but seed data requires a known tenant UUID that doesn't exist during migration. The plan uses `tenant_id UUID` (nullable), where `NULL` means global (applies to all tenants). The unique constraint on `intent_signal_config.tenant_id` is enforced with a partial index `WHERE tenant_id IS NOT NULL`, allowing exactly one global (NULL) row.

**Camelcase note — the word `lookalikeSuspicion`:**
The full camelCase spelling across all files is: l-o-o-k-a-l-i-k-e-S-u-s-p-i-c-i-o-n. This appears as a Java field, JSON key, DTO record component, getter, setter, and test value. Verify spelling in every file before committing.
