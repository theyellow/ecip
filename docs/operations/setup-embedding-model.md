# Setting Up Embedding and Extraction Models for Knowledge Search

The knowledge-engine requires two special-purpose models:

1. **EMBED** — an embedding model to power vector and hybrid search
2. **EXTRACT** — a language model for entity/relationship extraction (knowledge graph)

Without EMBED, only graph search works. Without EXTRACT, no graph data is built.

## Architecture

```
Knowledge Engine → LLM Orchestrator → LiteLLM Proxy → Ollama
                   (taskType: EMBED)    (192.168.23.232:4000)
```

## Step 1: Pull an embedding model in Ollama

On the machine running Ollama (`192.168.23.232`):

```bash
# Recommended: nomic-embed-text (137M params, 768 dimensions, good quality/speed)
ollama pull nomic-embed-text

# Alternative: smaller and faster
ollama pull all-minilm:l6-v2    # 23M params, 384 dimensions

# Alternative: larger and more accurate
ollama pull mxbai-embed-large   # 335M params, 1024 dimensions
```

Verify it works:

```bash
curl http://localhost:11434/api/embeddings -d '{
  "model": "nomic-embed-text",
  "prompt": "Hello world"
}'
# Should return a JSON object with an "embedding" array of floats
```

## Step 2: Add the model to LiteLLM config

Edit your LiteLLM proxy config (usually `litellm_config.yaml` or via the LiteLLM UI):

```yaml
model_list:
  # ... your existing models ...

  - model_name: nomic-embed-text
    litellm_params:
      model: ollama/nomic-embed-text
      api_base: http://localhost:11434
```

Restart LiteLLM or reload config. Verify:

```bash
curl http://192.168.23.232:4000/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{
    "model": "nomic-embed-text",
    "input": "Hello world"
  }'
# Should return an OpenAI-compatible response with embedding data
```

## Step 3: Register the model in EMCIP

Add a `model_configs` row via the Admin UI (LLM Models page) or directly in PostgreSQL:

```sql
INSERT INTO model_configs (
    id, model_key, provider, model_name, description,
    task_type, input_cost_per1k_tokens, output_cost_per1k_tokens,
    context_window, max_output_tokens, avg_latency_ms,
    supports_streaming, active, priority, created_at, updated_at, version_lock
) VALUES (
    gen_random_uuid(),
    'nomic-embed-text',           -- unique key
    'local-litellm',              -- must match llm_provider_configs.name
    'nomic-embed-text',           -- must match LiteLLM model_name
    'Local embedding model for knowledge search',
    'EMBED',                      -- IMPORTANT: must be exactly 'EMBED' (not 'EMBEDDING')
    0.0, 0.0,                     -- local model, no cost
    8192,                         -- context window (nomic supports 8192 tokens)
    0,                            -- embeddings don't have output tokens
    50.0,                         -- avg latency in ms
    false,                        -- embeddings don't stream
    true,                         -- active
    100,                          -- priority
    now(), now(), 0
);
```

**Important:** The `task_type` must be `EMBED` (not `EMBEDDING`). The knowledge-engine
sends `taskType: "EMBED"` in its requests to the LLM orchestrator.

## Step 4: Verify the pipeline

From inside the cluster (or via port-forward):

```bash
# Test via LLM orchestrator
kubectl exec -n emcip <llm-orchestrator-pod> -- curl -s -X POST \
  http://localhost:9085/api/analyse \
  -H "Content-Type: application/json" \
  -d '{"prompt": "test embedding", "taskType": "EMBED"}'
# Should return {"success": true, "analysis": "[0.123, -0.456, ...]", "model": "nomic-embed-text"}
```

## Step 5: Re-ingest and search

After the embedding model is configured:

1. **Backfill** watched groups again — messages will now get embeddings stored
2. **Vector search** and **hybrid search** will work in the Knowledge tab
3. **Graph search** requires the knowledge extraction pipeline (entity extraction via LLM) which runs on ingested documents

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `No model configured for task: EMBED` | No active model_config with task_type='EMBED' | Add the DB row (Step 3) |
| `vector must have at least 1 dimension` | Embedding call failed, empty vector passed to pgvector | Fix the EMBED model config first |
| `Connection refused` to LiteLLM | LiteLLM proxy not reachable from k8s | Check network/firewall between cluster and 192.168.23.232:4000 |
| Graph search returns 0 results | No entities extracted yet | Run backfill; extraction happens during ingestion |

## Dimension Consistency

