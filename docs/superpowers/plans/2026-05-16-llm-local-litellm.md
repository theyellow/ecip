# LLM Local LiteLLM Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded Anthropic API client with an OpenAI-compatible client that talks to a locally-hosted LiteLLM proxy, with the proxy URL configurable at runtime via the Admin UI.

**Architecture:** A new `LlmProviderConfig` entity in `emcip-llm-orchestrator` stores the LiteLLM proxy URL and optional API key. `OpenAiCompatibleLlmClient` reads the active provider config at call time and posts to `/v1/chat/completions`. The Admin UI gains a "LLM Provider" tab with a connectivity test that calls `/v1/models` and populates model dropdowns.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, Liquibase, RestClient (Spring 6.1), tools.jackson (Jackson 3), React 18, existing `WebClient` proxy pattern in admin-api.

---

## Branch Setup

Before any task: create the feature branch.

```bash
git checkout main
git pull origin main
git checkout -b feat/llm-local-litellm
```

---

## File Map

### New files
| File | Responsibility |
|---|---|
| `emcip-llm-orchestrator/.../entity/LlmProviderConfig.java` | JPA entity for provider URL + api_key |
| `emcip-llm-orchestrator/.../repository/LlmProviderConfigRepository.java` | Spring Data repo |
| `emcip-llm-orchestrator/.../service/LlmProviderConfigService.java` | CRUD + active-one-at-a-time logic + model list fetch |
| `emcip-llm-orchestrator/.../client/OpenAiCompatibleLlmClient.java` | OpenAI-format HTTP client |
| `emcip-llm-orchestrator/.../db/changelog/changes/007-create-llm-provider-config.xml` | Table DDL |
| `emcip-llm-orchestrator/.../db/changelog/changes/008-seed-litellm-provider.xml` | Seed inactive placeholder row |
| `emcip-llm-orchestrator/.../db/changelog/changes/009-update-model-seeds-for-litellm.xml` | Update existing seeds + add MODERATION entry |
| `emcip-admin-ui/.../api/providerConfig.js` | Frontend API client |
| `docs/superpowers/specs/2026-05-16-llm-local-litellm-design.md` | Design spec |

### Modified files
| File | Change |
|---|---|
| `emcip-llm-orchestrator/.../client/AnthropicLlmClient.java` | Delete |
| `emcip-llm-orchestrator/.../service/LlmCallService.java` | Swap `AnthropicLlmClient` → `OpenAiCompatibleLlmClient` |
| `emcip-llm-orchestrator/.../controller/OrchestratorController.java` | Add 3 provider-config endpoints |
| `emcip-llm-orchestrator/.../resources/application.yml` | Remove `anthropic.api-key` |
| `emcip-llm-orchestrator/.../db/changelog/db.changelog-master.xml` | Include 007, 008, 009 |
| `emcip-admin-api/.../controller/AIProxyController.java` | Add 3 proxy endpoints |
| `emcip-admin-ui/.../pages/AIConfig/AIConfig.jsx` | Add Provider tab + model picker |

---

## Task 1: Write the design spec

**Files:**
- Create: `docs/superpowers/specs/2026-05-16-llm-local-litellm-design.md`

- [ ] **Step 1: Create spec file**

```bash
cat > docs/superpowers/specs/2026-05-16-llm-local-litellm-design.md << 'EOF'
# LLM Local LiteLLM Integration — Design Spec

**Date:** 2026-05-16

## Problem

`emcip-llm-orchestrator` is hardcoded to Anthropic's `api.anthropic.com`. The project moves to
local LLMs (Qwen3) served via a LiteLLM proxy (OpenAI-compatible). The proxy URL must be
configurable at runtime via Admin UI — no restart required.

## AI Placement

| Component | Technology | Task type |
|---|---|---|
| Response generation | Qwen3-30B-A3B via LiteLLM | `response` |
| Escalation summary | Qwen3-30B-A3B via LiteLLM | `summary` |
| Command validation | Qwen3-14B via LiteLLM | `command_validation` |
| Moderation / toxicity | Qwen3-14B via LiteLLM | `MODERATION` |
| Intent classification | Rule-based — unchanged | — |
| Policy decision | Rule-based — unchanged | — |

## Design

### `LlmProviderConfig` entity (orchestrator-owned)

Table `llm_provider_configs`. Fields: id (UUID), name, base_url, api_key (nullable),
active (bool), created_at, updated_at, version_lock. Only one active=true row at a time,
enforced in service layer.

### `OpenAiCompatibleLlmClient`

POSTs to `{base_url}/v1/chat/completions`. Messages format: system + user roles.
Response: `choices[0].message.content`, `usage.prompt_tokens`, `usage.completion_tokens`.
Reads active provider config at call time from `LlmProviderConfigService`.

### REST API (orchestrator)

- GET  /api/provider-config          → list all configs (api_key masked)
- POST /api/provider-config          → create new config
- PUT  /api/provider-config/{id}     → update existing config (name, url, key, active flag)
- DELETE /api/provider-config/{id}   → delete config
- GET  /api/provider-config/models   → ping active provider /v1/models, return model id list

### Admin API proxy

Same WebClient proxy pattern as existing /api/ai/models — five new endpoints in AIProxyController.

### Frontend

New "LLM Provider" tab in AIConfig.jsx: table listing all configs with name, URL, active badge,
Edit/Delete/Test buttons per row. "Add Provider" button opens a modal (name, base_url, api_key,
active checkbox). "Test" button on a row calls /api/ai/provider-config/models → status badge + model list.
ModelModal gains "Pick from proxy" button populating modelName from proxy model list.
EOF
```

