# Prompt Template System & AI Config Improvements

## Problem

1. **Hardcoded prompts bypass the `prompt_templates` table.** Four services contain hardcoded LLM prompts that operators cannot edit: `FlagService` (admin-api), `OrchestratorController` (llm-orchestrator), and `ResearchReportService` (knowledge-engine). Meanwhile, the `prompt_templates` table and AI Config UI already exist but only the Kafka-triggered automated pipeline uses them.

2. **Model selection is disconnected from templates.** `PromptTemplate` has `modelProvider`/`modelName` fields but they are dead — never used in any LLM call. Instead, code calls `selectModelForTask("GENERAL")` which queries `ModelConfig` by `taskType`. The template should own the model choice so operators can configure prompt + model together in one place.

3. **AI analysis cut-off and empty responses.** Flag analysis hardcodes `maxTokens=1024`, truncating longer responses. Empty responses stem from LLM cold-start timeouts cascading through a circuit breaker that opens too aggressively for LLM workloads (30s wait-open, 10s slow-call threshold).

4. **Two divergent flag analysis paths.** `FlagService.buildChatSystemPrompt()` and `OrchestratorController./api/analyse` both analyse flags but build prompts differently. This is tech debt — they should share one template.

5. **AI Config page lacks template governance.** Template names are free text. No distinction between built-in system templates (that the code depends on) and operator-created custom templates. System templates can be accidentally deleted or renamed, breaking functionality.

6. **Temperature hardcoded per-call.** Different models have different optimal temperatures. The system should allow omitting temperature so LiteLLM defers to the model's default.

## Architecture

Four independent change areas:

- **Database + seed data**: Replace dead model fields with FK to `model_configs`, add `system` column, make `temperature` nullable, seed all system templates
- **Backend wiring**: Template becomes single source of truth for prompt + model + params. Replace hardcoded prompts with template lookups by name. Template's `modelConfig` reference drives model selection instead of `selectModelForTask()`.
- **Frontend**: System badge + lock behavior in AI Config template table; model dropdown populated from AI Models table; nullable temperature UX
- **Circuit breaker tuning**: Split embed vs analyse instances, lenient thresholds for LLM calls, add retry

### Corrected flow

```
Code needs LLM call
  → look up template by name (e.g. "flag_analysis")
  → template provides: systemPrompt, maxTokens, temperature, AND modelConfig
  → template.modelConfig.modelName → sent to LiteLLM
  → if template.modelConfig is null → fall back to selectModelForTask() (backward compat)
```

The template is the **single source of truth** for prompt text, parameters, and model selection.

## Database Changes

### Liquibase migration: schema changes

Changeset in a new file (`013-prompt-template-system-and-model-ref.xml`):

**1. Add `system` boolean column:**

```xml
<addColumn tableName="prompt_templates">
    <column name="system" type="BOOLEAN" defaultValueBoolean="false">
        <constraints nullable="false"/>
    </column>
</addColumn>
```

**2. Make `temperature` nullable:**

```xml
<dropNotNullConstraint tableName="prompt_templates" columnName="temperature" columnDataType="DOUBLE"/>
```

**3. Drop dead `model_provider` and `model_name` columns:**

```xml
<dropColumn tableName="prompt_templates" columnName="model_provider"/>
<dropColumn tableName="prompt_templates" columnName="model_name"/>
```

**4. Add `model_config_id` FK to `model_configs`:**

```xml
<addColumn tableName="prompt_templates">
    <column name="model_config_id" type="BIGINT">
        <constraints nullable="true"/>
    </column>
</addColumn>
<addForeignKeyConstraint
    baseTableName="prompt_templates" baseColumnNames="model_config_id"
    referencedTableName="model_configs" referencedColumnNames="id"
    constraintName="fk_template_model_config"/>
```

`model_config_id` is **nullable** — if null, code falls back to `selectModelForTask()` with the template's old taskType behavior (graceful migration).

### Liquibase migration: seed system templates

Changeset in a new file (`014-seed-system-templates.xml`):

**1. Seed five new system templates** — all with `system=true`, `max_tokens=8192`, `temperature=NULL`:

