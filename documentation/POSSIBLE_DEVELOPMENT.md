# Possible Future Development

Raw ideas collected from diagrams and documentation during the LiteLLM integration audit (2026-05-16).
Items that are now implemented or have a backlog entry are noted with their status.

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

- ✅ `AbstractKafkaConsumer` base class: common deserialization, metrics recording, correlation ID tracking, error handling — partially addressed by SC5 refactor (PR #63) and `CommonKafkaConfig` in emcip-core
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

- Row-level security at the PostgreSQL level (defence-in-depth for direct DB access — Hibernate `@Filter` + ReactorTenantContext already enforce this at the application level per SC4/PR #60)

## Moderation

- ✅ Basic signal detectors (9 structural/script signals) — **done, BACKLOG #36 (PR #115)**
- ML toxicity detection via OpenNLP or Perspective API — **BACKLOG #8**
- **Category-based moderation rules with thresholds** — the v2 design handoff envisions replacing keyword/regex rules with ML-scored categories (harassment, hate_speech, sexual_content, self_harm, spam, misinformation), each with a 0–1 threshold, per-category action (BLOCK/FLAG/WARN/ALLOW), and optional admin notification. Requires new backend data model + ML scoring pipeline. Deferred until #8 (ML toxicity detection) is implemented.

## User-Facing / Self-Service

- **Public self-service portal** — separate service, not admin-api. Allow end-users to link their own Telegram accounts without an EMCIP admin login. Prerequisite: stable tenant provisioning flow (BACKLOG #21).
- **Fine-grained per-resource permissions** — TENANT_VIEWER (read-only), TELEGRAM_OPERATOR (scoped to account connection flow), etc. Extends the `RolePermissions` matrix introduced in BACKLOG #9. Partially captured in BACKLOG #41 (Users expanded role model).
- **Tenant-level user limits and quotas** — cap TENANT_ADMIN users per tenant, or Telegram accounts per tenant. Relevant once self-service onboarding (BACKLOG #21) is in place.
- **SSO / OAuth2 / OIDC for admin login** — replace username/password with an identity provider (Keycloak, Auth0, Google Workspace). Requires replacing `JwtService` + `admin_users` with OIDC token exchange.

## Operator Reply Enhancements (item #23 follow-ons)

- Media/file replies: support sending images, documents, or voice messages as operator responses (Phase 1 is text-only)
- Edit or delete sent operator messages: allow correcting or retracting a response
- Bulk replies: respond to multiple flagged messages at once (e.g., same response to a spam wave)
- ✅ Message templates: chip-row of pre-defined response templates — **partially in UI (BACKLOG #41 Decisions reply composer v2)**

## Admin UI v2 Design Handoff Deferred Items

Items from the v2 design handoff prototypes (PR #94) that were explicitly excluded. Now tracked in **BACKLOG #41**.

- **Simulate: two-column layout with animated pipeline trace** — BACKLOG #41
- **Decisions: full reply composer v2** (4-mode SegmentedControl, chip-row templates, char counter) — BACKLOG #41
- **Users: expanded role model** (MODERATOR, ANALYST, VIEWER + lastLogin/createdAt columns) — BACKLOG #41

## Resilience (SC8 follow-ons)

Now tracked in **BACKLOG #40**.

- Retry with exponential backoff on circuit-broken calls before surfacing 503 — BACKLOG #40
- Per-service fallback responses: return empty list / degraded payload instead of 503 — BACKLOG #40