- [ ] **Step 2: Commit spec**

```bash
git add docs/superpowers/specs/2026-05-16-llm-local-litellm-design.md
git commit -m "docs: add LiteLLM integration design spec"
```

---

## Task 2: Liquibase — create `llm_provider_configs` table

**Files:**
- Create: `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/007-create-llm-provider-config.xml`
- Create: `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/008-seed-litellm-provider.xml`
- Create: `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/009-update-model-seeds-for-litellm.xml`
- Modify: `emcip-llm-orchestrator/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Create changeset 007 — table DDL**

```xml
<!-- emcip-llm-orchestrator/src/main/resources/db/changelog/changes/007-create-llm-provider-config.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">

    <changeSet id="007-create-llm-provider-config" author="ecip">
        <createTable tableName="llm_provider_configs">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="name" type="VARCHAR(255)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="base_url" type="VARCHAR(512)">
                <constraints nullable="false"/>
            </column>
            <column name="api_key" type="VARCHAR(512)"/>
            <column name="active" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="version_lock" type="BIGINT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Create changeset 008 — seed placeholder row**

```xml
<!-- emcip-llm-orchestrator/src/main/resources/db/changelog/changes/008-seed-litellm-provider.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">

    <changeSet id="008-seed-litellm-provider" author="ecip">
        <insert tableName="llm_provider_configs">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="local-litellm"/>
            <column name="base_url" value="http://localhost:4000"/>
            <column name="api_key" value=""/>
            <column name="active" valueBoolean="false"/>
            <column name="created_at" valueComputed="now()"/>
            <column name="updated_at" valueComputed="now()"/>
            <column name="version_lock" valueNumeric="0"/>
        </insert>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Create changeset 009 — update seeds + add MODERATION entry**

The existing Anthropic seeds are updated to use LiteLLM model names. The old `model_key` values are kept for backwards-compatible routing; only `provider` and `model_name` change. A new MODERATION entry is added.

```xml
<!-- emcip-llm-orchestrator/src/main/resources/db/changelog/changes/009-update-model-seeds-for-litellm.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">

    <!-- Update response model: Qwen3-30B-A3B for quality tasks -->
    <changeSet id="009-update-model-response-to-litellm" author="ecip">
        <update tableName="model_configs">
            <column name="provider" value="litellm"/>
            <column name="model_name" value="qwen3-30b-a3b"/>
            <column name="description" value="Qwen3-30B-A3B via LiteLLM for automated response generation"/>
            <column name="updated_at" valueComputed="now()"/>
        </update>
        <where>model_key = 'claude-haiku-response'</where>
    </changeSet>

    <!-- Update summary model: Qwen3-30B-A3B for quality tasks -->
    <changeSet id="009-update-model-summary-to-litellm" author="ecip">
        <update tableName="model_configs">
            <column name="provider" value="litellm"/>
            <column name="model_name" value="qwen3-30b-a3b"/>
            <column name="description" value="Qwen3-30B-A3B via LiteLLM for escalation summaries"/>
            <column name="updated_at" valueComputed="now()"/>
        </update>
        <where>model_key = 'claude-haiku-summary'</where>
    </changeSet>

    <!-- Update command_validation model: Qwen3-14B for speed tasks -->
    <changeSet id="009-update-model-command-to-litellm" author="ecip">
        <update tableName="model_configs">
            <column name="provider" value="litellm"/>
            <column name="model_name" value="qwen3-14b"/>
            <column name="description" value="Qwen3-14B via LiteLLM for command validation"/>
            <column name="updated_at" valueComputed="now()"/>
        </update>
        <where>model_key = 'claude-haiku-command'</where>
    </changeSet>

    <!-- Add new MODERATION task type entry -->
    <changeSet id="009-seed-model-moderation" author="ecip">
        <insert tableName="model_configs">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="model_key" value="qwen3-14b-moderation"/>
            <column name="provider" value="litellm"/>
            <column name="model_name" value="qwen3-14b"/>
            <column name="description" value="Qwen3-14B via LiteLLM for content moderation and toxicity detection"/>
            <column name="task_type" value="MODERATION"/>
            <column name="input_cost_per_1k_tokens" valueNumeric="0.0"/>
            <column name="output_cost_per_1k_tokens" valueNumeric="0.0"/>
            <column name="context_window" valueNumeric="40000"/>
            <column name="max_output_tokens" valueNumeric="256"/>
            <column name="avg_latency_ms" valueNumeric="400.0"/>
            <column name="supports_streaming" valueBoolean="false"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
            <column name="created_at" valueComputed="now()"/>
            <column name="updated_at" valueComputed="now()"/>
            <column name="version_lock" valueNumeric="0"/>
        </insert>
    </changeSet>

    <!-- Add seed prompt template for moderation -->
    <changeSet id="009-seed-prompt-template-moderation" author="ecip">
        <insert tableName="prompt_templates">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="moderation_check"/>
            <column name="version" value="1.0.0"/>
            <column name="description" value="Toxicity and content moderation check (German + English)"/>
            <column name="model_provider" value="litellm"/>
            <column name="model_name" value="qwen3-14b"/>
            <column name="system_prompt" value="Du bist ein Inhaltsmoderationsassistent. Bewerte die folgende Nachricht auf Toxizität, Beleidigungen, Hassrede oder unangemessene Inhalte. Antworte ausschließlich mit JSON in diesem Format: {&quot;verdict&quot;: &quot;SAFE&quot; | &quot;FLAGGED&quot;, &quot;reason&quot;: &quot;kurze Begründung auf Deutsch&quot;, &quot;score&quot;: 0.0-1.0}"/>
            <column name="user_prompt_template" value="Prüfe diese Nachricht: {{content}}"/>
            <column name="temperature" valueNumeric="0.1"/>
            <column name="max_tokens" valueNumeric="128"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
            <column name="created_at" valueComputed="now()"/>
            <column name="updated_at" valueComputed="now()"/>
            <column name="version_lock" valueNumeric="0"/>
        </insert>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 4: Update changelog master**

