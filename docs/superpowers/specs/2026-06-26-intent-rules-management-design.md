# Intent Rules Management — Design Spec

**Date:** 2026-06-26
**Status:** Approved
**Scope:** Move hardcoded intent-classifier rules and signal thresholds to database; add CRUD admin UI

---

## Problem

`IntentClassificationService` contains six hardcoded lexical rules (GREETING, QUESTION, COMMAND, THANKS, GOODBYE, SPAM). `SignalDetector` contains hardcoded numeric thresholds (capsRatio, foreignScriptRatio, etc.) and a hardcoded toxicity word list. These cannot be changed without a code deployment, and they are not tenant-aware.

---

## Goals

1. Persist lexical rules in a new `intent_rules` table (intent-classifier service)
2. Persist signal detector thresholds in a new `intent_signal_config` table (intent-classifier service)
3. Expose CRUD REST endpoints for both, proxied through admin-api
4. Add two new admin UI pages: **Intent Rules** (table) and **Intent Signal Config** (settings form)
5. Reorder navbar to reflect the Kafka pipeline: Intent Rules → Policy Rules → Moderation Rules
6. Seed existing hardcoded values as Liquibase defaults so no functionality is lost on migration

---

## Data Model

### `intent_rules` table

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | VARCHAR(36) | PK | UUID |
| `name` | VARCHAR(64) | NOT NULL | Human-readable label |
| `description` | VARCHAR(500) | | Optional documentation |
| `match_mode` | VARCHAR(8) | NOT NULL | `KEYWORD` or `REGEX` |
| `pattern` | VARCHAR(500) | NOT NULL | Pipe-separated keywords (`hello\|hi\|hey`) for KEYWORD mode; Java regex for REGEX mode |
| `intent` | VARCHAR(32) | NOT NULL | `GREETING`, `SPAM`, etc.; free-text to allow custom intents |
| `confidence` | DOUBLE PRECISION | NOT NULL | 0.0–1.0; confidence assigned when this rule fires |
| `priority` | INTEGER | NOT NULL DEFAULT 100 | Lower value = evaluated first; first matching rule wins |
| `active` | BOOLEAN | NOT NULL DEFAULT TRUE | |
| `tenant_id` | UUID | NOT NULL | Multi-tenant isolation |
| `created_at` | TIMESTAMPTZ | NOT NULL | |
| `updated_at` | TIMESTAMPTZ | NOT NULL | |

Indexes: `idx_intent_rules_tenant_active` on `(tenant_id, active)`, `idx_intent_rules_priority` on `priority`.

**Seed rows (global/default tenant — same UUID used elsewhere):**

| name | match_mode | pattern | intent | confidence | priority |
|------|------------|---------|--------|------------|----------|
| Greeting | KEYWORD | hello\|hi\|hey\|good morning\|good evening | GREETING | 0.80 | 10 |
| Question | KEYWORD | what\|how\|why\|when\|where\|who\|is\|are\|can\|does | QUESTION | 0.75 | 20 |
| Command | KEYWORD | start\|stop\|help\|create\|delete\|show\|list\|get\|set\|update | COMMAND | 0.85 | 30 |
| Thanks | KEYWORD | thank\|thanks\|thx\|ty\|thank you | THANKS | 0.90 | 40 |
| Goodbye | KEYWORD | bye\|goodbye\|later\|see you\|cya\|ttyl | GOODBYE | 0.85 | 50 |
| Spam | KEYWORD | click here\|buy now\|free money\|viagra\|crypto investment\|earn money fast | SPAM | 0.95 | 60 |

---

### `intent_signal_config` table

