# Policy Rule Versioning + Complex Conditions Design

**Date:** 2026-06-22
**Backlog item:** #6 — Policy versioning — complex rule logic
**Status:** Approved for implementation

---

## Goal

Extend the existing policy rule system with:
1. **OR-group condition composition** — conditions grouped with AND within a group, OR across groups
2. **7 condition types** — 2 existing + 5 new, each with configurable params
3. **In-place versioning with snapshot history** — full audit trail of edits without changing the live rule model
4. **Dry-run evaluation** — inline test panel in the rule editor; evaluates the unsaved rule against hand-entered context, shows per-group/per-condition pass/fail detail

---

## Architecture

### Data model

No changes to `policy_rules` column structure. The existing `conditions JSONB` column adopts a new schema. One new table is added for history snapshots.

**`conditions` JSONB — new shape:**

```json
{
  "groups": [
    {
      "conditions": [
        { "type": "TIME_WINDOW", "start": "22:00", "end": "06:00" },
        { "type": "ACCOUNT_AGE_DAYS", "max": 7 }
      ]
    },
    {
      "conditions": [
        { "type": "FLAGGED_COUNT", "min": 3, "windowDays": 30 }
      ]
    }
  ]
}
```

Evaluation: groups are OR'd (stop on first passing group); conditions within a group are AND'd (all must pass). Rules with no `groups` key (old flat-object format) are treated as fully-matching — backward compatible.

**New `policy_rule_history` table (Liquibase `006-policy-rule-history.xml`):**

```sql
CREATE TABLE policy_rule_history (
  id           UUID PRIMARY KEY,
  rule_id      VARCHAR(36) NOT NULL,
  tenant_id    UUID NOT NULL,
  snapshot     JSONB NOT NULL,       -- full rule JSON before this edit
  edited_by    VARCHAR(64),          -- username from JWT
  edited_at    TIMESTAMPTZ NOT NULL,
  rule_version INTEGER NOT NULL      -- rule_version at snapshot time
);
CREATE INDEX idx_prh_rule_id ON policy_rule_history(rule_id);
CREATE INDEX idx_prh_tenant ON policy_rule_history(tenant_id);
```

On every `PUT /api/policy-rules/{id}`: read current row → write snapshot to history → overwrite rule. No snapshot on DELETE.

---

## Condition Types

| Type | Params | Notes |
|---|---|---|
| `TIME_WINDOW` | `start` (HH:mm), `end` (HH:mm) | Overnight wrap-around supported. Existing. |
| `MIN_THREAD_LENGTH` | `min` (int) | Existing. |
| `ACCOUNT_AGE_DAYS` | `max` (int) | Sender account age in days. Requires age from tdlib message metadata. |
| `MESSAGE_LANGUAGE` | `languages` (list), `mode` (INCLUDE/EXCLUDE) | Language code list e.g. `["en","de"]`. |
| `GROUP_SIZE` | `min` (int) | Number of group members. |
| `MESSAGE_LENGTH` | `min` (int, optional), `max` (int, optional) | Character count. Either bound is optional. |
| `FLAGGED_COUNT` | `min` (int), `windowDays` (int) | Sender flagged decisions in the last N days, queried from `policy_decisions` table. |

---

## Evaluation Engine

### ConditionEvaluator interface

```java
public interface ConditionEvaluator {
    ConditionType type();
    boolean evaluate(Map<String, Object> params, EvaluationContext ctx);
}
```

One `@Component` per type, auto-discovered into a `Map<ConditionType, ConditionEvaluator>` registry bean. Adding a new type requires only a new class — no changes to existing code.

### EvaluationContext

```java
public record EvaluationContext(
    String intent,
    double confidence,
    String language,
    int threadLength,
    int groupSize,
    int messageLength,
    int senderAccountAgeDays,
    int senderFlaggedCount,
    int senderFlagWindowDays,
    ZonedDateTime now
) {}
```

All fields populated by `PolicyEvaluationService` before calling the condition registry. Fields unavailable from the message (e.g. `senderFlaggedCount`) are fetched from DB once per evaluation, cached for the duration of a single message evaluation.

### Updated evaluation loop

