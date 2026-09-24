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
5. **Reactivates returning models**: inactive `local-litellm` rows whose
   `model_name` is served again are set active. The proxy's model list is the
   source of truth for `local-litellm` rows: this **also reactivates a row an
   operator switched off by hand** in the Admin UI, on the next restart. To keep
   a model off permanently, remove it from the LiteLLM proxy instead.
6. **Never touches**: rows of other providers (e.g. `anthropic`) or any manual
   task_type / priority / routing-key assignment.

### Failure behaviour

The sync is best-effort and **never fails startup**. Any failure — proxy
unreachable or slower than the timeout, the `local-litellm` row's `api_key`
not decryptable (legacy plaintext or a wrong `EMCIP_SECRET_KEY`), a database
error — is logged as a single WARN and the service boots with the existing
`model_configs` state. This matters because the in-product credential repair
paths (PRs #241/#243) need the service running to fix exactly those cases.

If the proxy answers with an empty model list, the sync is skipped entirely
rather than deactivating every row (an empty list almost always means the proxy
is still loading).

### Configuration

The sync reads its connection details from the database — the
`llm_provider_configs` row with `name = 'local-litellm'`:

| Column     | Example (placeholder)                   |
|------------|-----------------------------------------|
| `name`     | `local-litellm`                         |
| `base_url` | `http://<litellm-host>:4000`            |
| `api_key`  | `<your-proxy-api-key>` (encrypted at rest) |

The HTTP call to the proxy uses one connect/read timeout, `emcip.llm.model-sync.timeout`
(default `10s`; env var `EMCIP_LLM_MODELSYNC_TIMEOUT` — Spring drops the dash). There are no other settings.

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
✅ **Safe deactivation:** Dead models marked inactive (not deleted), reactivated when they return  
✅ **Preserves config:** Manual task assignments kept  
✅ **Startup validation:** Logs summary of changes

### Logs Example

```
INFO  ModelConfigSyncService - Model sync: create qwen3.5-122b (qwen3.5-122b)
INFO  ModelConfigSyncService - Model sync: deactivate standard-qwen3.6-moe (standard-qwen3.6-moe)
INFO  ModelConfigSyncService - Model sync: 11 served, 7 created, 0 reactivated, 1 deactivated

# proxy down / key undecryptable / timeout - startup continues:
WARN  ModelConfigSyncService - Model sync from LiteLLM proxy skipped, keeping existing configuration: <cause>
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
3. Stale `local-litellm` rows deactivated, returning ones reactivated, new models created
4. No manual intervention needed

## Related

- **LO-LIQUIBASE:** https://github.com/theyellow/ecip/blob/main/docs/superpowers/BACKLOG.md#70-lo-liquibase
- **AI Config UI:** Already has dynamic model picker via `/api/ai/provider-config/models`
