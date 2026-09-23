# ADR-001: Single-Model Strategy for Local LLM Setup

**Status:** Accepted  
**Date:** 2026-09-20  
**Authors:** Ben, Qwen3.5-122B  
**Context:** Local LLM infrastructure on M2 Ultra

---

## Context

We operate a local LLM infrastructure using LiteLLM proxy with Ollama backends on an M2 Ultra machine (<litellm-host>). The system serves the opencode client for development tasks including architecture, planning, implementation, and review.

### Constraints

1. **Memory:** M2 Ultra has limited RAM for large models
2. **Context:** Need 128K context for complex development tasks
3. **Performance:** Model loading takes 2-3 minutes for 122B models
4. **Workflow:** Multiple agents (architect, builder, plan, reviewer, reactive-specialist)

### Available Models

- `qwen3.5-122b`: 122B parameter MoE model, 128K context
- `qwen3.8-27b-mtp`: 27B dense model with Multi-Token Prediction
- `qwen3.8-27b`: 27B dense model
- `qwen3.6-35b`: 35B model
- Other specialized models (embeddings, classification, etc.)

### Problem

Previously configured with multiple models loaded simultaneously or with agent-specific model assignments. This caused:

1. **Memory pressure:** Loading multiple 27B+ models exhausted RAM
2. **Context switching:** Different agents using different models lost context
3. **Complexity:** Managing keepalive for multiple models
4. **Unreliability:** Models unloading mid-session due to memory pressure

---

## Decision

**Adopt a single-active-model strategy with 122B for all agents:**

1. **Primary model (`qwen3.5-122b`)** loaded permanently with `keep_alive: "-1"`
2. **All agents use 122B** (architect, builder, plan, reviewer, reactive-specialist)
3. **Subagents also use 122B** - no model switching within sessions
4. **27B-MTP available** in config but not actively used (manual load only)

### Model Roles

| Model | Keepalive | Use Case |
|-------|-----------|----------|
| `qwen3.5-122b` | `-1` (never unload) | **ALL AGENTS** - architecture, planning, implementation, review, reactive systems |
| `qwen3.8-27b-mtp` | `45m` (auto-unload) | Manual sessions only (rare) |

### Rationale

1. **MoE Architecture:** The 122B model is itself a Mixture of Experts, dynamically routing to internal specialists for different tasks (architecture, coding, review, reactive patterns, etc.)

2. **Zero Switching Overhead:** All agents use the same model, so:
   - Instant agent switching (no reload delays)
   - No context fragmentation
   - No keepalive management complexity

3. **Consistent Quality:** 122B outperforms 27B on all task types, including routine implementation work

4. **Subagent Efficiency:** When agents spawn subagents, they remain in the same 128K context with the same model, preserving context and expertise

5. **Simplicity:** Single model = single configuration, single keepalive strategy, no decision fatigue

---

## Consequences

### Positive

✅ **Consistent quality:** All tasks benefit from 122B capabilities  
✅ **No context loss:** Model stays loaded, context preserved across sessions  
✅ **Simplified config:** Single model in active config, easy to maintain  
✅ **Reliable:** No unexpected model switches or unloads  
✅ **MoE benefits:** Internal experts handle different task types automatically

### Negative

❌ **No parallelism:** Can't run 122B and 27B simultaneously  
❌ **Manual switching:** User must manually change model for backup  
❌ **Single point of failure:** If 122B has issues, no automatic fallback  
❌ **Memory dedicated:** 122B always consumes RAM (but M2 Ultra has enough)

### Neutral

🔶 **Session restarts required:** To switch models, restart LiteLLM or wait for 45m timeout  
🔶 **User awareness:** Users must understand which model is active  

---

## Implementation

### LiteLLM config.yaml

```yaml
model_list:
  - model_name: qwen3.5-122b
    litellm_params:
      model: ollama_chat/qwen3.5:122b
      api_base: http://localhost:11434
      keep_alive: "-1"  # Never unload
      num_ctx: 128000

  - model_name: qwen3.8-27b-mtp
    litellm_params:
      model: ollama_chat/qwen3.8:27b
      api_base: http://localhost:11434
      keep_alive: "45m"  # Auto-unload
      num_ctx: 128000
```

### opencode.json

```json
{
  "model": "litellm/qwen3.5-122b",
  "provider": {
    "litellm": {
      "options": {
        "baseURL": "http://<litellm-host>:4000/v1",
        "apiKey": "<your-proxy-api-key>"
      }
    }
  },
  "agent": {
    "architect": { "model": "litellm/qwen3.5-122b" },
    "builder": { "model": "litellm/qwen3.5-122b" },
    "plan": { "model": "litellm/qwen3.5-122b" },
    "reactive-specialist": { "model": "litellm/qwen3.5-122b" },
    "reviewer": { "model": "litellm/qwen3.5-122b" }
  }
}
```

**Note:** All agents use 122B. Subagents automatically inherit the parent agent's model.

---

## Testing

### Verify Keepalive

```bash
# Check model is loaded
curl http://localhost:11434/api/ps

# Make request
curl -X POST http://<litellm-host>:4000/v1/chat/completions \
  -H "Authorization: Bearer <your-proxy-api-key>" \
  -d '{"model": "qwen3.5-122b", "messages": [{"role": "user", "content": "test"}]}'

# Wait 10 minutes, make another request
# Should be instant (model still loaded)
```

### Verify Single Model

```bash
# Check what's loaded
curl http://localhost:11434/api/ps | jq '.'

# Should show only qwen3.5:122b (and optionally qwen3.8:27b if recently used)
```

---

## Alternatives Considered

### Alternative 1: Multi-Model Strategy

Load multiple models simultaneously (e.g., 122B + 27B + 35B)

**Pros:**
- Parallel model access
- Automatic fallback
- Different models for different tasks

**Cons:**
- Memory pressure on M2 Ultra
- Context loss when switching
- Complex keepalive management
- Unreliable (models unloading unexpectedly)

**Rejected:** Memory constraints and complexity outweigh benefits

### Alternative 2: Agent-Specific Models

Assign specific models to specific agents (architect → 122B, builder → 27B, etc.)

**Pros:**
- Optimal model for each task type
- Potentially faster for simple tasks

**Cons:**
- Context fragmentation
- Complex routing logic
- User confusion about which model is active
- Model switching overhead

**Rejected:** MoE architecture makes this unnecessary; single model handles all tasks well

### Alternative 3: Dynamic Model Loading

Automatically load/unload models based on agent/request type

**Pros:**
- Optimal model for each task
- No manual switching

**Cons:**
- 2-3 minute load times per switch
- Complex orchestration
- Context loss during switches
- Unpredictable latency

**Rejected:** Load times disrupt workflow; context loss unacceptable

---

## References

- [LITELLM-CONFIG-GUIDE.md](../../documentation/LITELLM-CONFIG-GUIDE.md) - Full configuration guide
- `LITELLM_KEEPALIVE_CONFIG.md` - Historical keepalive documentation (obsolete)
- `config.yaml (on the proxy machine)` - deprecated config file
- `~/.config/opencode/micode.json.backup` - Archived old configuration (obsolete)

---

**Decision Date:** 2026-09-20  
**Review Date:** As needed (e.g., when upgrading hardware or adding new models)
