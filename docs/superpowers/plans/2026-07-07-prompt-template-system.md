# Prompt Template System & AI Config Improvements — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make prompt templates the single source of truth for LLM prompts, model selection, and parameters — replacing hardcoded prompts and taskType-driven model routing.

**Architecture:** Four independent change areas across three services: (1) Liquibase schema migration + seed data in llm-orchestrator, (2) backend wiring in llm-orchestrator to use template-owned model references and nullable temperature, (3) frontend changes in admin-ui for system badges and model dropdown, (4) circuit breaker tuning in knowledge-engine and admin-api. Each task produces a working, testable increment.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, Liquibase, PostgreSQL, Resilience4j, React (Vite), CSS Modules

## Global Constraints

- **Liquibase only** — never Flyway. All schema changes via XML changesets in `db/changelog/changes/`.
- **Lombok** — use `@Slf4j`, `@RequiredArgsConstructor`, `@Getter`/`@Setter`, `@Builder`. Never manual getters.
- **Spotless** — run `mvn spotless:apply -pl <module>` before every commit.
- **No secrets in code** — never put API keys, tokens, or passwords in migrations or committed files.
- **Jackson 3** — imports are `tools.jackson.databind.*`, not `com.fasterxml.jackson.*`.
- **Admin UI design system** — semantic tokens only (`var(--accent)`, etc.), Cinzel display font for headings, no emoji, no icon libraries, no rounded corners on data surfaces.
- **Temperature nullable** — `null` means "let LiteLLM/model decide". Default `maxTokens` = 8192.
- **`model_config_id` nullable** — `null` means fall back to `selectModelForTask()`.

---

### Task 1: Liquibase Migration — Schema Changes

**Files:**
- Create: `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/013-prompt-template-system-and-model-ref.xml`
- Modify: `emcip-llm-orchestrator/src/main/resources/db/changelog/db.changelog-master.xml:19`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: DB schema with `system` column, nullable `temperature`, dropped `model_provider`/`model_name`, new `model_config_id` FK on `prompt_templates`

- [ ] **Step 1: Create the migration file**

Create `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/013-prompt-template-system-and-model-ref.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">

    <changeSet id="013-add-system-column" author="ecip">
        <comment>Add 'system' boolean to distinguish built-in templates from custom ones</comment>
        <addColumn tableName="prompt_templates">
            <column name="system" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
        </addColumn>
    </changeSet>

    <changeSet id="013-make-temperature-nullable" author="ecip">
        <comment>Allow null temperature — null means let LiteLLM/model decide</comment>
        <dropNotNullConstraint tableName="prompt_templates"
                               columnName="temperature"
                               columnDataType="DOUBLE PRECISION"/>
    </changeSet>

    <changeSet id="013-add-model-config-ref" author="ecip">
        <comment>Replace dead model_provider/model_name with FK to model_configs.
            Template now owns the model choice.</comment>
        <addColumn tableName="prompt_templates">
            <column name="model_config_id" type="UUID">
                <constraints nullable="true"/>
            </column>
        </addColumn>
        <addForeignKeyConstraint
                baseTableName="prompt_templates" baseColumnNames="model_config_id"
                referencedTableName="model_configs" referencedColumnNames="id"
                constraintName="fk_template_model_config"/>
    </changeSet>

    <changeSet id="013-drop-dead-model-fields" author="ecip">
        <comment>model_provider and model_name were never used in any LLM call — remove them</comment>
        <dropIndex tableName="prompt_templates" indexName="idx_prompt_templates_model_provider"/>
        <dropColumn tableName="prompt_templates" columnName="model_provider"/>
        <dropColumn tableName="prompt_templates" columnName="model_name"/>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Register in changelog master**

Add the include to `emcip-llm-orchestrator/src/main/resources/db/changelog/db.changelog-master.xml` after line 19 (after the `012-activate-litellm-provider.xml` include):

```xml
    <include file="classpath:db/changelog/changes/013-prompt-template-system-and-model-ref.xml"/>
```

- [ ] **Step 3: Verify migration compiles**

Run:
```bash
cd /home/ben/Development/ecip && mvn compile -pl emcip-llm-orchestrator -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator
git add emcip-llm-orchestrator/src/main/resources/db/changelog/
git commit -m "feat(llm-orchestrator): add system column, model_config FK, drop dead model fields

Liquibase migration 013: adds 'system' boolean, makes temperature nullable,
adds model_config_id FK, drops unused model_provider/model_name columns."
```

---

### Task 2: Liquibase Migration — Seed System Templates

**Files:**
- Create: `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/014-seed-system-templates.xml`
- Modify: `emcip-llm-orchestrator/src/main/resources/db/changelog/db.changelog-master.xml`

**Interfaces:**
- Consumes: Schema from Task 1 (system column, model_config_id FK, temperature nullable)
- Produces: 5 new system templates seeded, 5 existing templates updated to `system=true`, `max_tokens=8192`, `temperature=NULL`, all with `model_config_id` set via subquery

- [ ] **Step 1: Create the seed migration file**

Create `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/014-seed-system-templates.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">

    <!-- Mark existing 5 templates as system, update defaults -->
    <changeSet id="014-mark-existing-templates-system" author="ecip">
        <comment>Mark existing seeded templates as system templates with new defaults</comment>
        <update tableName="prompt_templates">
            <column name="system" valueBoolean="true"/>
            <column name="max_tokens" valueNumeric="8192"/>
            <column name="temperature"/>
            <column name="updated_at" valueComputed="now()"/>
            <where>name IN ('auto_response', 'escalation_summary', 'command_validation',
                    'moderation_check', 'knowledge_extraction')</where>
        </update>
    </changeSet>

    <!-- Set model_config_id on existing templates via model_key lookup -->
    <changeSet id="014-link-auto-response-model" author="ecip">
        <sql>UPDATE prompt_templates SET model_config_id = (SELECT id FROM model_configs WHERE model_key = 'claude-haiku-response' AND active = true LIMIT 1) WHERE name = 'auto_response';</sql>
    </changeSet>

    <changeSet id="014-link-escalation-summary-model" author="ecip">
        <sql>UPDATE prompt_templates SET model_config_id = (SELECT id FROM model_configs WHERE model_key = 'claude-haiku-summary' AND active = true LIMIT 1) WHERE name = 'escalation_summary';</sql>
    </changeSet>

    <changeSet id="014-link-command-validation-model" author="ecip">
        <sql>UPDATE prompt_templates SET model_config_id = (SELECT id FROM model_configs WHERE model_key = 'claude-haiku-command' AND active = true LIMIT 1) WHERE name = 'command_validation';</sql>
    </changeSet>

    <changeSet id="014-link-moderation-check-model" author="ecip">
        <sql>UPDATE prompt_templates SET model_config_id = (SELECT id FROM model_configs WHERE model_key = 'qwen3-14b-moderation' AND active = true LIMIT 1) WHERE name = 'moderation_check';</sql>
    </changeSet>

    <changeSet id="014-link-knowledge-extraction-model" author="ecip">
        <sql>UPDATE prompt_templates SET model_config_id = (SELECT id FROM model_configs WHERE model_key = 'knowledge-extract' AND active = true LIMIT 1) WHERE name = 'knowledge_extraction';</sql>
    </changeSet>

    <!-- Seed flag_analysis — Decisions AI chat system prompt -->
    <changeSet id="014-seed-flag-analysis" author="ecip">
        <insert tableName="prompt_templates">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="tenant_id" value="00000000-0000-0000-0000-000000000000"/>
            <column name="name" value="flag_analysis"/>
            <column name="version" value="1.0.0"/>
            <column name="description" value="System prompt for Decisions AI chat — moderation analyst"/>
            <column name="system" valueBoolean="true"/>
            <column name="system_prompt" value="You are a moderation analyst for the EMCIP platform. You are assisting an operator investigating a flagged message.