Edit `emcip-llm-orchestrator/src/main/resources/db/changelog/db.changelog-master.xml` — add three includes after the existing six:

```xml
    <include file="classpath:db/changelog/changes/007-create-llm-provider-config.xml"/>
    <include file="classpath:db/changelog/changes/008-seed-litellm-provider.xml"/>
    <include file="classpath:db/changelog/changes/009-update-model-seeds-for-litellm.xml"/>
```

The full file should look like:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">

    <include file="classpath:db/changelog/changes/001-create-prompt-templates-table.xml"/>
    <include file="classpath:db/changelog/changes/002-create-model-cost-logs-table.xml"/>
    <include file="classpath:db/changelog/changes/003-create-model-configs-table.xml"/>
    <include file="classpath:db/changelog/changes/004-seed-initial-model-configs.xml"/>
    <include file="classpath:db/changelog/changes/005-seed-sonnet-model-configs.xml"/>
    <include file="classpath:db/changelog/changes/006-add-tenant-id.xml"/>
    <include file="classpath:db/changelog/changes/007-create-llm-provider-config.xml"/>
    <include file="classpath:db/changelog/changes/008-seed-litellm-provider.xml"/>
    <include file="classpath:db/changelog/changes/009-update-model-seeds-for-litellm.xml"/>