One row per tenant. Stores all `SignalDetector` numeric thresholds and the toxicity word list.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | VARCHAR(36) | PK | UUID |
| `tenant_id` | UUID | NOT NULL UNIQUE | One config per tenant |
| `description` | VARCHAR(500) | | Optional label for this config set |
| `foreign_script_ratio` | DOUBLE PRECISION | NOT NULL DEFAULT 0.6 | Fraction of non-Latin characters above which FOREIGN_SCRIPT intent fires |
| `cyrillic_ratio` | DOUBLE PRECISION | NOT NULL DEFAULT 0.6 | Fraction of Cyrillic characters above which CYRILLIC intent fires |
| `lookalike_suspicion` | INTEGER | NOT NULL DEFAULT 3 | Minimum count of Cyrillic/Greek lookalike characters to trigger LOOKALIKE_ATTACK |
| `zero_width_abuse` | INTEGER | NOT NULL DEFAULT 2 | Minimum count of zero-width / RTL-override characters to trigger ZERO_WIDTH_ABUSE |
| `caps_ratio` | DOUBLE PRECISION | NOT NULL DEFAULT 0.7 | Fraction of uppercase alphabetic characters above which SHOUTING intent fires |
| `toxicity_words` | JSONB | NOT NULL DEFAULT '[]' | JSON array of strings; whole-word match triggers TOXICITY intent |
| `created_at` | TIMESTAMPTZ | NOT NULL | |
| `updated_at` | TIMESTAMPTZ | NOT NULL | |

**Seed row:** one global-tenant row using the hardcoded values from `SignalDetector.java` (toxicity word list extracted verbatim).

---

## Backend — emcip-intent-classifier

### New classes

**Entities:**
- `IntentRule` — JPA entity mapping `intent_rules`; UUID id (String), fields as above; `@Version` for optimistic locking
- `IntentSignalConfig` — JPA entity mapping `intent_signal_config`; `toxicityWords` mapped as `List<String>` via `@JdbcTypeCode(SqlTypes.JSON)` + `@Column(columnDefinition = "jsonb")` (Hibernate 6 native, same as `PolicyRuleConfig.conditions`)

**Repositories:**
- `IntentRuleRepository extends JpaRepository<IntentRule, String>`
  - `findByTenantIdAndActiveTrueOrderByPriorityAsc(UUID tenantId)`
- `IntentSignalConfigRepository extends JpaRepository<IntentSignalConfig, String>`
  - `findByTenantId(UUID tenantId)`

**Controllers:**
- `IntentRuleController` — `@RestController @RequestMapping("/api/intent-rules")`
  - `GET /api/intent-rules` → list all for tenant (active and inactive, for admin)
  - `POST /api/intent-rules` → create; auto-sets `tenantId`, `createdAt`, `updatedAt`, `active=true`, `priority=100`
  - `PUT /api/intent-rules/{id}` → update; refreshes `updatedAt`; validates tenant ownership
  - `DELETE /api/intent-rules/{id}` → delete; validates tenant ownership
- `IntentSignalConfigController` — `@RestController @RequestMapping("/api/intent-signal-config")`
  - `GET /api/intent-signal-config` → fetch config for current tenant (returns 404 if none yet)
  - `PUT /api/intent-signal-config` → upsert (create or update) config for current tenant

**DTOs:**
- `IntentRuleDto` — flat record matching all entity fields (no circular refs)
- `IntentSignalConfigDto` — flat record

**Service modifications:**
- `IntentClassificationService`: remove static rule list; inject `IntentRuleRepository`; load active rules at startup via `@PostConstruct`; expose `refreshRules()` method called by controller on create/update/delete
- `SignalDetector`: accept `IntentSignalConfig` as constructor argument (or setter); `IntentClassificationService` passes current config when building/refreshing the detector

**Liquibase migrations** (in `db/changelog/changes/`):
- `003-create-intent-rules.xml` — creates `intent_rules` table + indexes + seed data
- `004-create-intent-signal-config.xml` — creates `intent_signal_config` table + seed row

---

## Backend — emcip-admin-api

### New client

`IntentClassifierClient` — `WebClient`-based, URL from `services.intent-classifier.url` (env var `INTENT_CLASSIFIER_URL`, same pattern as `services.moderation-service.url`).

Methods:
- `listRules()` → `Flux<JsonNode>`
- `createRule(JsonNode)` → `Mono<JsonNode>`
- `updateRule(String id, JsonNode)` → `Mono<JsonNode>`
- `deleteRule(String id)` → `Mono<Void>`
- `getSignalConfig()` → `Mono<JsonNode>`
- `upsertSignalConfig(JsonNode)` → `Mono<JsonNode>`

### New controllers

- `IntentRuleController` — proxies CRUD to `IntentClassifierClient`; returns `Flux<JsonNode>` / `Mono<JsonNode>`
- `IntentSignalConfigController` — proxies GET/PUT

No `editedBy` extraction (keeping it simple, matching moderation-service pattern).

---

