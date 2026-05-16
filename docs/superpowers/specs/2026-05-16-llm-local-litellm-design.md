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
