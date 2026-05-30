# ModerationRules Page v2 Redesign — Design Spec

**Goal:** Convert the ModerationRules page from a hand-built table to the shared DataTable component with v2 design tokens.

**Approach:** Same DataTable conversion pattern as Groups (PR #90), Tenants, AuditLog, PolicyRules (PR #91).

---

## 1. DataTable Conversion

Replace the hand-built `<table>` with `<DataTable>`. No new shared components needed.

### Columns

| Column | Source field | Mono? | Render |
|--------|-------------|-------|--------|
| Name | `name` | no | plain text |
| Tenant | `tenantId` | yes | `id.slice(0,8)…`, dash fallback |
| Type | `ruleType` | no | Badge gray |
| Pattern | `pattern` | yes | truncated with `title` tooltip (max-width via CSS class) |
| Severity | `severity` | no | Badge: LOW=gray, MEDIUM=yellow, HIGH=red |
| Action | `action` | no | Badge: FLAG=blue, WARN=yellow, MUTE=yellow, BAN=red, DELETE=red, ESCALATE=gray |
| Enabled | `enabled` | no | Badge: true=green "ON", false=gray "OFF" |

### System ID Line

`⊘ moderation-service · {count} rules`

### Primary Action

`+ Create Rule` button via DataTable's `addLabel` / `onAdd` props.

### Row Click

Opens edit modal (`onEdit`).

### Delete

Via DataTable's `onDelete` prop — confirm dialog (`Delete rule "{name}"?`), then `api.remove(id)`.

---

## 2. Edit/Create Modal

Keep all existing form fields with v2 field/input styles:

- **Rule Name** (required, disabled on edit) — text input
- **Rule Type** (select: KEYWORD / REGEX / LENGTH)
- **Pattern** (required) — text input for KEYWORD/REGEX, number input for LENGTH. Dynamic hint `<p>` below showing `PATTERN_HINT[ruleType]`
- **Severity** (select: LOW / MEDIUM / HIGH)
- **Action** (select: FLAG / WARN / MUTE / BAN / DELETE / ESCALATE)
- **Enabled** (select Yes/No, only shown on edit)
- **Tenant** (read-only display — shows `currentTenant.name` for new rules, truncated `tenantId` for existing)

Modal title: `Edit Rule` / `Create Rule`.

---

## 3. CSS

Replace `ModerationRules.module.css` with v2 styles:

- `.field` — flex column with gap, label styling (10px uppercase tracked)
- `.input` — v2 input styling (zero radius, mono font, gold focus glow)
- `.hint` — small mono hint text below pattern input
- `.pattern` — truncated mono pattern in table column (via DataTable render, not table-specific CSS — but still needed as a class for the `<span>` wrapper)

Error banner: inline styles with `role="alert"` (same pattern as all other v2 pages).

---

## 4. Testing

10 existing tests. Expected breakages from the conversion:

1. **"displays rule row with badges"** — asserts `✓` for enabled. New render uses Badge "ON". Update to expect `ON`.
2. **"shows em-dash for disabled rule"** — asserts `—` for disabled. New render uses Badge "OFF". Update to expect `OFF`.
3. **"opens Edit Rule modal with prefilled values"** — currently clicks an Edit button. Now uses row click via DataTable's `onEdit`. Update to click the row or a text element within it.
4. **"updates a rule and reloads list"** — same Edit button issue.
5. **"deletes a rule after confirmation"** / **"does not delete when confirmation is cancelled"** — Delete button now rendered by DataTable. Should still be queryable by button role with name /delete/i.

---

## 5. Excluded

- No filters (moderation rules are small config data)
- No new shared components
- No backend changes
- Design handoff's ML toxicity model (categories, thresholds) is a future feature — not implemented here
