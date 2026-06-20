# btca Configuration Guide - EMCIP Project

**Last Updated:** June 18, 2026  
**Purpose:** Setup and usage of btca (codebase analysis tool) for local LLM development with EMCIP

---

## Overview

btca is a CLI tool that clones library/framework source code locally and enables intelligent code search and questions about internals. For EMCIP development, this means you can ask questions like:

- "How does Spring Kafka handle consumer backpressure?"
- "Find GraalVM native-image examples with C++ JNI libraries"
- "Look up React 19 useEffect cleanup implementation details"

---

## Current Status

| Component | Status | Details |
|-----------|--------|---------|
| **btca CLI** | ✅ Installed v2.0.5 | Located at `/home/ben/.bun/bin/btca` |
| **Global Resources** | ⚠️ Configured | svelte, tailwindcss, nextjs |
| **Project Resources** | ❌ Not setup | Spring Boot, Kafka, TDLib pending |
| **Local LLM Integration** | ❌ Pending | Needs opencode provider config |

---

## Initial Setup (One-Time)

### Step 1: Configure AI Provider for Local LiteLLM

```bash
cd /home/ben/Development/ecip
btca connect --provider opencode
```

When prompted with the provider selection menu, choose:
```
Select a provider:
  1) OpenCode Zen    ← SELECT THIS
  2) Anthropic (Claude)
  3) OpenAI (GPT)
  ...
Enter number: 1
```

**Why OpenCode Zen?** This uses your existing `~/.config/opencode/opencode.json` configuration, which already points to your local LiteLLM server at `http://192.168.23.232:4000/v1`.

### Step 2: Verify Configuration

```bash
btca status
```

**Expected output:**
```
Selected model: worker-qwen3.6-moe (or frontier-qwen3.5-moe)
Selected provider: opencode
Selected provider authed: yes

Global resources:
  svelte
  tailwindcss
  nextjs
Project resources: (none yet - we'll add these below)
```

---

## Adding EMCIP Project Resources

These are the key libraries/frameworks you'll be working with in EMCIP. Add them to get intelligent code search capabilities:

### Spring Boot & Spring Framework

```bash
# Main Spring Boot repository (with docs)
btca reference https://github.com/spring-projects/spring-boot

# Spring Kafka (for consumer/producer patterns)
btca reference https://github.com/spring-projects/spring-kafka

# Spring Security (if needed for auth flows)
btca reference https://github.com/spring-projects/spring-security
```

### Apache Kafka

```bash
# Apache Kafka core (for producer/consumer internals)
btca reference https://github.com/apache/kafka
```

### Telegram TDLib

```bash
# Telegram Database Library (TDLib) - core of our Telegram integration
btca reference https://github.com/tdlib/td

# TDLib documentation (if available separately)
btca reference https://github.com/tdlib/docs 2>/dev/null || echo "Docs repo not found, using main tdlib"
```

### Optional: React & Frontend

```bash
# React (your frontend tech stack)
btca reference https://github.com/facebook/react

# TypeScript (for type-safe development)
btca reference https://github.com/microsoft/TypeScript
```

---

## Verifying Resources Are Added

After adding resources, verify:

```bash
btca status
```

**Expected output:**
```
Global resources:
  svelte
  tailwindcss
  nextjs

Project resources: (in /home/ben/Development/ecip/.btca/)
  spring-boot
  spring-kafka
  apache-kafka
  tdlib
  react
  typescript
```

Resources are cloned to `/home/ben/Development/ecip/.btca/` by default.

---

## Usage Patterns

### In opencode Conversations

When working in an opencode session, you can reference btca capabilities:

```
"Use btca to search Spring Kafka ConsumerFactory for backpressure handling patterns"
"Look up how React 19 handles useEffect cleanup with dependencies"
"Find examples of GraalVM native-image configuration with C++ JNI libraries"
```

### Using btca CLI Directly