| Name | Description | System Prompt Source |
|------|-------------|---------------------|
| `flag_analysis` | System prompt for Decisions AI chat | Copied from `FlagService.buildChatSystemPrompt()` |
| `flag_analyse` | System prompt for single-shot flag analysis | Same text as `flag_analysis` (aligned, mergeable later) |
| `research_topic` | Topic research report generation | Copied from `ResearchReportService.TOPIC_PROMPT_TEMPLATE` |
| `research_person` | Person profile research report | Copied from `ResearchReportService.PERSON_PROMPT_TEMPLATE` |
| `research_fact_check` | Fact-check verdict report | Copied from `ResearchReportService.FACT_CHECK_PROMPT_TEMPLATE` |

Each seeded with `model_config_id` pointing to the appropriate ModelConfig entry (e.g. the GENERAL model for analysis templates).

**2. Update existing 5 templates** — set `system=true`, `max_tokens=8192`, `temperature=NULL`, and set `model_config_id` to the ModelConfig they were previously using via taskType lookup:

```xml
<update tableName="prompt_templates">
    <column name="system" valueBoolean="true"/>
    <column name="max_tokens" valueNumeric="8192"/>
    <column name="temperature"/>  <!-- NULL -->
    <where>name IN ('auto_response', 'escalation_summary', 'command_validation',
                    'moderation_check', 'knowledge_extraction')</where>
</update>
```

### Full system template inventory (10 total)

| Name | Origin | Used by |
|------|--------|---------|
| `flag_analysis` | NEW | FlagService.chat() — Decisions AI chat |
| `flag_analyse` | NEW | OrchestratorController /api/analyse — single-shot analysis |
| `research_topic` | NEW | ResearchReportService — topic reports |
| `research_person` | NEW | ResearchReportService — person profiles |
| `research_fact_check` | NEW | ResearchReportService — fact-check verdicts |
| `auto_response` | EXISTING | PolicyActionService — auto-reply to messages |
| `escalation_summary` | EXISTING | PolicyActionService — escalation summaries |
| `command_validation` | EXISTING | PolicyActionService — command safety check |
| `moderation_check` | EXISTING | PolicyActionService — content moderation |
| `knowledge_extraction` | EXISTING | KnowledgeExtractionService — entity extraction |

## Backend Wiring

### PromptTemplate entity

- Remove `modelProvider` and `modelName` fields (dead code, columns dropped in migration)
- Add `modelConfig` as a `@ManyToOne @JoinColumn(name = "model_config_id")` relationship to `ModelConfig` (nullable)
- Add `system` boolean field, `@Column(nullable = false)`, default `false`
- Make `temperature` field nullable (`Double` not `double`), remove default

### Template-driven model resolution

New flow when code needs to call the LLM:

1. Look up template by name (e.g. `flag_analysis`)
2. If `template.getModelConfig() != null` → use that model's `modelName` for LiteLLM
3. If `template.getModelConfig() == null` → fall back to `selectModelForTask()` (backward compat)

This replaces the current pattern where code calls `selectModelForTask("GENERAL")` independently of the template.

### OpenAiCompatibleLlmClient

In `call()` and `chat()`: change `double temperature` to `Double temperature`. If null, omit the `temperature` key from the request body map entirely. LiteLLM will use the model's configured default.

No changes to `embed()` — embedding models don't use temperature.

### LlmCallService

- `callForTask()`: check if the template has a `modelConfig`. If yes, use `template.getModelConfig().getModelName()` instead of calling `selectModelForTask()`. Pass `template.getTemperature()` (nullable) through to `llmClient.call()`.

### OrchestratorController

- Template CRUD endpoints: prevent delete when `system=true` (return 400). Prevent renaming `name` field on system templates.
- `/api/analyse` endpoint: look up `flag_analyse` template by name. Use its `systemPrompt`, `maxTokens`, `temperature`, and `modelConfig`. Fall back to current hardcoded prompt if template not found.
- `/api/chat` endpoint: look up `flag_analysis` template. Same pattern.
- `/api/embed`: unchanged (embedding uses a different path).

### Template lookup endpoint

Add `GET /api/templates/{name}` to OrchestratorController — returns the template by name (including resolved model info), or 404. Used by admin-api and knowledge-engine for cross-service template lookups.

### FlagService (admin-api)

- `chat()`: fetch `flag_analysis` template from orchestrator via `GET /api/templates/flag_analysis`. Use its `systemPrompt` as the system message. Use its `maxTokens` and `temperature` for the LLM call. The model is already resolved on the orchestrator side. Fall back to `buildChatSystemPrompt()` if template not found.
- `analyse()`: fetch `flag_analyse` template the same way. Fall back to `buildAnalysisPrompt()` if not found.
- Both methods: remove hardcoded `maxTokens=1024` and `temperature=0.3`.