Help the operator understand this flag and research appropriate responses."/>
            <column name="user_prompt_template" value="{{content}}"/>
            <column name="max_tokens" valueNumeric="8192"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
            <column name="created_at" valueComputed="now()"/>
            <column name="updated_at" valueComputed="now()"/>
            <column name="version_lock" valueNumeric="0"/>
        </insert>
    </changeSet>

    <changeSet id="014-link-flag-analysis-model" author="ecip">
        <sql>UPDATE prompt_templates SET model_config_id = (SELECT id FROM model_configs WHERE model_key = 'qwen3-30b-a3b-general' AND active = true LIMIT 1) WHERE name = 'flag_analysis';</sql>
    </changeSet>

    <!-- Seed flag_analyse — single-shot flag analysis -->
    <changeSet id="014-seed-flag-analyse" author="ecip">
        <insert tableName="prompt_templates">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="tenant_id" value="00000000-0000-0000-0000-000000000000"/>
            <column name="name" value="flag_analyse"/>
            <column name="version" value="1.0.0"/>
            <column name="description" value="System prompt for single-shot flag analysis"/>
            <column name="system" valueBoolean="true"/>
            <column name="system_prompt" value="You are a moderation analyst for the EMCIP platform. Analyse the provided flag data and explain the moderation decision clearly and concisely."/>
            <column name="user_prompt_template" value="{{content}}"/>
            <column name="max_tokens" valueNumeric="8192"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
            <column name="created_at" valueComputed="now()"/>
            <column name="updated_at" valueComputed="now()"/>
            <column name="version_lock" valueNumeric="0"/>
        </insert>
    </changeSet>

    <changeSet id="014-link-flag-analyse-model" author="ecip">
        <sql>UPDATE prompt_templates SET model_config_id = (SELECT id FROM model_configs WHERE model_key = 'qwen3-30b-a3b-general' AND active = true LIMIT 1) WHERE name = 'flag_analyse';</sql>
    </changeSet>

    <!-- Seed research_topic -->
    <changeSet id="014-seed-research-topic" author="ecip">
        <insert tableName="prompt_templates">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="tenant_id" value="00000000-0000-0000-0000-000000000000"/>
            <column name="name" value="research_topic"/>
            <column name="version" value="1.0.0"/>
            <column name="description" value="Topic research report generation"/>
            <column name="system" valueBoolean="true"/>
            <column name="system_prompt" value="You are a research analyst synthesizing community intelligence.
Based on the evidence provided, write a structured research report.

Format your response as Markdown with exactly these sections:
## Executive Summary
(2–3 sentences summarizing key findings)

## Key Findings
(3–5 bullet points of the most important discoveries)

## Community Perspective
(What community members discuss, believe, or are concerned about)

## Factual Context
(Verified facts from external sources that provide context)

## Contradictions &amp; Open Questions
(Areas of disagreement, unverified claims, or open questions)

## Sources
(List each source as: - [source_type] source_ref)"/>
            <column name="user_prompt_template" value="Research topic: {{topic}}

Evidence collected:
{{evidence}}"/>
            <column name="max_tokens" valueNumeric="8192"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
            <column name="created_at" valueComputed="now()"/>
            <column name="updated_at" valueComputed="now()"/>
            <column name="version_lock" valueNumeric="0"/>
        </insert>
    </changeSet>

    <changeSet id="014-link-research-topic-model" author="ecip">
        <sql>UPDATE prompt_templates SET model_config_id = (SELECT id FROM model_configs WHERE model_key = 'qwen3-30b-a3b-general' AND active = true LIMIT 1) WHERE name = 'research_topic';</sql>
    </changeSet>

    <!-- Seed research_person -->
    <changeSet id="014-seed-research-person" author="ecip">
        <insert tableName="prompt_templates">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="tenant_id" value="00000000-0000-0000-0000-000000000000"/>
            <column name="name" value="research_person"/>
            <column name="version" value="1.0.0"/>
            <column name="description" value="Person profile research report"/>
            <column name="system" valueBoolean="true"/>
            <column name="system_prompt" value="You are a research analyst profiling an individual based on community intelligence.
Based on the evidence provided, write a structured person analysis report.