```bash
# Ask questions about specific libraries
btca ask "How does Spring Kafka handle consumer backpressure?"

# Search for specific patterns
btca ask "Show me examples of @KafkaListener annotations with error handling"

# Get implementation details
btca ask "Explain how TDLib handles authentication state persistence"
```

### Common EMCIP Development Questions

| Question | Command |
|----------|---------|
| Spring Kafka consumer configuration | `btca ask "Show ConsumerFactory configuration examples"` |
| GraalVM native-image hints for JNI | `btca ask "Find native-image configuration for C++ libraries"` |
| React form validation patterns | `btca ask "Show React 19 form validation with hooks"` |
| TDLib authentication flow | `btca ask "Explain TDLib authorization state machine"` |

---

## Troubleshooting

### Issue: "Provider authed: no"

**Solution:** Run `btca connect` again and ensure you select the correct provider (OpenCode Zen).

### Issue: Network timeout when cloning repos

**Possible causes:**
- GitHub rate limiting
- Slow internet connection
- Corporate firewall blocking

**Solutions:**
```bash
# Increase timeout for large repos
git config --global http.postBuffer 524288000

# Use SSH instead of HTTPS (if you have SSH keys)
btca reference git@github.com:spring-projects/spring-boot.git
```

### Issue: "Resource not found" after adding

**Solution:** The resource might still be cloning. Wait 30 seconds and check status again:
```bash
btca status
```

Large repos like Spring Boot (~500MB) can take several minutes to clone.

### Issue: btca commands hang or timeout

**Possible causes:**
- Interactive prompt waiting for input
- Network issues during clone
- Model not responding

**Solutions:**
```bash
# Cancel hanging command (Ctrl+C)
# Check if model is available
curl http://192.168.23.232:4000/v1/models

# Restart btca session
btca clear
```

---

## Configuration Files Location

| File | Purpose | Path |
|------|---------|------|
| `opencode.json` | Main opencode config | `~/.config/opencode/opencode.json` |
| `micode.json` | micode plugin settings | `~/.config/opencode/micode.json` |
| Project resources | Cloned repos for this project | `/home/ben/Development/ecip/.btca/` (when created) |

---

## Model Configuration Reference

Your current model setup:

| Agent Type | Model | Use Case |
|------------|-------|----------|
| **Primary / Architect** (opencode) | `frontier-qwen3.5-moe` (119B) | Main conversation, architecture decisions |
| **Plan** (opencode) | `frontier-qwen3.5-moe` (119B) | Planning and orchestration |
| **Subagents** (opencode) | `worker-qwen3.6-moe` (35B) | Code generation, implementation tasks |
| **Primary / Subagents** (micode) | `worker-qwen3.6-moe` (35B) | Standard micode agent work |
| **btca agents** (octto, brainstormer, bootstrapper, artifact-searcher) | `standard-qwen3.6-moe` (35B w/ thinking) | Codebase analysis with reasoning enabled |
| **Reviewer** | `frontier-deepseek-r1` (70B) | Deep code reviews, complex reasoning |

For btca questions, the model is inherited from your micode configuration - it uses `layer-standard-qwen3.6-moe` which has thinking/reasoning enabled for better analysis of library internals.

---

## Best Practices for EMCIP Development

1. **Add resources before starting major work** - Don't interrupt flow to clone repos mid-task
2. **Use specific questions** - "How does X handle Y" works better than "Tell me about X"
3. **Combine with local code search** - Use `grep` and file reads for your own code, btca for library internals
4. **Keep resources updated** - Periodically check for new versions: `btca clear && btca reference <repo>`

---

## Future Enhancements

Consider adding these as needed:

- **GraalVM** - For native image configuration patterns
- **PostgreSQL** - If working on database optimizations
- **Kubernetes** - For deployment/helm chart questions
- **Docker** - Container best practices

---

**Last Updated:** June 18, 2026  
**Project:** EMCIP (Enterprise Messenger Community Intelligence Platform)