### ResearchReportService (knowledge-engine)

- `generateReport()`: look up the appropriate template (`research_topic`, `research_person`, or `research_fact_check`) from the orchestrator via `GET /api/templates/{name}`. Use its `systemPrompt` to build the LLM prompt. Fall back to the hardcoded `*_PROMPT_TEMPLATE` constants if lookup fails.

## Circuit Breaker Tuning

### Knowledge Engine — split into two instances

Currently one `llm-orchestrator` circuit breaker handles both embed and analyse calls. Split into:

**`llm-orchestrator-embed`** (strict — bge-m3 is fast and local):

```yaml
slow-call-duration-threshold: 10s
slow-call-rate-threshold: 80
failure-rate-threshold: 50
wait-duration-in-open-state: 30s
permitted-number-of-calls-in-half-open-state: 3
```

**`llm-orchestrator-analyse`** (lenient — LLM models cold-start on M2 Ultra):

```yaml
slow-call-duration-threshold: 180s
slow-call-rate-threshold: 90
failure-rate-threshold: 70
wait-duration-in-open-state: 120s
permitted-number-of-calls-in-half-open-state: 5
```

The `LlmOrchestratorClient` already uses separate `embedCircuitBreaker()` and `analyseCircuitBreaker()` methods — they just both resolve to the same instance name. Change `analyseCircuitBreaker()` to use `"llm-orchestrator-analyse"` and `embedCircuitBreaker()` to use `"llm-orchestrator-embed"`.

### Retry for analyse/extract calls

Add a single retry with **10-second delay** inside `LlmOrchestratorClient.analyse()` and `LlmOrchestratorClient.extract()` — retry once before letting the failure propagate to the circuit breaker. This absorbs model cold-start without opening the circuit.

### Admin API — orchestrator circuit breaker

Update the admin-api's orchestrator circuit breaker to match the lenient analyse profile:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      orchestrator:
        sliding-window-size: 10
        failure-rate-threshold: 70
        wait-duration-in-open-state: 120s
        permitted-number-of-calls-in-half-open-state: 5
        slow-call-duration-threshold: 180s
        slow-call-rate-threshold: 90
```

## Frontend Changes

### AI Config — Prompt Templates table

- Add a "Type" column showing a "System" badge (lock icon) for system templates, or "Custom" for user-created ones
- System templates: Delete button is hidden
- Clicking a system template row opens the edit modal with the Name field disabled (read-only)

### Edit modal — model field (NEW)

- **Model dropdown**: populated from the AI Models table (ModelConfig entries). Shows model display name, selected by `model_config_id`.
- **Nullable**: if no model selected, the system falls back to taskType-based selection (backward compat). Dropdown shows a "Default (auto)" option for this.
- System and custom templates both get the dropdown.

### Edit modal — temperature field

- Allow clearing the field. If empty, show placeholder text "Model default" to indicate it will be omitted from the API call and the model picks its own temperature.

### Edit modal — system template behavior

- **System template**: Name input is disabled (read-only), showing the fixed name
- **Custom template** (add/edit): Name stays free text as today

### No layout changes

Single table, no new sections. Just the badge, the lock behavior on system templates, the model dropdown, and the nullable temperature UX.

## Backlog Items (out of scope)

- **Merge `flag_analysis` and `flag_analyse`** into a single template once both paths are proven stable
- **Wire custom templates to policy actions** — allow operators to reference a custom template name in policy rule action config (e.g. "when SPAM, use template `spam_response_german`")
- **KEYWORD word-boundary matching** — the intent classifier's KEYWORD mode uses substring `contains()`, causing false positives (e.g. "history" matches keyword "is"). Should be changed to word-boundary matching.
- **LLM-based intent classification** — wire qwen3-4b as a fallback classifier for UNKNOWN intents

## Testing

- Unit tests for nullable temperature in `OpenAiCompatibleLlmClient` (omit key from body when null)
- Unit tests for system template protection (prevent delete/rename)
- Unit tests for template lookup fallback (returns hardcoded default when DB template missing)
- Unit tests for template-driven model resolution (template.modelConfig used when present, fallback to selectModelForTask when null)
- Existing `FlagService`, `ResearchReportService` tests updated to mock template lookups
- Frontend: test model dropdown renders ModelConfig entries, test system badge display
- Manual test: edit a system template's model in AI Config, trigger flag analysis, verify the selected model is used
- Manual test: clear temperature field, verify LiteLLM receives no temperature parameter