```
for each rule (priority order):
  1. intent match + confidence range (unchanged)
  2. if conditions.groups is empty/absent → pass (backward compat)
  3. for each group:
       if ALL conditions in group pass → rule matches, stop
  4. if no group passed → rule does not match
```

### Dry-run endpoint

`POST /api/policy-rules/dry-run` (policy-engine, proxied via admin-api)

- Request: `{ rule: PolicyRuleConfig, context: EvaluationContext }`
- Response: `DryRunResult` with `matched`, `matchedGroupIndex`, `action`, `groupResults[]` (each with `conditionResults[]` containing type, passed, detail string)
- No side effects — no decision persisted, no Kafka publish
- Tenant-authenticated (same JWT filter as all other endpoints)

---

## Admin UI

### Files changed

| Action | File | Responsibility |
|---|---|---|
| Modify | `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.jsx` | Wire condition builder + dry-run panel into rule editor modal; add History tab |
| Create | `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/ConditionGroupBuilder.jsx` | OR-group condition editor component |
| Create | `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/ConditionGroupBuilder.module.css` | Styles |
| Create | `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/DryRunPanel.jsx` | Dry-run test inputs + result display |
| Create | `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/DryRunPanel.module.css` | Styles |
| Create | `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/RuleHistoryTab.jsx` | History table + diff viewer |
| Create | `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/RuleHistoryTab.module.css` | Styles |
| Modify | `emcip-admin-ui/src/main/frontend/src/api/policyRules.js` | Add `dryRun(rule, context)` and `getHistory(ruleId)` methods |

### ConditionGroupBuilder

- Renders a stacked list of groups separated by `— OR —` labels
- Each group has a list of condition rows and an `+ Add condition` button
- Each condition row: type `<select>` + dynamic param inputs (swapped inline on type change) + remove `✕`
- `+ Add OR group` button at the bottom
- State: `groups: [{ conditions: [{ type, ...params }] }]` — mirrors the JSONB shape exactly
- All design system: `border-radius: 0`, CSS Modules, design tokens

### DryRunPanel

- Collapsible panel below the condition builder (collapsed by default, expands on `▶ Test this rule` click)
- Input fields for all `EvaluationContext` fields (intent select, confidence number input, language text, thread length, group size, message length, account age, flagged count + window days)
- `Run dry-run` button → calls `policyRules.dryRun(currentRule, context)` with the unsaved rule as-is
- Result area: per-group pass/fail with per-condition detail rows; overall match badge (`MATCH` green / `NO MATCH` red)
- Operates on the **current form state** (unsaved rule), not the DB version

### RuleHistoryTab

- Second tab in the rule editor modal (`Edit Rule` | `History (N)`)
- `DataTable` showing: version transition (e.g. `v3 → v4`), edited_by, edited_at
- `View diff` button per row: opens an inline diff drawer showing JSON side-by-side (before snapshot vs. current rule)
- No rollback UI — operator re-edits manually if needed

---

## API Changes

### Policy Engine (`emcip-policy-engine`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/policy-rules/dry-run` | Evaluate unsaved rule against provided context; no side effects |
| `GET` | `/api/policy-rules/{id}/history` | Return list of `PolicyRuleHistory` snapshots for a rule, ordered by `edited_at` DESC |

`PUT /api/policy-rules/{id}` gains snapshot-on-save behaviour (no path change).

### Admin API (`emcip-admin-api`)

`PolicyEngineClient` gains two new proxy methods for `dry-run` and `/{id}/history`.

---

## Testing

- `ConditionEvaluatorTest` — unit test per evaluator (7 classes)
- `PolicyEvaluationServiceTest` — OR-group logic: single group, multi-group OR, empty groups (backward compat), all-fail case
- `DryRunControllerTest` — matched/unmatched result, per-condition detail strings
- `PolicyRuleHistoryTest` — snapshot written on PUT; not written on DELETE; tenant-filtered on GET
- Frontend: no automated tests (consistent with project pattern); dry-run panel manually tested via the test plan in the implementation plan

---

## Out of Scope

- Rollback UI (operator re-edits manually; history is read-only)
- History pruning / retention policy (keep indefinitely for now)
- Full expression tree (AND/OR/NOT nesting beyond one level)
- Simulation page integration (deferred; dry-run inline covers the core need)
