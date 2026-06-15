# #24 — Flag-detail: AI analysis end-to-end fix

**Date**: 2026-06-14
**Status**: Draft
**Addresses**: Backlog #24 — verify AI analysis renders correctly in the detail modal

---

## Goal

Unblock the AI analysis flow so clicking "Analyse" in the Flag Detail modal returns an LLM-generated analysis instead of a 503 error.

## Root Cause

The entire flow exists end-to-end (PR #89):

```
UI "Analyse" button
  → POST /api/flags/{id}/analyse          [admin-api FlagController]
  → FlagService.analyse()
      → PolicyEngineClient.getDecision()  [fetches flag context]
      → buildAnalysisPrompt()             [intent, decision, confidence, reason, messageText]
      → POST /api/analyse                 [llm-orchestrator OrchestratorController]
          → selectModelForTask("GENERAL") → ❌ Optional.empty() → 503
```

`selectModelForTask("GENERAL")` queries `model_configs WHERE task_type = 'GENERAL' AND active = true`. No such row exists. Existing migrations seed `response`, `summary`, `command_validation`, `MODERATION`, and knowledge-engine types — but not `GENERAL`.

## Fix

Add Liquibase changeset `011-seed-general-model-config.xml` inserting one `model_configs` row:

| Column | Value | Rationale |
|--------|-------|-----------|
| `model_key` | `qwen3-30b-a3b-general` | Naming convention: `{model}-{task}` |
| `provider` | `litellm` | Same as all other seeds since migration 009 |
| `model_name` | `qwen3-30b-a3b` | Analysis needs reasoning quality (same tier as response/summary) |
| `task_type` | `GENERAL` | Matches hardcoded value in `OrchestratorController.analyse()` |
| `context_window` | `40000` | Same as other qwen3-30b-a3b entries |
| `max_output_tokens` | `1024` | Matches hardcoded value in `OrchestratorController.analyse()` line 295 |
| `active` | `true` | Must be active for `selectModelForTask` to find it |
| `priority` | `1` | Default priority |
| `input_cost_per1k_tokens` | `0.0` | Local LiteLLM, no per-token cost |
| `output_cost_per1k_tokens` | `0.0` | Local LiteLLM, no per-token cost |
| `avg_latency_ms` | `800.0` | Analysis prompts are longer than moderation, estimate higher latency |
| `supports_streaming` | `false` | Synchronous call in current implementation |

## Verification

After migration runs:
1. Start docker-compose (all services + LiteLLM provider)
2. Open Admin UI > Decisions > click any decision with a flagged message
3. Click "Analyse" button
4. Confirm: spinner shows, then analysis text renders in the AI Analysis section with model attribution line
5. Confirm: "Copy" button copies the analysis text

## Prerequisites

- A working LiteLLM provider must be configured in Admin UI > AI Config > LLM Provider (runtime config, not part of this changeset)
- The `qwen3-30b-a3b` model must be available in the LiteLLM instance

## Affected files

| File | Change |
|------|--------|
| `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/011-seed-general-model-config.xml` | New changeset |
| `emcip-llm-orchestrator/src/main/resources/db/changelog/db.changelog-master.xml` | Add include for 011 |

## Not in scope

- Changing the analysis prompt or system prompt (works as-is)
- Adding automated tests for the LLM response (requires running LLM infrastructure)
- UI changes to the Flag Detail modal (already complete from PR #89)
- Retry/fallback on the orchestrator WebClient call (separate concern, could be added later)
