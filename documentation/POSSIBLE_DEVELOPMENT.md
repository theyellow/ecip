# Possible Future Development

Raw ideas not yet in the backlog. Once an item gets a backlog entry it is removed from here.

---

## LLM Routing & Multi-Model

- Multi-model routing strategy: intent-based (GREETING → cheap model, REPORT → capable model), cost-based (approaching budget → cheaper model), load balancing across providers
- MiniMax-2.7 direct API client (Chinese/English, fast, cost-effective — alternative to LiteLLM proxy)
- Claude/Anthropic direct API client (complex reasoning, safety-focused — alternative to LiteLLM proxy)
- LLM Client Factory with connection pooling and timeout management
- OpenAI direct integration (beyond LiteLLM proxy)
- Ollama direct integration (local models without proxy)

## Knowledge Enrichment Follow-Ons

These build on Epic 26.10 (knowledge context injected into LLM prompts, merged PR #143):

- Per-template enrichment flag — let individual `PromptTemplate` rows opt in/out of enrichment rather than a global on/off switch
- Task-type-aware search type — use VECTOR for quick factual tasks, GRAPH for relational reasoning tasks, HYBRID for general responses
- Enrichment health indicator — expose a `/actuator/health` sub-indicator for knowledge-engine reachability so Kubernetes liveness probes catch misconfiguration
- Context ranking — score + re-rank retrieved passages by a combination of similarity and recency before truncating

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

- Row-level security at the PostgreSQL level (defence-in-depth for direct DB access — Hibernate `@Filter` + ReactorTenantContext already enforce this at the application level)

## Moderation

- Category-based moderation rules with thresholds — replace keyword/regex rules with ML-scored categories (harassment, hate_speech, sexual_content, self_harm, spam, misinformation), each with a 0–1 threshold and per-category action. Requires new backend data model + ML scoring pipeline.

## User-Facing / Self-Service

- Public self-service portal — separate service allowing end-users to link their own Telegram accounts without an EMCIP admin login. Requires tenant provisioning to be in place first.
- Tenant-level user limits and quotas — cap TENANT_ADMIN users per tenant, or Telegram accounts per tenant.
- SSO / OAuth2 / OIDC for admin login — replace username/password with Keycloak, Auth0, or Google Workspace. Requires replacing `JwtService` + `admin_users` with OIDC token exchange.

## Operator Reply Enhancements

- Media/file replies: images, documents, or voice messages as operator responses (Phase 1 is text-only)
- Edit or delete sent operator messages
- Bulk replies: respond to multiple flagged messages at once (e.g., same response to a spam wave)
