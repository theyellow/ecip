# Possible Future Development

Raw ideas collected from diagrams and documentation during the LiteLLM integration audit (2026-05-16).
None of these are implemented. This is not a backlog — no sizes, priorities, or owners.
Sort into a proper backlog in a later step.

---

## LLM Routing & Multi-Model

- Multi-model routing strategy: intent-based (GREETING → cheap model, REPORT → capable model), cost-based (approaching budget → cheaper model), load balancing across providers
- MiniMax-2.7 direct API client (Chinese/English, fast, cost-effective — alternative to LiteLLM proxy)
- Claude/Anthropic direct API client (complex reasoning, safety-focused — alternative to LiteLLM proxy)
- LLM Client Factory with connection pooling and timeout management
- OpenAI direct integration (beyond LiteLLM proxy)
- Ollama direct integration (local models without proxy)

## LLM Quality & Safety

- Response Cache: Caffeine + Redis, semantic similarity matching, TTL-based expiration, target 30% cache hit rate
- Response Validator: content safety check, format verification, length check
- Retry with fallback model on validation failure (max 3 retries, then escalate)
- Budget enforcement: per-tenant token/cost tracking with alerts at 80% and 95% thresholds

## Rule Engine

- Drools or easy-rules based rule evaluation (currently plain Java if/else in policy engine)
- Escalation Manager: priority queuing, route to human review admin queue
- Decision Cache (Caffeine) to reduce DB load on repeated identical inputs

## Kafka Infrastructure

- `AbstractKafkaConsumer` base class: common deserialization, metrics recording, correlation ID tracking, error handling
- `RetryableKafkaConsumer` wrapper: exponential backoff (1s/2s/4s), configurable max retries
- `DeadLetterQueue.retryFromDLQ()`: automated replay of failed messages after fix deployment
- Per-consumer Prometheus metrics

## Audit & Compliance

- Cryptographic hash chain for tamper detection (each event hashes previous)
- WORM (Write Once Read Many) audit log immutability
- Configurable retention tiers (7-year default)
- CSV / JSON / PDF export for regulatory requests
- SIEM integration (Splunk, ELK stack)
- AlertManager integration for threshold-based notifications

## Multi-Tenancy

- Query-level tenant isolation (cross-tenant data leak currently possible — see Backlog item #5)

## Moderation

- ML toxicity detection via OpenNLP or Perspective API (see Backlog item #8)
