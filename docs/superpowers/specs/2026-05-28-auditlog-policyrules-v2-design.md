# AuditLog + PolicyRules v2 Redesign — Design Spec

**Goal:** Convert both pages from hand-built tables to the shared DataTable component with v2 design tokens.

**Approach:** Same DataTable conversion pattern as Groups (PR #90) and Tenants (PR #91).

---

## 1. AuditLog Page

### DataTable Conversion

The AuditLog is read-only — no create/edit/delete. It uses DataTable for the table rendering but keeps its own filters (event type + page size) and its custom DetailsModal for viewing event details with JSON blocks.

**Columns:**

| Column | Source field | Mono? | Render |
|--------|-------------|-------|--------|
| Timestamp | `createdAt` | yes | `toLocaleString()`, dash fallback |
| Event Type | `eventType` | no | plain text |
| Source | `sourceService` | yes | dash fallback |
| Action | `action` | no | dash fallback |
| Resource | `resourceId` | yes | dash fallback |
| Outcome | `outcome` | no | Badge: OK=green, BLOCK=red, else gray (from v2 handoff) |
| Details | `details` | no | Clickable truncated preview, opens DetailsModal |

**System-id line:** `◎ audit-service · {total} events total`

**Filters:** Two dropdowns passed via DataTable's `filters` prop:
1. Event type filter: "All types" + MESSAGE_RECEIVED / MESSAGE_CLASSIFIED / POLICY_DECISION / MODERATION_ACTION
2. Page size: 25 / 50 / 100 / 200

**No `addLabel`, no `onEdit`, no `onDelete`** — read-only.

**Row click:** Opens DetailsModal (via `onEdit`).

### DetailsModal

Restyle the existing custom DetailsModal to v2:
- Use shared Modal component instead of custom overlay/modal divs
- Use SectionLabel for "Details" and "Raw Event" section headers (with CopyButton as `aside`)
- Use v2 metaGrid/metaLabel/metaValue styles (same pattern as Groups edit modal)
- Use v2 jsonBlock styles (code-bg token, mono font, zero radius)

### CSS

Replace `AuditLog.module.css` with v2 styles for:
- `.detailsLink` — clickable details preview
- `.metaGrid`, `.metaLabel`, `.metaValue` — metadata grid in details modal
- `.jsonBlock`, `.jsonBlock pre` — JSON display block
- `.copyBtn`, `.copied` — copy button

Error banner: inline styles with `role="alert"` (same pattern as Groups/Tenants).

### Loading State

The current page shows a "Loading..." row. DataTable doesn't support this natively. Pass the loaded events as `rows` — when loading, `rows` will be `[]` and the empty state shows. This is acceptable since audit events load fast.

---

## 2. PolicyRules Page

### DataTable Conversion

The v2 handoff shows different columns than current production. The production page has more fields (effectiveFrom, effectiveTo, tenant) that the v2 handoff omits. **Keep all production fields** — the handoff is a design reference, not a feature spec. Add the v2 visual enhancements (action badge variants, active status badge) on top of existing columns.

**Columns:**

| Column | Source field | Mono? | Render |
|--------|-------------|-------|--------|
| Name | `name` | no | plain text |
| Intent | `targetIntent` | no | Badge gray |
| Action | `action` | no | Badge: FLAG=blue, WARN=yellow, MUTE=yellow, BAN=red, DELETE=red, ESCALATE=gray |
| Priority | `priority` | yes | plain number |
| From | `effectiveFrom` | yes | `toLocaleDateString()`, dash fallback |
| To | `effectiveTo` | yes | `toLocaleDateString()`, dash fallback |

**System-id line:** `⚖ policy-rules · {count} rules`

**Row click:** Opens edit modal (`onEdit`).

**Delete:** Via DataTable `onDelete` + confirm dialog.

**Primary action:** `+ Create Rule` button.

### Edit/Create Modal

Restyle the existing RuleModal with v2 field/input styles. Keep all existing form fields:
- Rule Name (disabled on edit)
- Target Intent
- Action (select)
- Priority (number)
- Description (textarea)
- Effective From (datetime-local)
- Effective To (datetime-local)
- Tenant (select, loaded from tenantsApi)

### History Modal

Keep the existing HistoryModal but restyle with v2 tokens. Use shared Modal (already used). Restyle history items with v2 mono/border tokens.

### CSS

Replace `PolicyRules.module.css` with v2 field/input styles + history item styles.

---

## 3. Testing

**AuditLog:** 7 existing tests. The event type and page size filter tests query by `combobox` role — these need updating since filters move into DataTable's filter row. The "displays event row" test uses a mock EVENT with `entityId` field that doesn't match the actual column structure — this is likely the pre-existing failure.

**PolicyRules:** 1 existing test (tenant dropdown). Should continue working since the modal structure is preserved.

---

## 4. Excluded

- No pagination controls (AuditLog uses page size but not page navigation — keep as-is)
- No new shared components
- No backend changes
