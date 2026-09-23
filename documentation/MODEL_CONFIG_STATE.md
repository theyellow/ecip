# Model Configuration Sync Solution

**Date:** 2026-09-21  
**Status:** ✅ Implemented - Dynamic sync at startup

## Problem

Database had mixed model configuration state:
- Some models correct (qwen3-4b-classify, qwen3-4b-extract, bge-m3)
- Some models wrong (qwen3.6:35b should be qwen3.6-35b)
- Some models dead (standard-qwen3.6-moe variants, claude models)
- Missing active models (qwen3.5-122b, qwen3.8-27b-mtp)

**Root cause:** Hardcoded Liquibase migrations vs. dynamic LiteLLM proxy model names.

## Solution: Dynamic Startup Sync

Instead of hardcoding model names in migrations, we now **auto-sync from LiteLLM proxy at startup**.

### How It Works

`ModelConfigSyncService` runs at application startup (order 100):

1. **Locates the `local-litellm` provider row** in `llm_provider_configs`
   (reads `base_url` + `api_key`). If the row is missing or incomplete, sync
   is skipped with a warning and the app starts with the existing DB state.
2. **Fetches available models** from LiteLLM proxy (`/models` endpoint)
3. **Creates missing model_configs** entries — but only for served models that
   no existing `local-litellm` row already references (matched on `model_name`).
   Existing manual routing keys are preserved, never duplicated.
4. **Deactivates stale models**: only active `local-litellm` rows whose
   `model_name` is no longer available on the proxy.
5. **Never touches**: rows of other providers (e.g. `anthropic`) or any manual
   task_type / priority / routing-key assignment.

### Configuration

The sync reads its connection details from the database — the
`llm_provider_configs` row with `name = 'local-litellm'`:

| Column     | Example (placeholder)                   |
|------------|-----------------------------------------|
| `name`     | `local-litellm`                         |
| `base_url` | `http://<litellm-host>:4000`            |
| `api_key`  | `<your-proxy-api-key>` (encrypted at rest) |

No environment variables or `application.yml` settings are involved.

### What Gets Created

For each served model no existing `local-litellm` row references yet, creates
a `model_configs` entry:
- `model_key`: same as model_name (e.g., `qwen3.8-27b-mtp`). If that routing
  key is already in use by another row, the create is skipped with a warning.
- `model_name`: From proxy (e.g., `qwen3.8-27b-mtp`)
- `provider`: The provider config row's name (i.e., `local-litellm`)
- `task_type`: `GENERAL` (default, can be changed manually)
- `active`: `true`
- Default specs: 128k context, 8192 max output, streaming enabled

### Benefits

✅ **No more drift:** Database always matches proxy  
✅ **No manual migrations:** New models auto-created  
✅ **Safe deactivation:** Dead models marked inactive (not deleted)  
✅ **Preserves config:** Manual task assignments kept  
✅ **Startup validation:** Logs summary of changes

### Logs Example

```
INFO  ModelConfigSyncService - Using LiteLLM proxy configuration from database (local-litellm)
INFO  ModelConfigSyncService -   Created: qwen3.5-122b (qwen3.5-122b)
INFO  ModelConfigSyncService -   Deactivated: standard-qwen3.6-moe (no longer on proxy)
INFO  ModelConfigSyncService - Sync summary: 11 served models, 7 created, 4 already configured, 1 deactivated
INFO  ModelConfigSyncService - ✓ Model sync complete: 11 models available on proxy
```

## Current Database State (After Sync)

- One row per served model — either created by the sync or an existing row
  (possibly with a custom routing key) already referencing it.
- `local-litellm` rows referencing models that are no longer served
  (e.g., `standard-qwen3.6-moe*`) are marked inactive, not deleted.
- Rows of other providers (e.g., `anthropic` claude-*) are never touched by
  the sync.

## Future Model Changes

When LiteLLM proxy model names change:
1. Restart llm-orchestrator
2. Sync runs automatically
3. Stale `local-litellm` rows deactivated, new models created
4. No manual intervention needed

## Related

- **LO-LIQUIBASE:** https://github.com/theyellow/ecip/blob/main/docs/superpowers/BACKLOG.md#70-lo-liquibase
- **AI Config UI:** Already has dynamic model picker via `/api/ai/provider-config/models`