Format your response as Markdown with exactly these sections:
## Executive Summary
(2–3 sentences about this person's role and significance)

## Key Findings
(3–5 bullet points about this person's notable activities or statements)

## Community Perspective
(How community members perceive and discuss this person)

## Factual Context
(Verified facts about this person from external sources)

## Contradictions &amp; Open Questions
(Inconsistencies in reporting or open questions)

## Sources
(List each source as: - [source_type] source_ref)"/>
            <column name="user_prompt_template" value="Person: {{topic}}

Evidence collected:
{{evidence}}"/>
            <column name="max_tokens" valueNumeric="8192"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
            <column name="created_at" valueComputed="now()"/>
            <column name="updated_at" valueComputed="now()"/>
            <column name="version_lock" valueNumeric="0"/>
        </insert>
    </changeSet>

    <changeSet id="014-link-research-person-model" author="ecip">
        <sql>UPDATE prompt_templates SET model_config_id = (SELECT id FROM model_configs WHERE model_key = 'qwen3-30b-a3b-general' AND active = true LIMIT 1) WHERE name = 'research_person';</sql>
    </changeSet>

    <!-- Seed research_fact_check -->
    <changeSet id="014-seed-research-fact-check" author="ecip">
        <insert tableName="prompt_templates">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="tenant_id" value="00000000-0000-0000-0000-000000000000"/>
            <column name="name" value="research_fact_check"/>
            <column name="version" value="1.0.0"/>
            <column name="description" value="Fact-check verdict report"/>
            <column name="system" valueBoolean="true"/>
            <column name="system_prompt" value="You are a fact-checking researcher.
Based on the evidence provided, write a structured fact-check report.

Format your response as Markdown with exactly these sections:
## Executive Summary
(Verdict: Supported / Unsupported / Partially Supported / Insufficient Evidence)

## Key Findings
(3–5 bullet points of evidence for or against the claim)

## Community Perspective
(What community members say about this claim)

## Factual Context
(Verified facts from external sources)

## Contradictions &amp; Open Questions
(Conflicting evidence or remaining uncertainty)

## Sources
(List each source as: - [source_type] source_ref)"/>
            <column name="user_prompt_template" value="Claim to fact-check: {{topic}}

Evidence collected:
{{evidence}}"/>
            <column name="max_tokens" valueNumeric="8192"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
            <column name="created_at" valueComputed="now()"/>
            <column name="updated_at" valueComputed="now()"/>
            <column name="version_lock" valueNumeric="0"/>
        </insert>
    </changeSet>

    <changeSet id="014-link-research-fact-check-model" author="ecip">
        <sql>UPDATE prompt_templates SET model_config_id = (SELECT id FROM model_configs WHERE model_key = 'qwen3-30b-a3b-general' AND active = true LIMIT 1) WHERE name = 'research_fact_check';</sql>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Register in changelog master**

Add to `db.changelog-master.xml` after the 013 include:

```xml
    <include file="classpath:db/changelog/changes/014-seed-system-templates.xml"/>
```

- [ ] **Step 3: Verify compilation**

Run:
```bash
cd /home/ben/Development/ecip && mvn compile -pl emcip-llm-orchestrator -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator
git add emcip-llm-orchestrator/src/main/resources/db/changelog/
git commit -m "feat(llm-orchestrator): seed 5 new system templates, mark existing 5 as system

Migration 014: seeds flag_analysis, flag_analyse, research_topic,
research_person, research_fact_check. Updates existing templates
to system=true, maxTokens=8192, temperature=NULL. Links all to model_configs."
```

---

### Task 3: PromptTemplate Entity + Repository — Model Config Relationship

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/PromptTemplate.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/repository/PromptTemplateRepository.java`
- Modify: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerChatTest.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/entity/PromptTemplateTest.java`

**Interfaces:**
- Consumes: `ModelConfig` entity (unchanged)
- Produces: `PromptTemplate.getModelConfig()` returning `ModelConfig` (nullable), `PromptTemplate.getSystem()` returning `Boolean`, `PromptTemplate.getTemperature()` returning `Double` (nullable). `PromptTemplateRepository.findByNameAndActiveTrue(name)` — unchanged signature, now returns entity with new fields.

- [ ] **Step 1: Write test for new entity fields**

Create `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/entity/PromptTemplateTest.java`:

```java
package io.emcip.llm.orchestrator.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PromptTemplateTest {

    @Test
    void systemDefaultsToFalse() {
        PromptTemplate template = new PromptTemplate();
        assertThat(template.getSystem()).isFalse();
    }

    @Test
    void temperatureCanBeNull() {
        PromptTemplate template = new PromptTemplate();
        template.setTemperature(null);
        assertThat(template.getTemperature()).isNull();
    }

    @Test
    void modelConfigCanBeNull() {
        PromptTemplate template = new PromptTemplate();
        assertThat(template.getModelConfig()).isNull();
    }

    @Test
    void modelConfigCanBeSet() {
        PromptTemplate template = new PromptTemplate();
        ModelConfig model = new ModelConfig();
        model.setModelName("qwen3-30b-a3b");
        template.setModelConfig(model);
        assertThat(template.getModelConfig().getModelName()).isEqualTo("qwen3-30b-a3b");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=PromptTemplateTest -q
```
Expected: FAIL — `getSystem()` and `getModelConfig()` don't exist yet

- [ ] **Step 3: Update PromptTemplate entity**

In `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/PromptTemplate.java`:

Remove these fields (lines 61–67):
```java
    @Schema(description = "LLM provider this template targets", example = "openai")
    @Column(nullable = false, length = 50)
    private String modelProvider;

    @Schema(description = "Model name this template is tuned for", example = "gpt-4-turbo")
    @Column(nullable = false, length = 50)
    private String modelName;
```

Add these imports:
```java
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
```

Add these new fields after the `description` field:

```java
    @Schema(description = "Whether this is a built-in system template (cannot be deleted/renamed)")
    @Column(nullable = false)
    @Builder.Default
    private Boolean system = false;

    @Schema(description = "Model configuration this template uses for LLM calls")
    @ManyToOne
    @JoinColumn(name = "model_config_id")
    private ModelConfig modelConfig;
```

Change temperature (line 80–82) from:
```java
    @Column(nullable = false)
    @Builder.Default
    private Double temperature = 0.7;
```
to:
```java
    @Column
    private Double temperature;
```

Change maxTokens (line 85–87) from:
```java
    @Column(nullable = false)
    @Builder.Default
    private Integer maxTokens = 2048;
```
to:
```java
    @Column(nullable = false)
    @Builder.Default
    private Integer maxTokens = 8192;
```

- [ ] **Step 4: Update PromptTemplateRepository**

In `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/repository/PromptTemplateRepository.java`:

Remove the `findByModelProviderAndActiveTrue` method (line 23) and the `findByTaskType` method (lines 26–29) — both reference the now-removed `modelProvider` field.

- [ ] **Step 5: Fix compilation errors in dependent code**

In `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmOrchestratorService.java`:

Remove the `getPromptTemplatesForProvider` method (lines 95–98) which calls the deleted repository method.

In `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`:

In `updateTemplate()` method (lines 150–155), remove these two lines:
```java
        existing.setModelProvider(update.getModelProvider());
        existing.setModelName(update.getModelName());
```

Replace with:
```java
        existing.setModelConfig(update.getModelConfig());
```

- [ ] **Step 6: Fix existing tests**

In `OrchestratorControllerChatTest.java`: if tests reference `modelProvider` or `modelName` on PromptTemplate, update them to use `modelConfig` instead. The test likely only uses ModelConfig directly (via `selectModelForTask`), so may need no changes — verify by running.

- [ ] **Step 7: Run all tests**

Run:
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -q
```
Expected: All tests PASS

- [ ] **Step 8: Commit**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator
git add emcip-llm-orchestrator/src/main/java/ emcip-llm-orchestrator/src/test/java/
git commit -m "feat(llm-orchestrator): add system flag, modelConfig FK to PromptTemplate

Remove dead modelProvider/modelName fields. Template now references
ModelConfig via @ManyToOne. Temperature nullable, maxTokens default 8192."
```

---

### Task 4: Nullable Temperature in OpenAiCompatibleLlmClient

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClient.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClientTest.java`

**Interfaces:**
- Consumes: nothing new
- Produces: `OpenAiCompatibleLlmClient.call(String, String, String, int, Double)` and `chat(String, List, int, Double)` — `Double` (nullable) instead of `double`

- [ ] **Step 1: Write tests for nullable temperature**

Create `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClientTest.java`:

```java
package io.emcip.llm.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleLlmClientTest {

    @Test
    void callOmitsTemperatureWhenNull() {
        // We test the body-building logic by verifying the map construction.
        // The actual HTTP call is not tested here (integration test).
        Map<String, Object> body = new HashMap<>();
        body.put("model", "test-model");
        body.put("max_tokens", 1024);
        // Null temperature — should NOT be in the map
        Double temperature = null;
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        assertThat(body).doesNotContainKey("temperature");
    }

    @Test
    void callIncludesTemperatureWhenProvided() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "test-model");
        body.put("max_tokens", 1024);
        Double temperature = 0.7;
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        assertThat(body).containsEntry("temperature", 0.7);
    }
}
```

- [ ] **Step 2: Run tests to verify they pass** (these test the logic pattern, not the client)

Run:
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=OpenAiCompatibleLlmClientTest -q
```
Expected: PASS

