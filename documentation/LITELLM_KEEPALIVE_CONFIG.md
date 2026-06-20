# Keepalive Configuration Summary

**Created:** June 18, 2026  
**Purpose:** Document keepalive configuration for LiteLLM + Ollama setup

---

## ✅ Changes Made

### 1. LiteLLM Server (192.168.23.232)
Added `keep_alive: "-1"` to all models in config.yaml to prevent model unloading.

**Valid keepalive formats:**
- `"-1"` = Forever (never unload) ⭐ **Recommended for local development**
- `"8m"` = 8 minutes
- `"2h"` = 2 hours
- `"0"` = Immediately unload after request (BAD - causes shutdowns)

### 2. opencode.json (Local)
Added top-level `model` field to prevent fallback to gpt-5.2-codex:
```json
"model": "litellm/frontier-qwen3.5-moe",
```

### 3. micode.json (NEW - Local)
Created with agent-specific model overrides:
- **Reasoning/Planning agents** → `frontier-qwen3.5-moe` (119B)
- **Implementation agents** → `worker-qwen3-coder` (32B)
- **Review agents** → `frontier-deepseek-r1` (70B)

---

## 📁 Config File Locations

| File | Location | Purpose |
|------|----------|---------|
| LiteLLM config.yaml | `/path/to/litellm/config.yaml` (remote server) | Model routing + keepalive |
| opencode.json | `~/.config/opencode/opencode.json` (local) | Main Opencode configuration |
| micode.json | `~/.config/opencode/micode.json` (local) | Micode plugin agent overrides |

---

## 🧪 Testing Keepalive

### Test 1: Quick Health Check
```bash
curl -s "http://192.168.23.232:4000/health" | jq '.healthy_endpoints'
```
Should show models as healthy (not timing out)

### Test 2: Make a Request
```bash
curl -s "http://192.168.23.232:4000/v1/chat/completions" \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "model": "frontier-qwen3.5-moe",
    "messages": [{"role": "user", "content": "test"}],
    "stream": false,
    "max_tokens": 10
  }' | jq '.'
```

### Test 3: Verify Model Stays Loaded
After making a request, wait 30 seconds and make another. If keepalive is working:
- ✅ Second request should be fast (model already loaded)
- ❌ If slow/timeout, model was unloaded → keepalive not configured correctly

---

## 🔄 Restarting LiteLLM with Config

```bash
# Find current process
ps aux | grep litellm

# Stop it
pkill -f litellm

# Start with config
litellm --config /path/to/config.yaml --host 0.0.0.0 --port 4000

# Or with detailed debug logging
litellm --config /path/to/config.yaml --detailed_debug
```

---

## 📊 Model Selection Priority

1. **micode.json agent override** (highest)
2. **opencode.json top-level `model` field** ← **Added in this config**
3. **opencode.json per-agent config**
4. **Hardcoded fallback: gpt-5.2-codex** (lowest - avoided now ✅)

---

## 🐛 Troubleshooting

### Models still shutting down
1. Check LiteLLM logs for `keep_alive` errors
2. Verify config.yaml has quotes around time values: `"8m"` not `8m`
3. Use `-1` for testing (forever)

### Still seeing gpt-5.2-codex
1. Restart Opencode completely
2. Check `~/.config/opencode/opencode.json` has top-level `model` field
3. Run: `opencode --help` to verify config loads

### Connection timeout
1. Verify remote server is running: `curl http://192.168.23.232:4000/models`
2. Check firewall: `ufw status` (port 4000 should be open)
3. Verify Ollama is running on the remote server

---

## 📝 Notes

- **Keepalive applies per-model** - each model in config.yaml needs its own setting
- **-1 is safe for development** - won't cause memory issues unless you have 50+ models loaded
- **Production consideration** - use timed values (e.g., `"30m"`) if memory is constrained

---

**Last Updated:** June 18, 2026  
**Status:** ✅ Configured and tested
