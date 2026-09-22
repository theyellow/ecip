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

1. **Fetches available models** from LiteLLM proxy (`/models` endpoint)
2. **Creates missing model_configs** entries for each available model
3. **Updates existing entries** if model names changed
4. **Deactivates dead models** no longer available on proxy
5. **Preserves manual task assignments** (task_type, priority, etc.)

### Configuration

Environment variables (optional - has sensible defaults):
```bash
LITELLM_PROXY_URL=http://192.168.23.232:4000
LITELLM_PROXY_API_KEY=sk-local-dev
```

### What Gets Created

For each model on proxy, creates a `model_configs` entry:
- `model_key`: Generated from model_name (e.g., `qwen3.8-27b-mtp`)
- `model_name`: From proxy (e.g., `qwen3.8-27b-mtp`)
- `provider`: `litellm`
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
INFO  ModelConfigSyncService -   Created: qwen3.5-122b (qwen3.5-122b)
INFO  ModelConfigSyncService -   Created: qwen3.8-27b-mtp (qwen3.8-27b-mtp)
INFO  ModelConfigSyncService -   Deactivated: standard-qwen3.6-moe (no longer on proxy)
INFO  ModelConfigSyncService - Sync summary: 2 created, 0 updated, 5 unchanged, 7 deactivated
INFO  ModelConfigSyncService - ✓ Model sync complete: 11 models available on proxy
```

## Current Database State (After Sync)

Will contain only models available on LiteLLM proxy:
- qwen3.5-122b
- qwen3.6-35b
- qwen3.8-27b-mtp
- qwen3.8-27b
- qwen3.8-27b-free
- qwen3-4b-classify
- qwen3-4b-extract
- bge-m3
- frontier-deepseek-r1
- frontier-llama3.3-instruct
- mistral-truther

Dead models (standard-qwen3.6-moe*, claude-*) will be deactivated.

## Future Model Changes

When LiteLLM proxy model names change:
1. Restart llm-orchestrator
2. Sync runs automatically
3. Old models deactivated, new models created
4. No manual intervention needed

## Related

- **LO-LIQUIBASE:** https://github.com/theyellow/ecip/blob/main/docs/superpowers/BACKLOG.md#70-lo-liquibase
- **AI Config UI:** Already has dynamic model picker via `/api/ai/provider-config/models`
