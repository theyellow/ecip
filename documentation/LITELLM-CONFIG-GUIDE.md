# Local LLM Configuration Guide - LiteLLM & opencode

**Last Updated:** 2026-09-20  
**Status:** ✅ Working Configuration  
**Server:** <litellm-host>:4000 (LiteLLM proxy)  
**Strategy:** Single active model (memory constrained)

---

## Quick Start

### 1. Verify LiteLLM is Running

```bash
curl -s "http://<litellm-host>:4000/models" \
  -H "Authorization: Bearer <your-proxy-api-key>" | jq '.'
```

Expected: List of available models

### 2. Available Models (Single-Model Strategy)

Due to M2 Ultra memory constraints, **only one model is loaded at a time**. Models available in the registry:

| Model ID | Type | Purpose |
|----------|------|---------|
| `qwen3.5-122b` | **PRIMARY** | Architecture, planning, complex dev, UI/UX, review |
| `qwen3.8-27b-mtp` | **BACKUP** | Daily development, easier tasks, lighter workloads |
| `qwen3.8-27b` | Available | Alternative 27B dense model |
| `qwen3.6-35b` | Available | General purpose |
| `frontier-deepseek-r1` | Available | Complex reasoning (when needed) |
| `frontier-llama3.3-instruct` | Available | Llama 3.3 |
| `mistral-truther` | Available | Truthful answers |
| `bge-m3` | Embedding | Vector embeddings |
| `qwen3-4b-extract` | Specialized | Data extraction |
| `qwen3-4b-classify` | Specialized | Classification |

**Current Active Model:** `qwen3.5-122b` (128K context, never unloaded)

### 3. Authentication

- **API Key:** `<your-proxy-api-key>`
- **Authorization Header:** `Bearer <your-proxy-api-key>`
- **No key required for Ollama direct** (localhost only)

---

## Configuration Files

### opencode.json (Local Client)

**Location:** `~/.config/opencode/opencode.json`

**Key Points:**
- **Default model:** `litellm/qwen3.5-122b` (always loaded)
- **All agents use 122B** for consistency and quality
- **No model switching** during sessions (simplifies context management)

### ⚠️ Obsolete: micode.json

**Status:** Archived (2026-09-20)

The old `micode.json` file has been archived because:
- Referenced non-existent models (`worker-qwen3.6-moe`, `standard-qwen3.6-moe`)
- Used obsolete agent naming (`primary`, `planner`, `implementer`, etc.)
- Conflicted with current opencode.json structure
- Unnecessary under single-model strategy

**Backup:** `~/.config/opencode/micode.json.backup` (for reference only)

**Migration:** All agent definitions from micode.json have been consolidated into opencode.json.

### ✅ opencode.json Verification Checklist

| Section | Status | Details |
|---------|--------|---------|
| `$schema` | ✅ | Points to current schema |
| `permission` | ✅ | Bash permissions with psql restrictions |
| `model` (default) | ✅ | `litellm/qwen3.5-122b` |
| `provider.litellm` | ✅ | Correct URL, API key, npm package |
| `models` | ✅ | All 10 models defined with limits |
| `default_agent` | ✅ | `architect` |
| `agent` definitions | ✅ | 5 agents (architect, builder, plan, reactive-specialist, reviewer) |
| `plugin` | ✅ | Empty (no plugins active) |
| `compaction` | ✅ | Auto-enabled, 15 tail turns |

**Status:** ✅ **Complete and verified** - no missing configurations.

```json
{
  "model": "litellm/qwen3.5-122b",
  "provider": {
    "litellm": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "LiteLLM (local)",
      "options": {
        "baseURL": "http://<litellm-host>:4000/v1",
        "apiKey": "<your-proxy-api-key>"
      },
      "models": {
        "qwen3.5-122b": {
          "name": "Qwen3.5 122B A10B (Primary)",
          "limit": {
            "context": 128000,
            "output": 8192
          },
          "reasoning": true,
          "interleaved": { "field": "reasoning_content" },
          "tool_call": true
        },
        "qwen3.8-27b-mtp": {
          "name": "Qwen3.8 27B MTP (Backup)",
          "limit": {
            "context": 128000,
            "output": 8192
          },
          "tool_call": true
        }
      }
    }
  },
  "default_agent": "architect",
  "agent": {
    "architect": {
      "mode": "primary",
      "model": "litellm/qwen3.5-122b"
    },
    "builder": {
      "mode": "subagent",
      "model": "litellm/qwen3.8-27b-mtp"
    },
    "plan": {
      "mode": "subagent",
      "model": "litellm/qwen3.5-122b"
    },
    "reactive-specialist": {
      "mode": "subagent",
      "model": "litellm/qwen3.8-27b-mtp"
    },
    "reviewer": {
      "mode": "subagent",
      "model": "litellm/qwen3.8-27b-mtp"
    }
  }
}
```

