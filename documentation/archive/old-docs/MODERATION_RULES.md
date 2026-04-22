# Moderation Rules

This document describes the moderation rule system used by `emcip-moderation-service`.

## Overview

Rules are stored in the `moderation_rules` table and evaluated in-memory by `RuleEvaluationService`.
Rules are loaded at startup and refreshed every 5 minutes via a `@Scheduled` task.

Each incoming message text is tested against every enabled rule in insertion order.
The first matching rule determines the outcome; subsequent rules are not evaluated.

---

## Rule Types

Three rule types are supported, enforced by a `CHECK` constraint in the database schema.

### KEYWORD

Performs a case-insensitive substring match against the full message text.

The `pattern` field is the literal string to search for.

```sql
INSERT INTO moderation_rules (name, rule_type, pattern, severity, action)
VALUES ('block-spam', 'KEYWORD', 'spam', 'HIGH', 'FLAG');
```

The evaluation logic:

```
text.toLowerCase().contains(pattern.toLowerCase())
```

### REGEX

Tests the message text against a Java regular expression. The pattern is automatically
wrapped in `(?i).*<pattern>.*`, so the match is case-insensitive and partial (does not
need to cover the full string).

```sql
INSERT INTO moderation_rules (name, rule_type, pattern, severity, action)
VALUES ('detect-urls', 'REGEX', 'https?://\S+', 'MEDIUM', 'FLAG');
```

The evaluation logic:

```
text.matches("(?i).*" + pattern + ".*")
```

Note: The `pattern` must be a valid Java regex. Anchors (`^`, `$`) are not required
because the wrapper already handles partial matching.

### LENGTH

Triggers when the message length (in characters) exceeds the integer value stored in
`pattern`.

```sql
INSERT INTO moderation_rules (name, rule_type, pattern, severity, action)
VALUES ('excessive-length', 'LENGTH', '2000', 'LOW', 'FLAG');
```

The evaluation logic:

```
text.length() > Integer.parseInt(pattern)
```

---

## Severity Levels

| Severity | Meaning |
|----------|---------|
| `LOW`    | Informational; the message is unusual but not harmful. |
| `MEDIUM` | Requires attention; the message may violate community norms. |
| `HIGH`   | Serious violation; the message likely needs immediate action. |

Severity is informational — it is carried through in the `ModerationFlagEvent` so
downstream consumers (audit-service, future admin review) can prioritise accordingly.
It does not change how the rule is matched.

---

## Action Values

| Action  | Meaning |
|---------|---------|
| `FLAG`  | Record a moderation event but allow the message to pass through. |
| `BLOCK` | Prevent the message from being forwarded or acted on. |
| `WARN`  | Emit a warning notification to the group administrator. |

The action is emitted as part of `ModerationFlagEvent` and interpreted by consumers.
`emcip-audit-service` persists all flagged events regardless of action type.

---

## Seed Rules

The Liquibase changeset `001-seed-default-rules` inserts two rules on first startup:

| Name               | Type      | Pattern | Severity | Action |
|--------------------|-----------|---------|----------|--------|
| `profanity-block`  | `KEYWORD` | `spam`  | `HIGH`   | `FLAG` |
| `excessive-length` | `LENGTH`  | `2000`  | `LOW`    | `FLAG` |

These are intentionally minimal. Operators are expected to insert domain-specific rules
before production use.

---

## Adding New Rules

### Via SQL

Connect to the PostgreSQL instance and insert directly into `moderation_rules`:

```sql
-- Add a KEYWORD rule
INSERT INTO moderation_rules (name, rule_type, pattern, severity, action, enabled)
VALUES ('block-phishing', 'KEYWORD', 'click here to verify', 'HIGH', 'BLOCK', true);

-- Add a REGEX rule
INSERT INTO moderation_rules (name, rule_type, pattern, severity, action, enabled)
VALUES ('detect-phone-numbers', 'REGEX', '\+?\d[\d\s\-]{7,}\d', 'MEDIUM', 'FLAG', true);

-- Disable an existing rule without deleting it
UPDATE moderation_rules SET enabled = false WHERE name = 'excessive-length';
```

The `RuleEvaluationService` refreshes its in-memory cache every 5 minutes, so new rules
become active within that window without a service restart.

### Via Admin API (planned)

A dedicated `/api/rules` endpoint in `emcip-admin-api` is planned for Phase 4. Until
that endpoint is implemented, the SQL approach is the authoritative method.

---

## Escalation Path

```
Message arrives via Kafka (topic: messages.raw)
    |
    v
ModerationService evaluates all enabled rules
    |
    | (match found)
    v
ModerationFlagEvent published to Kafka (topic: moderation.flags)
    |
    v
emcip-audit-service consumes ModerationFlagEvent
    |   -> Persists as audit_event with event_type = MODERATION_FLAG
    |   -> Carries severity, action, rule name, correlation ID
    |
    v
(Future) Admin review queue in emcip-admin-api
    -> Admins can acknowledge, escalate, or dismiss flagged events
```

`ModerationFlagEvent` fields of interest for escalation:

| Field           | Description |
|-----------------|-------------|
| `correlationId` | Ties the flag back to the originating message. |
| `ruleName`      | Which rule triggered the flag. |
| `severity`      | Informs priority in the review queue. |
| `action`        | The intended consequence (`FLAG`, `BLOCK`, `WARN`). |
| `ruleType`      | `KEYWORD`, `REGEX`, or `LENGTH`. |

---

## Schema Reference

See the Liquibase migration at:
`emcip-moderation-service/src/main/resources/db/changelog/changes/001-create-moderation-rules.xml`
