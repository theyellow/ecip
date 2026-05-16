# Documentation & Diagram Update — LiteLLM Integration

**Date:** 2026-05-16
**Scope:** Update all EMCIP documentation and PlantUML diagrams to reflect the LiteLLM integration
(feat/llm-local-litellm). Correct stale/aspirational content to match what is actually implemented.
Create `documentation/POSSIBLE_DEVELOPMENT.md` to preserve unbuilt ideas.

---

## What changed in the codebase (trigger for this update)

| Area | Change |
|---|---|
| `OpenAiCompatibleLlmClient` | Replaces `AnthropicLlmClient`; POSTs to `/v1/chat/completions` on LiteLLM proxy |
| `LlmProviderConfig` entity | New JPA entity + table `llm_provider_configs`; stores proxy URL + optional API key |
| `LlmProviderConfigService` | Active provider lookup; `/v1/models` fetch for connectivity test |
| `OrchestratorController` | 5 new REST endpoints: GET/POST/PUT/DELETE `/api/provider-config` + GET `/api/provider-config/models` |
| `AIProxyController` | 5 new proxy endpoints forwarding to orchestrator |
| Admin UI `AIConfig.jsx` | New "LLM Provider" tab: add/edit/delete provider, Test Connection, model list |
| `ModelModal` | "Pick from proxy" button populates model name from LiteLLM |
| `application.yml` | `anthropic.api-key` removed |
| Model seeds | Updated to Qwen3-30B-A3B (response/summary) and Qwen3-14B (validation/moderation) |
| New MODERATION task type | `qwen3-14b-moderation` model config + `moderation_check` prompt template |

---

## Diagram changes

### Full rewrites

#### `c3-llm-orchestrator.puml`

Replace 14 aspirational components with the 6 that actually exist:

| Component | Role |
|---|---|
| `PolicyDecisionEventConsumer` | Kafka `@KafkaListener` on `policies.decisions` |
| `OrchestratorController` | REST: models, templates, costs, provider-config CRUD + models endpoint |
| `LlmCallService` | Orchestrates: select model config → render template → call client → log cost |
| `LlmOrchestratorService` | ModelConfig + PromptTemplate management (select, save, render) |
| `LlmProviderConfigService` | Active provider lookup; `fetchAvailableModels` via GET `/v1/models` |
| `OpenAiCompatibleLlmClient` | HTTP RestClient POST `/v1/chat/completions`; reads provider URL at call time |

External system: **LiteLLM Proxy** (replaces MiniMax API + Claude API externals).
DB tables: `model_configs`, `prompt_templates`, `model_cost_logs`, `llm_provider_configs`.

#### `sequence-llm-orchestration.puml`

Replace the cache-check / routing-strategy / validator flow with the real flow:

```
Kafka policies.decisions
  → PolicyDecisionEventConsumer
  → LlmCallService.callForTask(taskType, templateName, userContent)
    → LlmOrchestratorService.selectModelForTask(taskType) → ModelConfig
    → LlmOrchestratorService.getPromptTemplate(templateName) → PromptTemplate
    → LlmOrchestratorService.renderPromptTemplate(template, vars) → rendered string
    → LlmProviderConfigService.getActiveProvider() → LlmProviderConfig (baseUrl, apiKey)
    → OpenAiCompatibleLlmClient.call(model, systemPrompt, userContent, maxTokens, temp)
      → POST {baseUrl}/v1/chat/completions
      ← choices[0].message.content + usage tokens
    → CostTrackingService.logSuccessfulCall(...)
  → ResponseGeneratedEvent → Kafka responses.generated
```

---

### Targeted updates (LiteLLM-specific)

| File | Change |
|---|---|
| `c1-context.puml` | External system label: "MiniMax-2.7, Claude, etc." → "LiteLLM proxy (OpenAI-compatible, local)" |
| `c2-container.puml` | `llm_orchestrator` description: "Model routing (MiniMax/Claude)" → "OpenAI-compatible LLM calls via LiteLLM proxy, runtime-configurable URL"; external `llm_providers` label updated |
| `deployment-local-docker.puml` | Remove `minimax_api` + `claude_api` external systems; add `litellm_proxy` as optional external; remove `ANTHROPIC_API_KEY` from env-var note; update `llm` profile note to say provider URL is set via Admin UI |
| `sequence-full-message-lifecycle.puml` | LLM phase: remove "Select MiniMax or Claude" comment; replace with provider config lookup comment |
| `sequence-message-flow.puml` | "Route to MiniMax/Claude" → "Call via LiteLLM proxy (URL from DB)" |
| `c4-event-flow.puml` | `ModelType` enum: replace `MINIMAX_2_7 / CLAUDE` with `LITELLM / OTHER` |
| `dataflow-audit-trail.puml` | Cost tracking note: remove per-model rates ($0.001/$0.008 MiniMax/Claude) |

---

### Minor updates (stale component names)

| File | Change |
|---|---|
| `c3-admin-api.puml` | Add `AIProxyController` component with provider-config proxy endpoints (list, create, update, delete, models) |
| `c4-kafka-consumers.puml` | `LlmOrchestrationService`: remove `ModelRouter` field; replace with `LlmCallService` + `LlmProviderConfigService` dependencies |
| `c3-component.puml` | LLM boundary: replace `Model Router` component with `LlmCallService`; add `LlmProviderConfigService` |
| `c4-code.puml` | LLM boundary: replace `ModelRouter` with `LlmCallService` |