**Note:** Agent assignments reflect the *intended* model, but **all agents will use 122B** when it's loaded. The 27B models are only available when explicitly loaded on the server.

### LiteLLM config.yaml (Server)

**Location:** `/path/to/litellm/config.yaml` (on <litellm-host>)

**Single-Model Strategy:**
- **122B:** `keep_alive: "-1"` (always loaded, primary model)
- **27B-MTP:** `keep_alive: "45m"` (loaded on-demand, auto-unload)
- **Other models:** Not in active config (added only when needed)

```yaml
model_list:
  # PRIMARY: Always loaded (M2 Ultra has enough RAM)
  - model_name: qwen3.5-122b
    litellm_params:
      model: ollama_chat/qwen3.5:122b
      api_base: http://localhost:11434
      keep_alive: "-1"  # Never unload - this is your primary model
      num_ctx: 128000
      reasoning_effort: disable

  # BACKUP: Load on-demand for lighter tasks
  - model_name: qwen3.8-27b-mtp
    litellm_params:
      model: ollama_chat/qwen3.8:27b
      api_base: http://localhost:11434
      keep_alive: "45m"  # Auto-unload after 45 min of inactivity
      num_ctx: 128000

litellm_settings:
  drop_params: true
  set_verbose: false

general_settings:
  master_key: <your-proxy-api-key>
```

**Note:** Only 2 models in active config. Other models (35b, 27b, deepseek, etc.) can be added temporarily by editing config.yaml and restarting LiteLLM when needed.

---

## Model Selection Guide

### Single-Model Strategy (Current Setup)

**Primary Model:** `qwen3.5-122b` (you!)

This is the **only model used** across all agents. Benefits:
- ✨ **No model switching** - instant agent changes
- 🧠 **MoE architecture** - you internally route to appropriate experts
- 📋 **Consistent context** - all agents share the same context window
- ⚡ **No reload delays** - 27B never needs to load
- 💪 **Best quality** - 122B outperforms 27B on all task types

**All Agents Use 122B:**

| Agent | Model | Purpose |
|-------|-------|---------|
| `architect` | `qwen3.5-122b` | Architecture, planning, complex reasoning |
| `plan` | `qwen3.5-122b` | Orchestration, task classification |
| `builder` | `qwen3.5-122b` | Implementation, coding, refactoring |
| `reviewer` | `qwen3.5-122b` | Code review, production-readiness |
| `reactive-specialist` | `qwen3.5-122b` | WebFlux, Kafka, reactive patterns |

**Subagents:** When you create subagents within any agent, they also use 122B automatically. This means:
- **architect → subagent:** Still 122B (specialized architecture work)
- **builder → subagent:** Still 122B (specialized implementation tasks)
- **No context fragmentation** - everything runs in the same 128K context

### When to Use 27B (Rare Cases)

The 27B model is available but **not actively used** in normal workflow:
- **Manual sessions:** If you manually start a new opencode session with 27B
- **Batch processing:** Processing many simple tasks separately
- **Memory constraints:** If you need to free up 122B's RAM temporarily

For **99% of use cases**, 122B is the right choice.

### For German Language

All Qwen3 models support German well. For best results:
- Use system prompt in German
- Set `num_ctx: 128000` for long contexts
- Prefer 122B for nuanced German (better language understanding)

---

## Troubleshooting

### Model Not Found (404)

```
{"error": "Model not found: qwen3.5-122b"}
```

**Fix:**
1. Check LiteLLM config has the model defined
2. Verify Ollama has the model: `ollama list`
3. Restart LiteLLM: `pkill -f litellm && litellm --config config.yaml`

### Timeout Errors (122B Loading)

```
Request timed out after 60s
```

**Fix:**
1. Check if model is loaded: `curl http://localhost:11434/api/ps`
2. If 122B is swapped out, it takes ~2-3 minutes to load
3. **Prevention:** Keep `keep_alive: "-1"` to prevent unloading

**Note:** With `keep_alive: "-1"`, the 122B model should never unload unless you explicitly restart LiteLLM or Ollama.

### Empty Responses

If model returns thinking blocks:

```json
{
  "choices": [{
    "message": {
      "content": "<think>...</think>{...}"
    }
  }]
}
```

**Fix:** Add to litellm_params: `reasoning_effort: disable`

### Connection Refused

```
ECONNREFUSED <litellm-host>:4000
```

**Fix:**
1. Check LiteLLM is running: `ps aux | grep litellm`
2. Check port: `netstat -tlnp | grep 4000`
3. Check firewall: `ufw status`

### "I'm the wrong model!"

If you're running a task and realize you should be 27B:

```
You're acting as 122B but this is simple CRUD work
```

**Fix:**
1. User manually switches to 27B in opencode.json
2. Or loads 27B on server and restarts LiteLLM
3. Or creates a new session with different model

---

## Advanced Configuration

### Keepalive Options (Single-Model Strategy)