All embeddings in the database must have the same dimension count. If you switch
embedding models (e.g., from 768-dim to 1024-dim), you must:

1. Truncate existing embeddings: `UPDATE ke_knowledge_documents SET embedding = NULL;`
2. Clear graph embeddings: `DELETE FROM ke_graph_node_embeddings;`
3. Re-ingest all documents

---

## Setting Up an Extraction Model (task_type: EXTRACT)

The knowledge-engine uses a second LLM call to extract entities and relationships
from ingested messages. This builds the knowledge graph that powers graph search.

### What the EXTRACT task does

The knowledge-engine sends a structured prompt like:

```
Extract structured knowledge from the text below.

CONCEPT TYPES:
- Person: A human individual
- Organisation: A company or group
...

TEXT:
<the message>

Return JSON:
{"entities": [...], "relationships": [...]}
```

This is a **structured JSON extraction** task — it needs a model that:
- Understands the languages in your monitored groups (German, English, etc.)
- Can follow instructions and output valid JSON
- Does NOT need to be large — this is pattern matching, not reasoning

### Choosing the right model

| Model                  | Size  | Languages     | Speed   | Quality | Recommendation           |
|------------------------|-------|---------------|---------|---------|--------------------------|
| `qwen3:1.7b`           | 1.7B  | 100+ langs    | Very fast | Good    | **Best for EXTRACT** — smallest Qwen3 with multilingual + JSON |
| `qwen3:4b`             | 4B    | 100+ langs    | Fast    | Better  | If 1.7B quality isn't enough |
| `gemma3:4b`             | 4B    | 140+ langs    | Fast    | Good    | Alternative to Qwen       |
| `phi4-mini:3.8b`        | 3.8B  | Multi         | Fast    | Good    | Strong JSON adherence     |
| `standard-qwen3.6-moe` | 35B   | 100+ langs    | Slow    | Best    | Overkill for extraction   |

**Recommendation: `qwen3:1.7b`** — It's the sweet spot for EXTRACT:
- Tiny (1.2 GB RAM), runs alongside your embedding model without strain
- Qwen3 family has excellent multilingual coverage (German, English, Russian, etc.)
- Supports structured output / JSON mode
- Fast enough to process message batches without bottlenecking ingestion

You do **not** need your big 35B MoE model for this. Entity extraction is a
focused task — a small model that understands the language and can output JSON
is all you need. Save the big model for GENERAL/CHAT/MODERATION tasks that
require reasoning.

### Step 1: Pull the model

```bash
ollama pull qwen3:1.7b
```

### Step 2: Add to LiteLLM config

```yaml
model_list:
  - model_name: qwen3-1.7b-extract
    litellm_params:
      model: ollama/qwen3:1.7b
      api_base: http://localhost:11434
```

### Step 3: Register in EMCIP

```sql
INSERT INTO model_configs (
    id, model_key, provider, model_name, description,
    task_type, input_cost_per1k_tokens, output_cost_per1k_tokens,
    context_window, max_output_tokens, avg_latency_ms,
    supports_streaming, active, priority, created_at, updated_at, version_lock
) VALUES (
    gen_random_uuid(),
    'qwen3-1.7b-extract',
    'local-litellm',
    'qwen3-1.7b-extract',        -- must match LiteLLM model_name
    'Small multilingual model for knowledge entity extraction',
    'EXTRACT',                     -- IMPORTANT: must be exactly 'EXTRACT'
    0.0, 0.0,
    32768,                         -- context window
    4096,                          -- max output tokens (JSON can be verbose)
    200.0,                         -- avg latency in ms
    false,
    true,
    100,
    now(), now(), 0
);
```

### Step 4: Verify

```bash
# Test via LLM orchestrator
kubectl exec -n emcip <llm-orchestrator-pod> -- curl -s -X POST \
  http://localhost:9085/api/analyse \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Extract entities: Alice discussed AI safety with Bob", "taskType": "EXTRACT"}'
# Should return JSON with entities and relationships
```

After configuring EXTRACT, re-run backfill to populate the knowledge graph.

---

## Using One Model for Multiple Task Types

A single Ollama model can serve multiple EMCIP task types (e.g. EXTRACT +
CLASSIFICATION). This saves RAM — Ollama loads the model once regardless of
how many LiteLLM aliases point to it.

### How it works