## Frontend — emcip-admin-ui

### New pages

**`/pages/IntentRules/IntentRules.jsx`** — follows `ModerationRules.jsx` structure exactly:

- DataTable columns:
  - `name` (text)
  - `intent` (badge: GREETING=blue, QUESTION=gray, COMMAND=blue, THANKS=green, GOODBYE=gray, SPAM=red, custom=gray)
  - `matchMode` (badge: KEYWORD=gray, REGEX=yellow)
  - `pattern` (monospace, truncated with title tooltip)
  - `confidence` (numeric, 2 decimal places)
  - `priority` (numeric)
  - `active` (ON/OFF badge)
- Create/edit modal fields:
  - `name` (text input)
  - `description` (textarea, optional)
  - `intent` (text input — free-text to allow custom intents; no enum restriction)
  - `matchMode` (dropdown: KEYWORD / REGEX)
  - `pattern` (text input; hint text changes: *"Pipe-separated keywords, e.g. hello|hi|hey"* for KEYWORD, *"Java regex pattern, e.g. (?i)click\s+here"* for REGEX)
  - `confidence` (number input 0.00–1.00, step 0.05)
  - `priority` (number input, default 100)
  - `active` (toggle)

**`/pages/IntentSignalConfig/IntentSignalConfig.jsx`** — settings form (not a table):

- Single form for current tenant's signal config
- Fields with label + tooltip for each:
  - `foreignScriptRatio` (0.0–1.0) — *"Fraction of non-Latin characters above which FOREIGN_SCRIPT intent fires"*
  - `cyrillicRatio` (0.0–1.0) — *"Fraction of Cyrillic characters above which CYRILLIC intent fires"*
  - `lookalikeSuspicion` (integer ≥ 0) — *"Minimum count of Cyrillic/Greek lookalike chars mixed with Latin to trigger LOOKALIKE_ATTACK"*
  - `zeroWidthAbuse` (integer ≥ 0) — *"Minimum count of zero-width or RTL-override characters to trigger ZERO_WIDTH_ABUSE"*
  - `capsRatio` (0.0–1.0) — *"Fraction of uppercase alphabetic characters above which SHOUTING fires"*
  - `toxicityWords` (tag/chip input — comma-separated, rendered as removable chips) — *"Whole-word matches; any hit triggers TOXICITY intent"*
  - `description` (textarea, optional)
- Single **Save** button → PUT `/api/intent-signal-config`
- On load: GET config; if 404, show empty form with defaults pre-filled

### New API clients

- `/api/intentRules.js` — `list()`, `create(body)`, `update(id, body)`, `remove(id)`
- `/api/intentSignalConfig.js` — `get()`, `upsert(body)`

### Navbar change (`Sidebar.jsx`)

Reorder nav entries:
```
Intent Rules      (icon: ✦, permission: INTENT_RULES_READ)
Policy Rules      (icon: ⚖, permission: POLICY_RULES_READ)
Moderation Rules  (icon: ⊘, permission: MODERATION_RULES_READ)
```

Intent Signal Config does **not** get its own top-level nav entry — it is linked from the Intent Rules page as a secondary action button ("Signal Config →") to keep the nav clean.

### Routes (`App.jsx`)

Add:
- `/intent-rules` → `<IntentRules />`
- `/intent-signal-config` → `<IntentSignalConfig />`

---

## Rule Evaluation Logic

`IntentClassificationService` applies rules in priority order (ascending). For KEYWORD mode, the pattern is split on `|` and each keyword is checked via `String.contains()` (case-insensitive). For REGEX mode, the pattern is compiled to a `java.util.regex.Pattern` at load time (not per-message) and matched via `Matcher.find()`.

The first rule that matches determines the intent and confidence. Signal-based detection (via `SignalDetector`) runs as the fallback when no lexical rule matches — same as current behavior, now using DB-configured thresholds.

Compiled regex patterns are cached in a `Map<String, Pattern>` keyed by rule ID, rebuilt on `refreshRules()`.

---

## Out of Scope

- Version history for intent rules (can be added later following policy-engine pattern)
- Dry-run / rule testing UI
- Making `SignalDetector`'s format-detection flags (`emojiOnly`, `stickerOnly`, `imageOnly`) configurable — these are structural format signals, not thresholds
- Per-rule Kafka event on create/update/delete