| Model | Value | Behavior | Rationale |
|-------|-------|----------|-----------|
| **122B (Primary)** | `"-1"` | Never unload | Always available for complex work |
| **27B-MTP (Backup)** | `"45m"` | 45 min idle | Auto-cleanup for on-demand use |
| **Other models** | `"15m"` | 15 min idle | Temporary models, quick cleanup |

**Why this works:**
- M2 Ultra has enough RAM for 122B permanently loaded
- 27B is loaded only when needed, auto-unloads after 45 min
- No memory pressure, no swapping, no context loss

### Context Window Settings

```yaml
num_ctx: 128000  # Default for Qwen3
num_ctx: 262144  # Extended context (slow)
num_ctx: 32000   # Memory constrained
```

### Temperature Settings

| Task | Temperature |
|------|-------------|
| Code generation | 0.3 - 0.7 |
| Reasoning | 0.1 - 0.3 |
| Creative writing | 0.7 - 1.0 |
| Classification | 0.0 |

---

## eCIP Integration

### LlmProviderConfig Entity

The eCIP system stores LiteLLM configuration in the database:

```java
@Entity
public class LlmProviderConfig {
    private String name;           // "local-litellm"
    private String baseUrl;        // "http://<litellm-host>:4000"
    private String apiKey;         // "<your-proxy-api-key>"
    private Boolean active;        // true/false
}
```

### Admin UI Configuration

1. Navigate to **AI Config** → **LLM Provider** tab
2. Add new provider:
   - Name: `local-litellm`
   - Base URL: `http://<litellm-host>:4000`
   - API Key: `<your-proxy-api-key>`
   - Active: ✓
3. Click **Test** → Should show model list
4. Save and activate

### Model Configuration in Database

Update `model_configs` table:

```sql
UPDATE model_configs 
SET provider = 'litellm',
    model_name = 'qwen3.5-122b',
    active = true
WHERE task_type = 'RESPONSE';
```

---

## Migration from Old Config

### Old Files (Deprecated)

- `config.yaml (on the proxy machine)` - old LiteLLM config
- `docs/superpowers/plans/2026-05-16-llm-local-litellm.md` - Old plan

### Migration Steps

1. **Update opencode.json:**
   ```bash
   # Change from:
   "baseURL": "http://<old-ollama-host>:11434/v1"
   # To:
   "baseURL": "http://<litellm-host>:4000/v1"
   ```

2. **Update LiteLLM config.yaml:**
   - Add all models from the "Available Models" table
   - Set `keep_alive` appropriately
   - Set `master_key: <your-proxy-api-key>`

3. **Update eCIP database:**
   ```sql
   -- Deactivate old providers
   UPDATE llm_provider_configs SET active = false WHERE active = true;
   
   -- Add new provider
   INSERT INTO llm_provider_configs 
   (name, base_url, api_key, active, created_at, updated_at)
   VALUES 
   ('local-litellm', 'http://<litellm-host>:4000', '<your-proxy-api-key>', true, now(), now());
   ```

---

## Performance Tips

### Memory Optimization

```yaml
# Load only required models
- model_name: qwen3.5-122b
  litellm_params:
    keep_alive: "-1"  # Always loaded

- model_name: qwen3-4b-extract
  litellm_params:
    keep_alive: "15m"  # Unload after 15 min
```

### Speed Optimization

```yaml
# Use MTP for faster generation
- model_name: qwen3.8-27b-mtp
  litellm_params:
    num_predict: 2048
    temperature: 0.7
```

### Batch Processing

For embedding large documents:
```bash
# Pre-warm model
curl -X POST http://<litellm-host>:4000/v1/chat/completions \
  -H "Authorization: Bearer <your-proxy-api-key>" \
  -d '{"model": "bge-m3", "messages": [{"role": "user", "content": "warmup"}]}'
```

---

## Related Documentation

- `LITELLM_KEEPALIVE_CONFIG.md` - historical keepalive documentation (obsolete)
- `docs/superpowers/plans/2026-05-16-llm-local-litellm.md` - Original plan (deprecated)
- `docs/superpowers/specs/2026-05-16-llm-local-litellm-design.md` - Design spec

---

## Support & Troubleshooting

### Common Issues Matrix

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| 404 Model not found | Model not in config | Add to config.yaml |
| 503 Service unavailable | LiteLLM down | Restart LiteLLM |
| Timeout | Model loading | Use `keep_alive: "-1"` |
| Empty response | Thinking blocks | Set `reasoning_effort: disable` |
| 500 Internal error | Ollama down | Check `ollama list` |

### Debug Mode

Enable detailed logging:
```bash
litellm --config config.yaml --detailed_debug --port 4000
```

### Logs

LiteLLM logs to stdout. For systemd:
```bash
journalctl -u litellm -f
```

---

**Maintained by:** eCIP Team  
**Contact:** See repository README
