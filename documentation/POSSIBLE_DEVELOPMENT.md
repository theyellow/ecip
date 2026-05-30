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

- Row-level security at the PostgreSQL level (redundant given Hibernate `@Filter` + scoped R2DBC enforcement already in place, but provides defence-in-depth for direct DB access)

## Moderation

- ML toxicity detection via OpenNLP or Perspective API (see Backlog item #8)
- **Category-based moderation rules with thresholds** — the v2 design handoff envisions replacing keyword/regex rules with ML-scored categories (harassment, hate_speech, sexual_content, self_harm, spam, misinformation), each with a 0–1 threshold, per-category action (BLOCK/FLAG/WARN/ALLOW), and optional admin notification. This would require a new backend data model and ML scoring pipeline. Deferred until backlog item #8 (ML toxicity detection) is implemented.

## User-Facing / Self-Service

- **Public self-service portal** (separate service, not admin-api): allow end-users (group members, tenant subscribers, external stakeholders) to link their own personal Telegram accounts to EMCIP without requiring an EMCIP admin login. Would need a new public-facing API distinct from admin-api (admin-api is too powerful to expose publicly), user registration/identity model, and a dedicated UI. Prerequisite: stable tenant provisioning flow (backlog #21).
- **Fine-grained per-resource permissions**: e.g., a read-only `TENANT_VIEWER` role that can see data but not mutate anything, or a `TELEGRAM_OPERATOR` role scoped to only the Telegram account connection flow. Extends the `RolePermissions` matrix introduced in backlog #9.
- **Tenant-level user limits and quotas**: cap the number of `TENANT_ADMIN` users per tenant, or the number of Telegram accounts a tenant can connect. Relevant once self-service onboarding (backlog #21) is in place.
- **SSO / OAuth2 / OIDC for admin login**: replace username/password auth in `admin_users` with an identity provider (Keycloak, Auth0, Google Workspace). Would require replacing the current `JwtService` + `admin_users` table with an OIDC token exchange flow.

## Operator Reply Enhancements (item #23 follow-ons)

- Media/file replies: support sending images, documents, or voice messages as operator responses (Phase 1 is text-only)
- Edit or delete sent operator messages: allow correcting or retracting a response after it was sent to Telegram
- Bulk replies: respond to multiple flagged messages at once (e.g., same response to a spam wave)
- Message templates: pre-defined response templates (e.g., "Community guidelines warning", "Spam notice") selectable from a dropdown instead of freeform text

## Admin UI v2 Design Handoff Deferred Items

Items from the v2 design handoff prototypes that were intentionally excluded during the page redesigns (PR #94). These are visual/UX enhancements beyond the token restyle.

- **Simulate: two-column layout with animated pipeline trace** — the design handoff shows a split view with real-time pipeline stage visualization (message flowing through `telegram.raw.messages` → `messages.classified` → `policies.decisions` with animated progress). Current production is a simple form + static pipeline description `<ol>`.
- **Flags: full reply composer v2** — the handoff has a 4-mode SegmentedControl (Public reply / Quote-reply / Private DM / Silent note), ChipRow of pre-fill templates (e.g. "Community guidelines warning"), textarea with `{n} chars · {MODE}` character counter footer, and a Discard button. Current production has 2-mode Group/DM SegmentedControl and plain textarea. Quote-reply and Silent note modes need backend support.
- **Users: expanded role model and audit columns** — the handoff shows MODERATOR, ANALYST, VIEWER roles (production only has ADMIN/TENANT_ADMIN) and `lastLogin`/`createdAt` columns (not in the current API response). Would require backend role expansion and API changes.

## Resilience (SC8 follow-ons)

- Retry with exponential backoff on circuit-broken calls before surfacing 503 (currently 30 s half-open re-probe is the only recovery path)
- Per-service fallback responses: return empty list / degraded payload instead of 503 so the admin UI stays functional when one downstream service is down