```
EMCIP model_configs          LiteLLM config              Ollama
┌──────────────────┐    ┌─────────────────────┐    ┌──────────────┐
│ task: EXTRACT    │───▶│ qwen3-4b-extract    │───▶│              │
│ model: qwen3-4b- │    │ model: ollama_chat/  │    │  qwen3:4b    │
│        extract   │    │        qwen3:4b      │    │  (loaded     │
├──────────────────┤    ├─────────────────────┤    │   once)      │
│ task: CLASSIFY   │───▶│ qwen3-4b-classify   │───▶│              │
│ model: qwen3-4b- │    │ model: ollama_chat/  │    │              │
│        classify  │    │        qwen3:4b      │    └──────────────┘
└──────────────────┘    └─────────────────────┘
```

### LiteLLM config

Create separate aliases pointing to the same Ollama model. Each alias needs
its own `model_info.mode` declaration so LiteLLM routes health checks and
requests correctly.

```yaml
model_list:
  # --- Embedding model (dedicated, cannot be shared) ---
  - model_name: bge-m3
    litellm_params:
      model: ollama/bge-m3
      api_base: http://localhost:11434
    model_info:
      mode: embedding        # REQUIRED: routes to /api/embed in Ollama

  # --- Small model for EXTRACT ---
  - model_name: qwen3-4b-extract
    litellm_params:
      model: ollama_chat/qwen3:4b      # ollama_chat/ routes to /api/chat
      api_base: http://localhost:11434
    model_info:
      mode: completion

  # --- Same model, different alias for CLASSIFICATION ---
  - model_name: qwen3-4b-classify
    litellm_params:
      model: ollama_chat/qwen3:4b      # same underlying model
      api_base: http://localhost:11434
    model_info:
      mode: completion
```

**Key points:**
- Use `ollama_chat/` prefix for chat/completion models (routes to `/api/chat`)
- Use `ollama/` prefix for embedding models (routes to `/api/embed`)
- Set `model_info.mode` explicitly — `embedding` or `completion` — so health
  checks and request routing work correctly
- Ollama loads `qwen3:4b` into RAM once; both aliases share it

### EMCIP model_configs (DB or Admin UI)

Add one row per task type, each pointing to its LiteLLM alias:

```sql
-- EXTRACT task
INSERT INTO model_configs (
    id, model_key, provider, model_name, description,
    task_type, input_cost_per1k_tokens, output_cost_per1k_tokens,
    context_window, max_output_tokens, avg_latency_ms,
    supports_streaming, active, priority, created_at, updated_at, version_lock
) VALUES (
    gen_random_uuid(),
    'qwen3-4b-extract',
    'local-litellm',
    'qwen3-4b-extract',
    'Small multilingual model for entity extraction',
    'EXTRACT', 0.0, 0.0, 32768, 4096, 200.0,
    false, true, 100, now(), now(), 0
);

-- CLASSIFICATION task (same underlying model, different alias)
INSERT INTO model_configs (
    id, model_key, provider, model_name, description,
    task_type, input_cost_per1k_tokens, output_cost_per1k_tokens,
    context_window, max_output_tokens, avg_latency_ms,
    supports_streaming, active, priority, created_at, updated_at, version_lock
) VALUES (
    gen_random_uuid(),
    'qwen3-4b-classify',
    'local-litellm',
    'qwen3-4b-classify',
    'Small multilingual model for intent classification',
    'CLASSIFICATION', 0.0, 0.0, 32768, 1024, 150.0,
    false, true, 100, now(), now(), 0
);
```

### Why separate aliases?

You could point both model_configs rows at the same LiteLLM `model_name`,
but separate aliases give you:
- **Per-task-type metrics** in LiteLLM dashboard (extract vs classify traffic)
- **Independent rate limits** if needed later
- **Easy migration** — swap one task to a different model without touching the other

### Embedding models cannot be shared

Embedding models (bge-m3, nomic-embed-text) are special-purpose and only
support the `/v1/embeddings` API. They cannot serve EXTRACT, CLASSIFICATION,
or any other chat/completion task type. They always need their own dedicated
alias with `mode: embedding`.

### Model size guidance

| Task types to cover | Recommended model | RAM |
|---|---|---|
| EXTRACT only | `qwen3:1.7b` | ~1.2 GB |
| EXTRACT + CLASSIFICATION | `qwen3:4b` | ~2.5 GB |
| EXTRACT + CLASSIFICATION + MODERATION | `qwen3:4b` | ~2.5 GB |
| GENERAL + CHAT (reasoning needed) | Keep your 35B MoE | ~20 GB |