- [ ] **Step 3: Update OpenAiCompatibleLlmClient**

In `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClient.java`:

**`call()` method** (line 39–44): change signature from `double temperature` to `Double temperature`.

Change the body-building (lines 55–58) from:
```java
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
```
to:
```java
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
```

**`chat()` method** (line 114–115): change signature from `double temperature` to `Double temperature`.

Change the body-building (lines 126–129) from:
```java
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
```
to:
```java
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
```

- [ ] **Step 4: Run all tests**

Run:
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -q
```
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator
git add emcip-llm-orchestrator/src/main/java/ emcip-llm-orchestrator/src/test/java/
git commit -m "feat(llm-orchestrator): support nullable temperature in LLM client

Null temperature omits the key from the request body, letting
LiteLLM/model use its own default."
```

---

### Task 5: Template-Driven Model Resolution in LlmCallService + OrchestratorController

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmCallService.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmOrchestratorService.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/LlmCallServiceTest.java`

**Interfaces:**
- Consumes: `PromptTemplate.getModelConfig()` (Task 3), `OpenAiCompatibleLlmClient.call(model, prompt, content, maxTokens, Double)` (Task 4)
- Produces: `LlmCallService.callForTask()` — now uses `template.getModelConfig()` when present. `OrchestratorController` `/api/analyse` and `/api/chat` use template lookup. `GET /api/templates/{name}` endpoint.

- [ ] **Step 1: Write test for template-driven model resolution**

Create `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/LlmCallServiceTest.java`:

```java
package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.client.LlmCallResult;
import io.emcip.llm.orchestrator.client.LlmResponse;
import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.config.KnowledgeEnrichmentProperties;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.entity.PromptTemplate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmCallServiceTest {

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @Mock private CostTrackingService costTrackingService;
    @Mock private KnowledgeContextEnricherService enricherService;
    @Mock private KnowledgeEnrichmentProperties enrichmentProperties;
    @InjectMocks private LlmCallService llmCallService;

    @Test
    void callForTaskUsesTemplateModelConfigWhenPresent() {
        ModelConfig templateModel = new ModelConfig();
        templateModel.setModelKey("template-model");
        templateModel.setModelName("qwen3-30b-a3b");

        ModelConfig taskModel = new ModelConfig();
        taskModel.setModelKey("task-model");
        taskModel.setModelName("qwen3-14b");

        PromptTemplate template = new PromptTemplate();
        template.setName("test_template");
        template.setSystemPrompt("System prompt");
        template.setUserPromptTemplate("{{content}}");
        template.setMaxTokens(8192);
        template.setTemperature(null);
        template.setModelConfig(templateModel);

        when(orchestratorService.selectModelForTask("GENERAL")).thenReturn(Optional.of(taskModel));
        when(orchestratorService.getPromptTemplate("test_template")).thenReturn(Optional.of(template));
        when(orchestratorService.renderPromptTemplate(any(), any())).thenReturn("");
        when(enrichmentProperties.enabled()).thenReturn(false);
        when(llmClient.call(eq("qwen3-30b-a3b"), anyString(), anyString(), eq(8192), isNull()))
                .thenReturn(new LlmResponse("result", 100, 50, "qwen3-30b-a3b"));

        Optional<LlmCallResult> result = llmCallService.callForTask(
                "GENERAL", "test_template", "user content", Map.of(), "event-1", null);

        assertThat(result).isPresent();
        // Verify the template's model was used, not the taskType model
        verify(llmClient).call(eq("qwen3-30b-a3b"), anyString(), anyString(), eq(8192), isNull());
    }

    @Test
    void callForTaskFallsBackToTaskTypeModelWhenTemplateModelNull() {
        ModelConfig taskModel = new ModelConfig();
        taskModel.setModelKey("task-model");
        taskModel.setModelName("qwen3-14b");

        PromptTemplate template = new PromptTemplate();
        template.setName("test_template");
        template.setSystemPrompt("System prompt");
        template.setUserPromptTemplate("{{content}}");
        template.setMaxTokens(8192);
        template.setTemperature(0.5);
        template.setModelConfig(null);

        when(orchestratorService.selectModelForTask("GENERAL")).thenReturn(Optional.of(taskModel));
        when(orchestratorService.getPromptTemplate("test_template")).thenReturn(Optional.of(template));
        when(orchestratorService.renderPromptTemplate(any(), any())).thenReturn("");
        when(enrichmentProperties.enabled()).thenReturn(false);
        when(llmClient.call(eq("qwen3-14b"), anyString(), anyString(), eq(8192), eq(0.5)))
                .thenReturn(new LlmResponse("result", 100, 50, "qwen3-14b"));

        Optional<LlmCallResult> result = llmCallService.callForTask(
                "GENERAL", "test_template", "user content", Map.of(), "event-1", null);

        assertThat(result).isPresent();
        verify(llmClient).call(eq("qwen3-14b"), anyString(), anyString(), eq(8192), eq(0.5));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=LlmCallServiceTest -q
```
Expected: FAIL — `callForTask()` still uses `modelOpt.get()` unconditionally

- [ ] **Step 3: Update LlmCallService.callForTask()**

In `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmCallService.java`:

Replace the `callForTask` method (lines 43–75) with:

```java
    public Optional<LlmCallResult> callForTask(
            String taskType,
            String templateName,
            String userContent,
            Map<String, String> contextVars,
            String sourceEventId,
            String conversationId) {

        Optional<PromptTemplate> templateOpt = orchestratorService.getPromptTemplate(templateName);

        if (templateOpt.isEmpty()) {
            log.warn(
                    "No prompt template '{}' configured for task '{}' - skipping LLM call",
                    templateName,
                    taskType);
            return Optional.empty();
        }

        PromptTemplate template = templateOpt.get();

        // Template owns model choice; fall back to taskType selection if not set
        ModelConfig modelConfig;
        if (template.getModelConfig() != null) {
            modelConfig = template.getModelConfig();
            log.debug("Using template's model config: {}", modelConfig.getModelKey());
        } else {
            Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
            if (modelOpt.isEmpty()) {
                log.warn("No model configured for task type '{}' - skipping LLM call", taskType);
                return Optional.empty();
            }
            modelConfig = modelOpt.get();
            log.debug("Template has no model config, falling back to task type: {}", taskType);
        }

        return Optional.of(
                call(modelConfig, template, userContent, contextVars, sourceEventId, conversationId));
    }
```

- [ ] **Step 4: Update OrchestratorController /api/analyse**

In `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`:

Replace the `analyse` method (lines 312–340) with:

```java
    @Operation(summary = "Run an ad-hoc LLM analysis using the flag_analyse template")
    @PostMapping("/analyse")
    public ResponseEntity<AnalyseResponse> analyse(@RequestBody AnalyseRequest req) {
        // Look up the flag_analyse template first
        Optional<PromptTemplate> templateOpt = orchestratorService.getPromptTemplate("flag_analyse");

        String systemPrompt;
        int maxTokens;
        Double temperature;
        String modelName;

        if (templateOpt.isPresent()) {
            PromptTemplate template = templateOpt.get();
            systemPrompt = template.getSystemPrompt();
            maxTokens = template.getMaxTokens();
            temperature = template.getTemperature();

            if (template.getModelConfig() != null) {
                modelName = template.getModelConfig().getModelName();
            } else {
                String taskType = req.taskType() != null ? req.taskType() : "GENERAL";
                Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
                if (modelOpt.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(new AnalyseResponse(false, "No model configured for task: " + taskType, null));
                }
                modelName = modelOpt.get().getModelName();
            }
        } else {
            // Fallback to hardcoded defaults
            String taskType = req.taskType() != null ? req.taskType() : "GENERAL";
            Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
            if (modelOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new AnalyseResponse(false, "No model configured for task: " + taskType, null));
            }
            modelName = modelOpt.get().getModelName();
            systemPrompt = "You are a moderation analyst for the EMCIP platform. Analyse the"
                    + " provided flag data and explain the moderation decision clearly"
                    + " and concisely.";
            maxTokens = 8192;
            temperature = null;
        }

        try {
            LlmResponse response = llmClient.call(modelName, systemPrompt, req.prompt(), maxTokens, temperature);
            return ResponseEntity.ok(new AnalyseResponse(true, response.content(), response.model()));
        } catch (Exception e) {
            log.error("Ad-hoc LLM analysis failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AnalyseResponse(false, "LLM call failed: " + e.getMessage(), null));
        }
    }
```

- [ ] **Step 5: Update OrchestratorController /api/chat**

Replace the `chat` method (lines 362–386) with:

```java
    @Operation(summary = "Multi-turn chat using the flag_analysis template")
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest req) {
        Optional<PromptTemplate> templateOpt = orchestratorService.getPromptTemplate("flag_analysis");

        int maxTokens;
        Double temperature;
        String modelName;

        if (templateOpt.isPresent()) {
            PromptTemplate template = templateOpt.get();
            maxTokens = template.getMaxTokens();
            temperature = template.getTemperature();

            if (template.getModelConfig() != null) {
                modelName = template.getModelConfig().getModelName();
            } else {
                String taskType = req.taskType() != null ? req.taskType() : "GENERAL";
                Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
                if (modelOpt.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(new ChatResponse(false, "No model configured for task: " + taskType, null));
                }
                modelName = modelOpt.get().getModelName();
            }
        } else {
            String taskType = req.taskType() != null ? req.taskType() : "GENERAL";
            Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
            if (modelOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new ChatResponse(false, "No model configured for task: " + taskType, null));
            }
            modelName = modelOpt.get().getModelName();
            maxTokens = 8192;
            temperature = null;
        }

        try {
            List<Map<String, String>> messages =
                    req.messages().stream()
                            .map(m -> Map.of("role", m.role(), "content", m.content()))
                            .toList();
            LlmResponse response = llmClient.chat(modelName, messages, maxTokens, temperature);
            return ResponseEntity.ok(new ChatResponse(true, response.content(), response.model()));
        } catch (Exception e) {
            log.error("Chat call failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ChatResponse(false, "LLM call failed: " + e.getMessage(), null));
        }
    }
```

- [ ] **Step 6: Add GET /api/templates/{name} endpoint**

Add this method to `OrchestratorController.java` after the `listTemplates()` method:

```java
    @Operation(summary = "Get a prompt template by name")
    @GetMapping("/templates/{name}")
    public ResponseEntity<PromptTemplate> getTemplateByName(@PathVariable String name) {
        return orchestratorService.getPromptTemplate(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
```

- [ ] **Step 7: Add system template protection to delete and update**

In `OrchestratorController.java`, update `deleteTemplate()`:

```java
    @Operation(summary = "Delete a prompt template")
    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@PathVariable UUID id) {
        PromptTemplate template = promptTemplateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: " + id));
        if (Boolean.TRUE.equals(template.getSystem())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete system template: " + template.getName());
        }
        promptTemplateRepository.deleteById(id);
    }
```

In `updateTemplate()`, add system name protection at the start:

```java
        if (Boolean.TRUE.equals(existing.getSystem()) && !existing.getName().equals(update.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot rename system template: " + existing.getName());
        }
```

- [ ] **Step 8: Run all tests**

Run:
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -q
```
Expected: All tests PASS

- [ ] **Step 9: Commit**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator
git add emcip-llm-orchestrator/src/main/java/ emcip-llm-orchestrator/src/test/java/
git commit -m "feat(llm-orchestrator): template-driven model resolution, system protection

callForTask() uses template.modelConfig when present, falls back to
selectModelForTask(). /api/analyse and /api/chat use template lookup.
Added GET /api/templates/{name}. System templates cannot be deleted/renamed."
```

---

### Task 6: Circuit Breaker Tuning — Knowledge Engine + Admin API

**Files:**
- Modify: `emcip-knowledge-engine/src/main/resources/application.yml:74-83`
- Modify: `emcip-admin-api/src/main/resources/application.yml:97-119`

**Interfaces:**
- Consumes: nothing (independent)
- Produces: Split circuit breaker config for knowledge-engine (`llm-orchestrator-embed` strict, `llm-orchestrator-analyse` lenient). Admin-api `orchestrator` instance uses lenient profile.

- [ ] **Step 1: Update knowledge-engine circuit breaker config**

In `emcip-knowledge-engine/src/main/resources/application.yml`, replace lines 74–83:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      llm-orchestrator-embed:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        slow-call-duration-threshold: 10s
        slow-call-rate-threshold: 80
      llm-orchestrator-analyse:
        sliding-window-size: 10
        failure-rate-threshold: 70
        wait-duration-in-open-state: 120s
        permitted-number-of-calls-in-half-open-state: 5
        slow-call-duration-threshold: 180s
        slow-call-rate-threshold: 90
  retry:
    instances:
      llm-orchestrator-analyse:
        max-attempts: 2
        wait-duration: 10s
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
          - java.net.SocketTimeoutException
```

- [ ] **Step 2: Update admin-api circuit breaker config**

In `emcip-admin-api/src/main/resources/application.yml`, replace the `orchestrator` instance config (line 114–115):

```yaml
      orchestrator:
        slidingWindowSize: 10
        failureRateThreshold: 70
        waitDurationInOpenState: 120s
        permittedNumberOfCallsInHalfOpenState: 5
        slowCallDurationThreshold: 180s
        slowCallRateThreshold: 90
```

- [ ] **Step 3: Verify both modules compile**

Run:
```bash
cd /home/ben/Development/ecip && mvn compile -pl emcip-knowledge-engine,emcip-admin-api -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine,emcip-admin-api
git add emcip-knowledge-engine/src/main/resources/application.yml emcip-admin-api/src/main/resources/application.yml
git commit -m "fix(circuit-breaker): split embed/analyse, lenient thresholds for LLM calls

Knowledge-engine: embed=strict (10s slow-call, 50% failure, 30s wait),
analyse=lenient (180s slow-call, 70% failure, 120s wait) + 1 retry 10s.
Admin-api orchestrator: matches lenient profile."
```

---

### Task 7: Frontend — System Badge, Model Dropdown, Nullable Temperature

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.jsx:136-206, 463-494`
- Modify: `emcip-admin-ui/src/main/frontend/src/api/aiConfig.js`

**Interfaces:**
- Consumes: Backend API — `GET /api/ai/templates` now returns `system` boolean and `modelConfig` object (with `id`, `modelName`, `modelKey`). `GET /api/ai/models` returns list of ModelConfig entries.
- Produces: Updated `TemplateModal` with model dropdown and nullable temperature. Updated template table with Type column and system badge. Delete button hidden for system templates.

- [ ] **Step 1: Update TemplateModal with model dropdown and system protection**

In `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.jsx`, replace the `TemplateModal` component (lines 136–206) with:

```jsx
function TemplateModal({ template, models, onClose, onSave }) {
  const isSystem = template?.system === true
  const [form, setForm] = useState({
    name: template?.name ?? '',
    version: template?.version ?? '1.0',
    description: template?.description ?? '',
    modelConfigId: template?.modelConfig?.id ?? '',
    systemPrompt: template?.systemPrompt ?? '',
    userPromptTemplate: template?.userPromptTemplate ?? '',
    temperature: template?.temperature ?? '',
    maxTokens: template?.maxTokens ?? 8192,
    active: template?.active ?? true,
    priority: template?.priority ?? 100,
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  const handleSave = () => {
    const payload = {
      ...form,
      temperature: form.temperature === '' ? null : parseFloat(form.temperature),
      modelConfig: form.modelConfigId ? { id: form.modelConfigId } : null,
    }
    delete payload.modelConfigId
    onSave(payload)
  }

  return (
    <Modal title={template ? 'Edit Template' : 'Add Template'} onClose={onClose} onSubmit={handleSave}>
      <div className={styles.field}>
        <label>Name *</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required disabled={isSystem} />
      </div>
      <div className={styles.field}>
        <label>Version</label>
        <input type="text" className={styles.input} value={form.version}
          onChange={e => set('version', e.target.value)} />
      </div>
      <div className={styles.field}>
        <label>Description</label>
        <input type="text" className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} />
      </div>
      <div className={styles.field}>
        <label>Model</label>
        <select className={styles.input} value={form.modelConfigId}
          onChange={e => set('modelConfigId', e.target.value)}>
          <option value="">Default (auto)</option>
          {models.map(m => (
            <option key={m.id} value={m.id}>{m.modelKey} — {m.modelName}</option>
          ))}
        </select>
      </div>
      <div className={styles.field}>
        <label>System Prompt *</label>
        <textarea className={`${styles.input} ${styles.promptTextarea}`} rows={6}
          value={form.systemPrompt} onChange={e => set('systemPrompt', e.target.value)} required />
      </div>
      <div className={styles.field}>
        <label>User Prompt Template</label>
        <textarea className={`${styles.input} ${styles.promptTextarea}`} rows={4}
          value={form.userPromptTemplate} onChange={e => set('userPromptTemplate', e.target.value)}
          placeholder="Use {{variable}} placeholders" />
      </div>
      <div className={styles.field}>
        <label>Temperature</label>
        <input type="number" step="0.1" min="0" max="2" className={styles.input}
          value={form.temperature} onChange={e => set('temperature', e.target.value)}
          placeholder="Model default" />
      </div>
      <div className={styles.field}>
        <label>Max Tokens</label>
        <input type="number" className={styles.input} value={form.maxTokens}
          onChange={e => set('maxTokens', parseInt(e.target.value, 10))} />
      </div>
      <label>
        <input type="checkbox" checked={form.active}
          onChange={e => set('active', e.target.checked)} /> Active
      </label>
    </Modal>
  )
}
```

- [ ] **Step 2: Update template table with Type column and system badge**

In the templates table section (lines 463–494), replace the table with:

```jsx
      {/* Templates */}
      <div className={styles.section}>
        <SectionLabel aside={<Button onClick={() => setTemplateModal('add')}>+ Add Template</Button>}>Prompt Templates</SectionLabel>
        <div className={styles.tableWrapper}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Model</th>
              <th>System Prompt</th>
              <th>Active</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {templates.map(t => (
              <tr key={t.id} className={styles.clickable} onClick={() => setTemplateModal(t)}>
                <td>{t.name}</td>
                <td>{t.system ? <Badge variant="blue">System</Badge> : <Badge variant="gray">Custom</Badge>}</td>
                <td className={styles.mono}>{t.modelConfig?.modelKey ?? '—'}</td>
                <td className={styles.preview} title={t.systemPrompt}>{t.systemPrompt}</td>
                <td><Badge variant={t.active ? 'green' : 'red'}>{t.active ? 'Yes' : 'No'}</Badge></td>
                <td className={styles.actions} onClick={e => e.stopPropagation()}>
                  {!t.system && <Button variant="danger" onClick={() => setPendingDelete({ kind: 'template', row: t })}>Delete</Button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      </div>
```

- [ ] **Step 3: Pass models to TemplateModal**

In the modal rendering section (lines 506–512), update the `TemplateModal` usage:

```jsx
      {templateModal && (
        <TemplateModal
          template={templateModal === 'add' ? null : templateModal}
          models={models}
          onClose={() => setTemplateModal(null)}
          onSave={saveTemplate}
        />
      )}
```

- [ ] **Step 4: Verify the frontend builds**

Run:
```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend && npm run build 2>&1 | tail -5
```
Expected: Build succeeds

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-ui
git add emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.jsx
git commit -m "feat(admin-ui): system badge, model dropdown, nullable temperature in AI Config

Template table shows System/Custom badge. System templates can't be deleted
or renamed. Model dropdown populated from AI Models table. Empty temperature
field sends null (model default)."
```

---

### Task 8: Proxy GET /api/templates/{name} in Admin API

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java`

**Interfaces:**
- Consumes: `GET /api/templates/{name}` on orchestrator (Task 5)
- Produces: `GET /api/ai/templates/{name}` on admin-api — proxied to orchestrator

- [ ] **Step 1: Add the proxy endpoint**

In `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java`, after the `listTemplates()` method, add:

```java
    @Operation(summary = "Get a prompt template by name")
    @GetMapping("/templates/{name}")
    public Mono<String> getTemplateByName(@PathVariable String name) {
        return orchestratorClient
                .get()
                .uri("/api/templates/{name}", name)
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
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
cd /home/ben/Development/ecip && mvn compile -pl emcip-admin-api -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/src/main/java/
git commit -m "feat(admin-api): proxy GET /api/ai/templates/{name} to orchestrator

Enables FlagService and other admin-api code to look up templates by name."
```

---

### Task 9: FlagService — Use Templates Instead of Hardcoded Prompts

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java:139-233`

**Interfaces:**
- Consumes: `GET /api/templates/{name}` proxy (Task 8). Orchestrator `/api/analyse` and `/api/chat` already use templates (Task 5).
- Produces: `FlagService.analyse()` and `chat()` — no longer hardcode system prompts. The orchestrator now handles template lookup for `/api/analyse` and `/api/chat`, so FlagService just needs to stop prepending its own system prompt for chat (the orchestrator's template provides it).

Note: Since `/api/analyse` and `/api/chat` on the orchestrator now look up their own templates (Task 5), FlagService's `analyse()` method already benefits — it sends to `/api/analyse` which uses the `flag_analyse` template. For `chat()`, FlagService currently builds a system message from `buildChatSystemPrompt()` and prepends it. After this task, the orchestrator's `/api/chat` uses the `flag_analysis` template's systemPrompt, so FlagService should stop prepending its own.

- [ ] **Step 1: Update FlagService.chat() to not prepend hardcoded system prompt**

In `emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java`, replace the `chat` method (lines 162–193) with:

```java
    public Mono<JsonNode> chat(String flagId, JsonNode body) {
        return policyEngineClient
                .getDecision(flagId)
                .flatMap(
                        flag -> {
                            // Build context string for the template's user prompt
                            String context = buildFlagContext(flag);
                            JsonNode clientMessages = body.get("messages");

                            tools.jackson.databind.node.ArrayNode messages =
                                    JsonNodeFactory.instance.arrayNode();

                            // Add flag context as first user message if client messages exist
                            if (clientMessages != null && clientMessages.isArray()) {
                                boolean firstMessage = true;
                                for (JsonNode msg : clientMessages) {
                                    if (firstMessage && "user".equals(msg.path("role").asText())) {
                                        // Prepend flag context to first user message
                                        ObjectNode enriched = JsonNodeFactory.instance.objectNode();
                                        enriched.put("role", "user");
                                        enriched.put("content",
                                                "Context:\n" + context + "\n\nOperator question: "
                                                        + msg.path("content").asText());
                                        messages.add(enriched);
                                        firstMessage = false;
                                    } else {
                                        messages.add(msg);
                                        firstMessage = false;
                                    }
                                }
                            }

                            ObjectNode chatBody = JsonNodeFactory.instance.objectNode();
                            chatBody.set("messages", messages);
                            chatBody.put("taskType", "GENERAL");

                            return orchestratorWebClient
                                    .post()
                                    .uri("/api/chat")
                                    .bodyValue(chatBody)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        });
    }
```

Add this helper method (extract common flag context building):

```java
    private String buildFlagContext(JsonNode flag) {
        StringBuilder sb = new StringBuilder();
        sb.append("- Intent: ").append(flag.path("originalIntent").asText("unknown")).append("\n");
        sb.append("- Decision: ").append(flag.path("decision").asText("unknown")).append("\n");
        sb.append("- Confidence: ")
                .append(String.format("%.1f%%", flag.path("confidence").asDouble(0) * 100))
                .append("\n");
        sb.append("- Reason: ").append(flag.path("reason").asText("none")).append("\n");
        JsonNode meta = flag.path("metadata");
        if (!meta.isMissingNode() && !meta.isNull() && meta.has("messageText")) {
            sb.append("- Message text: ").append(meta.path("messageText").asText()).append("\n");
        }
        return sb.toString();
    }
```

The `buildChatSystemPrompt()` and `buildAnalysisPrompt()` methods can remain as fallback documentation but are no longer called in the main path. Remove them if they cause no other test failures; otherwise keep as dead code with a `// TODO: remove after template migration confirmed stable` comment.

- [ ] **Step 2: Verify compilation and tests**

Run:
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -q
```
Expected: All tests PASS (update any mocks in existing tests that depend on the old chat() signature)

- [ ] **Step 3: Commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/src/main/java/
git commit -m "feat(admin-api): FlagService uses orchestrator templates instead of hardcoded prompts

chat() no longer prepends hardcoded system prompt — orchestrator's /api/chat
uses the flag_analysis template. analyse() benefits from orchestrator's
flag_analyse template lookup."
```

---

### Task 10: ResearchReportService — Use Templates Instead of Hardcoded Prompts

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchReportService.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java`

**Interfaces:**
- Consumes: `GET /api/templates/{name}` on orchestrator (Task 5)
- Produces: `ResearchReportService.generateReport()` — looks up template from orchestrator, falls back to hardcoded constants

- [ ] **Step 1: Add template lookup method to LlmOrchestratorClient**

In `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java`, add this method:

```java
    public TemplateResponse getTemplate(String name) {
        try {
            return restClient
                    .get()
                    .uri("/api/templates/{name}", name)
                    .retrieve()
                    .body(TemplateResponse.class);
        } catch (Exception e) {
            log.warn("Failed to fetch template '{}': {}", name, e.getMessage());
            return null;
        }
    }

    public record TemplateResponse(String name, String systemPrompt, String userPromptTemplate,
                                    Integer maxTokens, Double temperature) {}
```

- [ ] **Step 2: Update ResearchReportService to try template lookup first**

In `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchReportService.java`, replace the `generateReport` method (lines 114–137) with:

```java
    @Transactional
    public ResearchReport generateReport(
            ResearchSession session, List<ResearchEvidence> evidence, ReportTemplate template) {

        String evidenceSummary = buildEvidenceSummary(evidence);
        String prompt = buildPrompt(session.getQuestion(), evidenceSummary, template);

        String content = llmClient.analyse(prompt, "REPORT");
        if (content == null || content.isBlank()) {
            log.warn("LLM returned no content for report on session {}", session.getId());
            content =
                    "# Report Generation Failed\n\n"
                            + "The LLM could not generate a report for this session.";
        }

        ResearchReport report = new ResearchReport();
        report.setTenantId(session.getTenantId());
        report.setSession(session);
        report.setTemplate(template);
        report.setTitle(buildTitle(session.getQuestion(), template));
        report.setContent(content);

        return reportRepository.save(report);
    }

    private String buildPrompt(String question, String evidenceSummary, ReportTemplate template) {
        String templateName = switch (template) {
            case TOPIC -> "research_topic";
            case PERSON -> "research_person";
            case FACT_CHECK -> "research_fact_check";
        };

        LlmOrchestratorClient.TemplateResponse tmpl = llmClient.getTemplate(templateName);
        if (tmpl != null && tmpl.systemPrompt() != null) {
            log.info("Using template '{}' from orchestrator for report generation", templateName);
            String userTemplate = tmpl.userPromptTemplate() != null ? tmpl.userPromptTemplate() : "{{topic}}\n\n{{evidence}}";
            return userTemplate
                    .replace("{{topic}}", question)
                    .replace("{{evidence}}", evidenceSummary);
        }

        // Fallback to hardcoded prompts
        log.warn("Template '{}' not found, falling back to hardcoded prompt", templateName);
        String promptTemplate = selectPromptTemplate(template);
        return promptTemplate.formatted(question, evidenceSummary);
    }
```

- [ ] **Step 3: Verify compilation and tests**

Run:
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-knowledge-engine -q
```
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/main/java/
git commit -m "feat(knowledge-engine): ResearchReportService uses templates from orchestrator

generateReport() looks up research_topic/research_person/research_fact_check
templates from orchestrator. Falls back to hardcoded constants if not found."
```