---

### No change (confirmed current)

`c3-policy-engine.puml`, `c3-tdlib-adapter.puml`, `c4-policy-domain.puml`,
`sequence-error-handling.puml`, `sequence-admin-auth.puml`, `sequence-policy-evaluation.puml`,
`sequence-tenant-propagation.puml`, `dataflow-context-enrichment.puml`

Recorded in `docs/superpowers/BACKLOG.md` → Documentation Audit section.

---

## Text documentation changes

### `architecture-guide.adoc`

- Line 54: "Claude (Anthropic), OpenAI, and local Ollama models" → "local models via LiteLLM proxy (OpenAI-compatible)"
- LLM Orchestrator component section: rewrite descriptive text to match updated `c3-llm-orchestrator.puml`

### `developer-guide.adoc`

- Module table `emcip-llm-orchestrator` description: "LLM provider routing (Claude, OpenAI, Ollama)" → "LLM provider routing via LiteLLM proxy (OpenAI-compatible); provider URL runtime-configurable via Admin UI"
- Remove all `ANTHROPIC_API_KEY` references (env var setup, native build sections)

### `operations-guide.adoc`

- Kubernetes secrets: remove `anthropic-api-key` from `kubectl create secret` example; add note that LLM provider URL is stored in DB and configured via Admin UI → AI Config → LLM Provider
- AI Config section: add "LLM Provider" subsection documenting the new tab

### `user-guide.adoc`

- AI Config section: add "LLM Provider" subsection:
  - Add / edit / delete provider entries (Name, Base URL, API Key optional)
  - Test Connection — calls `/v1/models`, shows reachable badge + model list
  - Active flag — only one provider active at a time
  - Model picker — "Pick from proxy" in ModelModal populates model name from running proxy

### `docker-compose-guide.adoc`

- `.env` section: remove `ANTHROPIC_API_KEY=sk-ant-...` line; replace with comment: `# LLM provider URL is configured at runtime via Admin UI → AI Config → LLM Provider`
- `llm` profile description: remove "Requires ANTHROPIC_API_KEY" note

---

## New file: `documentation/POSSIBLE_DEVELOPMENT.md`

Captures all aspirational/unimplemented ideas found across diagrams and documentation.
**Not a backlog** — no sizes or priorities. Raw ideas only, to be sorted in a later step.

### Sections

**LLM routing & multi-model**
- Multi-model routing strategy: intent-based (GREETING → cheap, REPORT → capable), cost-based (approaching budget → cheaper model), load balancing
- MiniMax-2.7 direct client (Chinese/English, fast, cost-effective)
- Claude/Anthropic direct client (complex reasoning, safety-focused)
- LLM Client Factory with connection pooling and timeout management
- OpenAI direct integration (beyond LiteLLM proxy)
- Ollama direct integration (local models without proxy)

**LLM quality & safety**
- Response Cache: Caffeine + Redis, semantic similarity matching, TTL-based expiration, target 30% cache hit rate
- Response Validator: content safety check, format verification, length check
- Retry with fallback model on validation failure (max 3 retries, then escalate)
- Budget enforcement: per-tenant token/cost tracking with alerts at 80% and 95% thresholds

**Rule engine**
- Drools or easy-rules based rule evaluation (currently plain Java if/else)
- Escalation Manager: priority queuing, route to human review admin queue
- Decision Cache (Caffeine) to reduce DB load on repeated identical inputs

**Kafka infrastructure**
- `AbstractKafkaConsumer` base class: common deserialization, metrics recording, correlation ID tracking, error handling
- `RetryableKafkaConsumer` wrapper: exponential backoff (1s/2s/4s), configurable max retries
- `DeadLetterQueue.retryFromDLQ()`: automated replay of failed messages after fix deployment
- Per-consumer Prometheus metrics

**Audit & compliance**
- Cryptographic hash chain for tamper detection (each event hashes previous)
- WORM (Write Once Read Many) audit log immutability
- Configurable retention tiers (7-year default)
- CSV / JSON / PDF export for regulatory requests
- SIEM integration (Splunk, ELK stack)
- AlertManager integration for threshold-based notifications

**Multi-tenancy**
- Query-level tenant isolation (cross-tenant data leak currently possible — also in Backlog #5)

**Moderation**
- ML toxicity detection via OpenNLP or Perspective API (also in Backlog #8)

---

## Implementation approach

Sequential execution, one task per logical group, with explicit verification step after each.

### Tasks

1. **Full rewrite — `c3-llm-orchestrator.puml`**
2. **Full rewrite — `sequence-llm-orchestration.puml`**
3. **Targeted updates — 7 diagram files** (c1, c2, deployment-local, seq-full-lifecycle, seq-message-flow, c4-event-flow, dataflow-audit-trail)
4. **Minor updates — 4 diagram files** (c3-admin-api, c4-kafka-consumers, c3-component, c4-code)
5. **Text doc updates — 5 adoc files** (architecture-guide, developer-guide, operations-guide, user-guide, docker-compose-guide)
6. **Create `documentation/POSSIBLE_DEVELOPMENT.md`**
7. **Spotless check + commit all changes**