</databaseChangeLog>
```

- [ ] **Step 5: Commit**

```bash
git add emcip-llm-orchestrator/src/main/resources/db/
git commit -m "feat(llm): add Liquibase migrations for LlmProviderConfig table and LiteLLM seed data"
```

---

## Task 3: `LlmProviderConfig` entity + repository

**Files:**
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/LlmProviderConfig.java`
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/repository/LlmProviderConfigRepository.java`

- [ ] **Step 1: Write failing test**

Create `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/repository/LlmProviderConfigRepositoryTest.java`:

```java
package io.emcip.llm.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(
        properties = {
            "spring.liquibase.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class LlmProviderConfigRepositoryTest {

    @Autowired LlmProviderConfigRepository repository;

    @Test
    void findFirstByActiveTrueOrderByUpdatedAtDesc_returnsActiveConfig() {
        LlmProviderConfig config =
                LlmProviderConfig.builder()
                        .name("test-provider")
                        .baseUrl("http://localhost:4000")
                        .active(true)
                        .build();
        repository.save(config);

        Optional<LlmProviderConfig> found =
                repository.findFirstByActiveTrueOrderByUpdatedAtDesc();

        assertThat(found).isPresent();
        assertThat(found.get().getBaseUrl()).isEqualTo("http://localhost:4000");
    }

    @Test
    void findFirstByActiveTrueOrderByUpdatedAtDesc_emptyWhenNoneActive() {
        LlmProviderConfig config =
                LlmProviderConfig.builder()
                        .name("inactive")
                        .baseUrl("http://localhost:4000")
                        .active(false)
                        .build();
        repository.save(config);

        Optional<LlmProviderConfig> found =
                repository.findFirstByActiveTrueOrderByUpdatedAtDesc();

        assertThat(found).isEmpty();
    }
}
```

- [ ] **Step 2: Run test — expect compile failure (entity not yet defined)**

```bash
mvn test -pl emcip-llm-orchestrator -Dtest=LlmProviderConfigRepositoryTest 2>&1 | tail -20 | cat
```

Expected: compilation error — `LlmProviderConfig` not found.

- [ ] **Step 3: Create entity**

```java
// emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/LlmProviderConfig.java
package io.emcip.llm.orchestrator.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** JPA entity for LLM provider configuration. Stores LiteLLM proxy URL and credentials. */
@Entity
@Table(name = "llm_provider_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String name;

    @Column(nullable = false, length = 512)
    private String baseUrl;

    @Column(length = 512)
    private String apiKey;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long versionLock;
}
```

- [ ] **Step 4: Create repository**

```java
// emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/repository/LlmProviderConfigRepository.java
package io.emcip.llm.orchestrator.repository;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for LlmProviderConfig entity. */
@Repository
public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfig, UUID> {

    /** Returns the most-recently-updated active provider config. */
    Optional<LlmProviderConfig> findFirstByActiveTrueOrderByUpdatedAtDesc();

    /** Returns all configs — used for deactivating all before activating one. */
    List<LlmProviderConfig> findAll();
}
```

- [ ] **Step 5: Run tests — expect pass**

```bash
mvn test -pl emcip-llm-orchestrator -Dtest=LlmProviderConfigRepositoryTest 2>&1 | tail -20 | cat
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/LlmProviderConfig.java \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/repository/LlmProviderConfigRepository.java \
        emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/repository/LlmProviderConfigRepositoryTest.java
git commit -m "feat(llm): add LlmProviderConfig entity and repository"
```

---

## Task 4: `LlmProviderConfigService`

**Files:**
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmProviderConfigService.java`

- [ ] **Step 1: Write failing test**

Create `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/LlmProviderConfigServiceTest.java`:

```java
package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class LlmProviderConfigServiceTest {

    @Mock LlmProviderConfigRepository repository;
    @Mock ObjectMapper objectMapper;
    @InjectMocks LlmProviderConfigService service;

    @Test
    void getActiveProvider_delegatesToRepository() {
        LlmProviderConfig config =
                LlmProviderConfig.builder().name("test").baseUrl("http://localhost:4000").active(true).build();
        when(repository.findFirstByActiveTrueOrderByUpdatedAtDesc()).thenReturn(Optional.of(config));

        Optional<LlmProviderConfig> result = service.getActiveProvider();

        assertThat(result).isPresent();
        assertThat(result.get().getBaseUrl()).isEqualTo("http://localhost:4000");
    }

    @Test
    void saveProvider_deactivatesOthersBeforeSavingActiveConfig() {
        LlmProviderConfig existing =
                LlmProviderConfig.builder().name("old").baseUrl("http://old:4000").active(true).build();
        when(repository.findAll()).thenReturn(List.of(existing));
        LlmProviderConfig incoming =
                LlmProviderConfig.builder().name("new").baseUrl("http://new:4000").active(true).build();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveProvider(incoming);

        // existing was deactivated and saved, then incoming was saved
        verify(repository, times(2)).save(any());
        assertThat(existing.getActive()).isFalse();
    }

    @Test
    void saveProvider_doesNotDeactivateOthersWhenSavingInactiveConfig() {
        LlmProviderConfig existing =
                LlmProviderConfig.builder().name("old").baseUrl("http://old:4000").active(true).build();
        when(repository.findAll()).thenReturn(List.of(existing));
        LlmProviderConfig incoming =
                LlmProviderConfig.builder().name("new").baseUrl("http://new:4000").active(false).build();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveProvider(incoming);

        // only incoming was saved — existing untouched
        verify(repository, times(1)).save(any());
        assertThat(existing.getActive()).isTrue();
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

```bash
mvn test -pl emcip-llm-orchestrator -Dtest=LlmProviderConfigServiceTest 2>&1 | tail -20 | cat
```

Expected: compilation error — `LlmProviderConfigService` not found.

- [ ] **Step 3: Create service**

```java
// emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmProviderConfigService.java
package io.emcip.llm.orchestrator.service;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

/** Manages LLM provider configuration and connectivity checks. */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmProviderConfigService {

    private final LlmProviderConfigRepository repository;
    private final ObjectMapper objectMapper;

    public Optional<LlmProviderConfig> getActiveProvider() {
        return repository.findFirstByActiveTrueOrderByUpdatedAtDesc();
    }

    @Transactional
    public LlmProviderConfig saveProvider(LlmProviderConfig config) {
        if (Boolean.TRUE.equals(config.getActive())) {
            List<LlmProviderConfig> all = repository.findAll();
            for (LlmProviderConfig existing : all) {
                if (Boolean.TRUE.equals(existing.getActive())) {
                    existing.setActive(false);
                    repository.save(existing);
                }
            }
        }
        return repository.save(config);
    }

    /**
     * Calls GET {baseUrl}/v1/models on the given URL and returns the list of model IDs.
     * Returns empty list if the endpoint is unreachable.
     */
    public List<String> fetchAvailableModels(String baseUrl, String apiKey) {
        try {
            RestClient restClient = RestClient.create();
            String responseJson = restClient
                    .get()
                    .uri(baseUrl + "/v1/models")
                    .headers(h -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode data = root.path("data");
            return data.isArray()
                    ? data.findValuesAsText("id")
                    : List.of();
        } catch (Exception e) {
            log.warn("Failed to fetch models from {}: {}", baseUrl, e.getMessage());
            return List.of();
        }
    }
}
```

- [ ] **Step 4: Run tests — expect pass**

```bash
mvn test -pl emcip-llm-orchestrator -Dtest=LlmProviderConfigServiceTest 2>&1 | tail -20 | cat
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmProviderConfigService.java \
        emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/LlmProviderConfigServiceTest.java
git commit -m "feat(llm): add LlmProviderConfigService with active-one-at-a-time logic"
```

---

## Task 5: `OpenAiCompatibleLlmClient` (replaces `AnthropicLlmClient`)

**Files:**
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClient.java`
- Delete: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/AnthropicLlmClient.java`

- [ ] **Step 1: Write failing test**

Create `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClientTest.java`:

```java
package io.emcip.llm.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleLlmClientTest {

    @Mock LlmProviderConfigService providerConfigService;

    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAiCompatibleLlmClient(providerConfigService, new ObjectMapper());
    }

    @Test
    void call_throwsWhenNoActiveProviderConfigured() {
        when(providerConfigService.getActiveProvider()).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> client.call("qwen3-30b-a3b", "You are helpful.", "Hello", 256, 0.7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active LLM provider");
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

```bash
mvn test -pl emcip-llm-orchestrator -Dtest=OpenAiCompatibleLlmClientTest 2>&1 | tail -20 | cat
```

Expected: compilation error — `OpenAiCompatibleLlmClient` not found.

- [ ] **Step 3: Create `OpenAiCompatibleLlmClient`**

```java
// emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClient.java
package io.emcip.llm.orchestrator.client;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for any OpenAI-compatible LLM API (e.g. LiteLLM proxy).
 * Calls POST /v1/chat/completions with system + user messages.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient {

    private final LlmProviderConfigService providerConfigService;
    private final ObjectMapper objectMapper;

    /**
     * Call the OpenAI-compatible chat completions endpoint.
     *
     * @param model Model name as configured in LiteLLM (e.g. "qwen3-30b-a3b")
     * @param systemPrompt System instructions
     * @param userContent User message
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0–2.0)
     * @return LlmResponse with content and token counts
     */
    public LlmResponse call(
            String model,
            String systemPrompt,
            String userContent,
            int maxTokens,
            double temperature) {

        LlmProviderConfig provider =
                providerConfigService
                        .getActiveProvider()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No active LLM provider configured — set one via"
                                                        + " Admin UI > AI Config > LLM Provider"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)));

        log.debug(
                "Calling LiteLLM: url={}, model={}, maxTokens={}",
                provider.getBaseUrl(),
                model,
                maxTokens);

        try {
            String apiKey = provider.getApiKey();
            RestClient restClient = RestClient.create();
            String responseJson = restClient
                    .post()
                    .uri(provider.getBaseUrl() + "/v1/chat/completions")
                    .headers(h -> {
                        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                        if (apiKey != null && !apiKey.isBlank()) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            String content =
                    root.path("choices").get(0).path("message").path("content").asText();
            int inputTokens = root.path("usage").path("prompt_tokens").asInt();
            int outputTokens = root.path("usage").path("completion_tokens").asInt();
            String modelUsed = root.path("model").asText(model);

            log.debug(
                    "LiteLLM response: model={}, input_tokens={}, output_tokens={}",
                    modelUsed,
                    inputTokens,
                    outputTokens);

            return new LlmResponse(content, inputTokens, outputTokens, modelUsed);

        } catch (Exception e) {
            throw new RuntimeException(
                    "LiteLLM API call failed [" + provider.getBaseUrl() + "]: " + e.getMessage(),
                    e);
        }
    }
}
```

- [ ] **Step 4: Run tests — expect pass**

```bash
mvn test -pl emcip-llm-orchestrator -Dtest=OpenAiCompatibleLlmClientTest 2>&1 | tail -20 | cat
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5: Delete `AnthropicLlmClient`**

```bash
git rm emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/AnthropicLlmClient.java
```

- [ ] **Step 6: Commit**

```bash
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClient.java \
        emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClientTest.java
git commit -m "feat(llm): replace AnthropicLlmClient with OpenAiCompatibleLlmClient for LiteLLM proxy"
```

---

## Task 6: Wire `OpenAiCompatibleLlmClient` into `LlmCallService`

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmCallService.java`
- Modify: `emcip-llm-orchestrator/src/main/resources/application.yml`

- [ ] **Step 1: Update `LlmCallService`**

Replace the `AnthropicLlmClient` import and field with `OpenAiCompatibleLlmClient`. The call site is identical — same `call(model, systemPrompt, userContent, maxTokens, temperature)` signature.

Change line 4 (import):
```java
// Before:
import io.emcip.llm.orchestrator.client.AnthropicLlmClient;
// After:
import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
```

Change line 25 (field):
```java
// Before:
    private final AnthropicLlmClient anthropicClient;
// After:
    private final OpenAiCompatibleLlmClient llmClient;
```

Change line 103 (call site):
```java
// Before:
            LlmResponse response =
                    anthropicClient.call(
// After:
            LlmResponse response =
                    llmClient.call(
```

- [ ] **Step 2: Remove `anthropic.api-key` from `application.yml`**

Delete lines 35–36:
```yaml
# Remove these two lines:
anthropic:
  api-key: ${ANTHROPIC_API_KEY:}
```

- [ ] **Step 3: Run all orchestrator tests**

```bash
mvn test -pl emcip-llm-orchestrator 2>&1 | tail -30 | cat
```

Expected: no compilation errors, all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmCallService.java \
        emcip-llm-orchestrator/src/main/resources/application.yml
git commit -m "feat(llm): wire OpenAiCompatibleLlmClient into LlmCallService, remove Anthropic config"
```

---

## Task 7: REST endpoints for provider config in `OrchestratorController`

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`

- [ ] **Step 1: Add imports and inject `LlmProviderConfigService`**

Add to imports:
```java
import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.Map;
```

Add field to class (Lombok `@RequiredArgsConstructor` handles injection):
```java
    private final LlmProviderConfigService providerConfigService;
```

- [ ] **Step 2: Add five provider-config endpoints**

Add after the `// --- Costs ---` section (before the closing `}`). Also add `UUID` import and
`LlmProviderConfigRepository` injection if not already present — but prefer using the service for
all operations to keep controller thin.

Also add to imports:
```java
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
```

Add field:
```java
    private final LlmProviderConfigRepository providerConfigRepository;
```

Add endpoints:

```java
    // --- Provider Config ---

    private Map<String, Object> maskConfig(LlmProviderConfig p) {
        return Map.of(
                "id", p.getId().toString(),
                "name", p.getName(),
                "baseUrl", p.getBaseUrl(),
                "apiKey", p.getApiKey() != null && !p.getApiKey().isBlank() ? "***" : "",
                "active", p.getActive());
    }

    @Operation(summary = "List all LLM provider configurations (api_key masked)")
    @GetMapping("/provider-config")
    public List<Map<String, Object>> listProviderConfigs() {
        return providerConfigRepository.findAll().stream()
                .map(this::maskConfig)
                .toList();
    }

    @Operation(summary = "Create a new LLM provider configuration")
    @PostMapping("/provider-config")
    public ResponseEntity<Map<String, Object>> createProviderConfig(
            @RequestBody LlmProviderConfig config) {
        LlmProviderConfig saved = providerConfigService.saveProvider(config);
        log.info("Created provider config: name={}, active={}", saved.getName(), saved.getActive());
        return ResponseEntity.status(201).body(maskConfig(saved));
    }

    @Operation(summary = "Update an existing LLM provider configuration")
    @PutMapping("/provider-config/{id}")
    public ResponseEntity<Map<String, Object>> updateProviderConfig(
            @PathVariable UUID id, @RequestBody LlmProviderConfig update) {
        LlmProviderConfig existing =
                providerConfigRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Provider config not found: " + id));
        existing.setName(update.getName());
        existing.setBaseUrl(update.getBaseUrl());
        if (update.getApiKey() != null && !update.getApiKey().isBlank()
                && !"***".equals(update.getApiKey())) {
            existing.setApiKey(update.getApiKey());
        }
        existing.setActive(update.getActive());
        LlmProviderConfig saved = providerConfigService.saveProvider(existing);
        log.info("Updated provider config: id={}, active={}", id, saved.getActive());
        return ResponseEntity.ok(maskConfig(saved));
    }

    @Operation(summary = "Delete a LLM provider configuration")
    @DeleteMapping("/provider-config/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProviderConfig(@PathVariable UUID id) {
        if (!providerConfigRepository.existsById(id)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Provider config not found: " + id);
        }
        providerConfigRepository.deleteById(id);
    }

    @Operation(summary = "List models available on the active LLM provider proxy")
    @GetMapping("/provider-config/models")
    public ResponseEntity<Map<String, Object>> listProxyModels() {
        return providerConfigService
                .getActiveProvider()
                .map(
                        p -> {
                            java.util.List<String> models =
                                    providerConfigService.fetchAvailableModels(
                                            p.getBaseUrl(), p.getApiKey());
                            return ResponseEntity.ok(
                                    Map.<String, Object>of(
                                            "baseUrl", p.getBaseUrl(),
                                            "models", models,
                                            "reachable", !models.isEmpty()));
                        })
                .orElse(ResponseEntity.notFound().build());
    }
```

- [ ] **Step 3: Run orchestrator tests**

```bash
mvn test -pl emcip-llm-orchestrator 2>&1 | tail -20 | cat
```

Expected: all tests pass, no compilation errors.

- [ ] **Step 4: Apply Spotless**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator 2>&1 | tail -5 | cat
```

Expected: `0 were changed to be clean` (or files fixed — if fixed, check diff is clean).

- [ ] **Step 5: Commit**

```bash
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java
git commit -m "feat(llm): add provider-config REST endpoints to OrchestratorController"
```

---

## Task 8: Admin API proxy — five new endpoints in `AIProxyController`

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java`

- [ ] **Step 1: Add five proxy endpoints**

Add after the last `deleteTemplate` method (before the closing `}`):

```java
    // ---- Provider Config ----

    @GetMapping("/provider-config")
    public Mono<String> listProviderConfigs() {
        return orchestratorClient
                .get()
                .uri("/api/provider-config")
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                body ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(), body))))
                .bodyToMono(String.class);
    }

    @PostMapping(value = "/provider-config", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<String> createProviderConfig(@RequestBody String body) {
        return orchestratorClient
                .post()
                .uri("/api/provider-config")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(String.class);
    }

    @PutMapping(value = "/provider-config/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> updateProviderConfig(@PathVariable String id, @RequestBody String body) {
        return orchestratorClient
                .put()
                .uri("/api/provider-config/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(String.class);
    }

    @DeleteMapping("/provider-config/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteProviderConfig(@PathVariable String id) {
        return orchestratorClient
                .delete()
                .uri("/api/provider-config/{id}", id)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(Void.class);
    }

    @GetMapping("/provider-config/models")
    public Mono<String> listProxyModels() {
        return orchestratorClient
                .get()
                .uri("/api/provider-config/models")
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                body ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(), body))))
                .bodyToMono(String.class);
    }
```

- [ ] **Step 2: Run admin-api tests**

```bash
mvn test -pl emcip-admin-api 2>&1 | tail -20 | cat
```

Expected: all tests pass.

- [ ] **Step 3: Apply Spotless**

```bash
mvn spotless:apply -pl emcip-admin-api 2>&1 | tail -5 | cat
```

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java
git commit -m "feat(admin-api): proxy provider-config CRUD and models endpoints to llm-orchestrator"
```

---

## Task 9: Frontend — `providerConfig.js` API client

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/api/providerConfig.js`

- [ ] **Step 1: Create API client**

```javascript
// emcip-admin-ui/src/main/frontend/src/api/providerConfig.js

export function providerConfigApi(request) {
  return {
    listProviderConfigs: () =>
      request('GET', '/api/ai/provider-config'),

    createProviderConfig: (form) =>
      request('POST', '/api/ai/provider-config', form),

    updateProviderConfig: (id, form) =>
      request('PUT', `/api/ai/provider-config/${id}`, form),

    deleteProviderConfig: (id) =>
      request('DELETE', `/api/ai/provider-config/${id}`),

    getProxyModels: () =>
      request('GET', '/api/ai/provider-config/models'),
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/api/providerConfig.js
git commit -m "feat(ui): add providerConfig API client"
```

---

## Task 10: Frontend — "LLM Provider" tab + model picker in `AIConfig.jsx`

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.jsx`

- [ ] **Step 1: Add import for `providerConfigApi` and tab state**

At the top of `AIConfig.jsx`, add the import after the existing `aiConfigApi` import:

```javascript
import { providerConfigApi } from '../../api/providerConfig'
```

- [ ] **Step 2: Add `ProviderModal` and `ProviderConfigSection` components**

Add these components before the `AIConfig` function:

```javascript
function ProviderModal({ provider, onClose, onSave }) {
  const [form, setForm] = useState({
    name: provider?.name ?? '',
    baseUrl: provider?.baseUrl ?? '',
    apiKey: '',  // never pre-fill — server returns "***"
    active: provider?.active ?? false,
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={provider ? 'Edit Provider' : 'Add Provider'} onClose={onClose} onSubmit={() => onSave(form)}>
      <label>Name *</label>
      <input type="text" className={styles.input} value={form.name}
        onChange={e => set('name', e.target.value)} placeholder="local-litellm" required />
      <label>Base URL *</label>
      <input type="text" className={styles.input} value={form.baseUrl}
        onChange={e => set('baseUrl', e.target.value)} placeholder="http://192.168.1.50:4000" required />
      <label>API Key (optional — leave blank to keep existing)</label>
      <input type="password" className={styles.input} value={form.apiKey}
        onChange={e => set('apiKey', e.target.value)} placeholder="Leave blank if not required" />
      <label>
        <input type="checkbox" checked={form.active}
          onChange={e => set('active', e.target.checked)} /> Active
      </label>
    </Modal>
  )
}

function ProviderConfigSection({ token }) {
  const api = providerConfigApi(makeRequest(token))
  const [providers, setProviders] = useState([])
  const [modal, setModal] = useState(null) // null | 'add' | provider object
  const [status, setStatus] = useState(null) // null | { ok: boolean, models: string[] }
  const [error, setError] = useState('')

  const load = () =>
    api.listProviderConfigs().then(setProviders).catch(e => setError(e.message))

  useEffect(() => { load() }, [])

  const save = async form => {
    try {
      if (modal === 'add') await api.createProviderConfig(form)
      else await api.updateProviderConfig(modal.id, form)
      setModal(null)
      load()
    } catch (e) { setError(e.message) }
  }

  const remove = async p => {
    if (!confirm(`Delete provider "${p.name}"?`)) return
    try { await api.deleteProviderConfig(p.id); load() }
    catch (e) { setError(e.message) }
  }

  const testConnection = async () => {
    setStatus(null)
    try {
      const data = await api.getProxyModels()
      setStatus({ ok: data.reachable, models: data.models ?? [] })
    } catch {
      setStatus({ ok: false, models: [] })
    }
  }

  return (
    <div className={styles.section}>
      <div className={styles.sectionHeader}>
        <h3 className={styles.sectionTitle}>LLM Provider</h3>
        <Button onClick={() => setModal('add')}>+ Add Provider</Button>
      </div>
      {error && <p className={styles.error} role="alert">{error}</p>}
      <table className={styles.table}>
        <thead>
          <tr><th>Name</th><th>Base URL</th><th>Active</th><th></th></tr>
        </thead>
        <tbody>
          {providers.map(p => (
            <tr key={p.id}>
              <td className={styles.mono}>{p.name}</td>
              <td>{p.baseUrl}</td>
              <td><Badge variant={p.active ? 'green' : 'red'}>{p.active ? 'Yes' : 'No'}</Badge></td>
              <td className={styles.actions}>
                <Button variant="secondary" onClick={() => setModal(p)}>Edit</Button>
                {p.active && (
                  <Button variant="secondary" onClick={testConnection}>Test</Button>
                )}
                <Button variant="danger" onClick={() => remove(p)}>Delete</Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {status && (
        <div className={styles.connectionStatus}>
          <Badge variant={status.ok ? 'green' : 'red'}>
            {status.ok ? 'Reachable' : 'Unreachable'}
          </Badge>
          {status.ok && status.models.length > 0 && (
            <ul className={styles.modelList}>
              {status.models.map(m => <li key={m} className={styles.mono}>{m}</li>)}
            </ul>
          )}
        </div>
      )}
      {modal && (
        <ProviderModal
          provider={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
        />
      )}
    </div>
  )
}
```

- [ ] **Step 3: Enhance `ModelModal` with "Pick from proxy" button**

In `ModelModal`, replace the `modelName` input block:

```javascript
// Before:
      <label>Model Name *</label>
      <input type="text" className={styles.input} value={form.modelName}
        onChange={e => set('modelName', e.target.value)} required />

// After:
      <label>Model Name *</label>
      <div className={styles.modelNameRow}>
        <input type="text" className={styles.input} value={form.modelName}
          onChange={e => set('modelName', e.target.value)} required />
        <ProxyModelPicker onPick={name => set('modelName', name)} />
      </div>
```

Add `ProxyModelPicker` component before `ModelModal`:

```javascript
function ProxyModelPicker({ onPick }) {
  const { token } = useAuth()
  const api = providerConfigApi(makeRequest(token))
  const [models, setModels] = useState([])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)

  const load = async () => {
    if (open) { setOpen(false); return }
    setLoading(true)
    try {
      const data = await api.getProxyModels()
      setModels(data.models ?? [])
      setOpen(true)
    } catch {
      setModels([])
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.proxyPicker}>
      <Button variant="secondary" onClick={load} disabled={loading}>
        {loading ? '…' : 'Pick from proxy'}
      </Button>
      {open && models.length > 0 && (
        <select className={styles.input} size={Math.min(models.length, 6)}
          onChange={e => { onPick(e.target.value); setOpen(false) }}>
          {models.map(m => <option key={m} value={m}>{m}</option>)}
        </select>
      )}
    </div>
  )
}
```

- [ ] **Step 4: Add `ProviderConfigSection` to the `AIConfig` render**

In the `AIConfig` return JSX, add `<ProviderConfigSection token={token} />` after the Templates section and before the modals:

```javascript
      {/* LLM Provider */}
      <ProviderConfigSection token={token} />
```

- [ ] **Step 5: Add CSS for new elements to `AIConfig.module.css`**

Append to the existing CSS module:

```css
.providerForm {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-width: 540px;
}

.providerActions {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.25rem;
}

.connectionStatus {
  margin-top: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.modelList {
  margin: 0;
  padding-left: 1.25rem;
  font-size: 0.85rem;
}

.modelNameRow {
  display: flex;
  gap: 0.5rem;
  align-items: flex-start;
}

.proxyPicker {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  flex-shrink: 0;
}
```

- [ ] **Step 6: Run full build to verify frontend compiles**

```bash
mvn package -pl emcip-admin-ui -DskipTests 2>&1 | tail -20 | cat
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/AIConfig/
git commit -m "feat(ui): add LLM Provider tab and proxy model picker to AIConfig"
```

---

## Task 11: Full build + Spotless + push

- [ ] **Step 1: Apply Spotless across all modules**

```bash
mvn spotless:apply 2>&1 | grep -E "changed|clean" | cat
```

Expected: `0 were changed to be clean` for each module. If files were changed, `git add -A && git commit --amend` (only if this is the last commit and no remote push yet — otherwise `git add -A && git commit -m "style: spotless formatting"`).

- [ ] **Step 2: Run full test suite**

```bash
mvn test 2>&1 | tail -40 | cat
```

Expected: all modules pass, `BUILD SUCCESS`.

- [ ] **Step 3: Spotless check (verify)**

```bash
mvn spotless:check 2>&1 | grep -E "changed|clean|ERROR" | cat
```

Expected: `0 were changed to be clean` for every module.

- [ ] **Step 4: Push branch**

```bash
git push -u origin feat/llm-local-litellm
```

- [ ] **Step 5: Create PR**

```bash
gh pr create \
  --title "feat(llm): replace Anthropic client with OpenAI-compatible LiteLLM client" \
  --body "$(cat <<'EOF'
## Summary

- Replaces `AnthropicLlmClient` with `OpenAiCompatibleLlmClient` (POST /v1/chat/completions)
- New `LlmProviderConfig` entity + table stores LiteLLM proxy URL, configurable at runtime
- Admin UI gains LLM Provider tab: URL input, connectivity test, model list from proxy
- ModelModal gains "Pick from proxy" button to select model names from the running proxy
- Seeds updated: Qwen3-30B-A3B for response/summary, Qwen3-14B for validation/moderation
- New MODERATION task type + prompt template added (addresses backlog #8, German-aware)
- No env-var dependency for LLM calls — all config lives in DB

## Test plan

- [ ] Liquibase migrations apply cleanly (`mvn test` passes)
- [ ] `GET /api/provider-config` returns empty list when no configs exist
- [ ] `POST /api/provider-config` with `active: true` → deactivates others, returns 201
- [ ] `PUT /api/provider-config/{id}` with changed URL → updates correctly, api_key `"***"` ignored
- [ ] `DELETE /api/provider-config/{id}` → 204
- [ ] `GET /api/provider-config/models` returns model list from LiteLLM proxy proxy
- [ ] Admin UI: LLM Provider tab renders, Test Connection shows green + model list
- [ ] ModelModal: "Pick from proxy" populates model name dropdown
- [ ] Simulate UI: send test message → LLM call logged with correct model
- [ ] `mvn spotless:check` → 0 files changed

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Verification Checklist

1. `docker compose up` — PostgreSQL port 14005, Kafka port 14003
2. Start orchestrator — Liquibase applies 007/008/009, Swagger at `/swagger-ui.html`
3. `POST /api/provider-config` `{"name":"local","baseUrl":"http://YOUR_MAC_IP:4000","active":true}` → 201
4. `GET /api/provider-config` → returns list with one entry, `apiKey: ""`
4b. `PUT /api/provider-config/{id}` with updated URL → 200, change reflected
4c. (Optional) `DELETE /api/provider-config/{id}` → 204
5. `GET /api/provider-config/models` → model list from LiteLLM
6. `GET /api/ai/provider-config/models` via admin-api (port 9087) → same result
7. Simulate UI: send test Telegram message → logs show `LlmCallService` calling LiteLLM
8. Admin UI: AI Config → LLM Provider section → enter URL → Test Connection → green badge + model list
9. Edit model → "Pick from proxy" → dropdown with model names
10. `mvn spotless:check` → `0 were changed to be clean`
11. `mvn test` → `BUILD SUCCESS`
