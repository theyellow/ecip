# Tenants Page v2 Redesign — Design Spec

**Goal:** Convert the Tenants page from a hand-built table to the shared DataTable component, applying v2 design tokens and matching the handoff prototype.

**Approach:** Same DataTable conversion pattern validated with the Groups page redesign (PR #90).

---

## 1. DataTable Conversion

Replace the hand-built `<table>` with `<DataTable>`. No new shared components needed.

### Columns

| Column | Source field | Mono? | Render |
|--------|-------------|-------|--------|
| ID | `id` | yes | `id.slice(0, 8)…` (truncated UUID) |
| Name | `name` | no | plain text |
| Description | `description` | no | dash fallback (`v || '—'`) |
| LLM Override | `llmModelOverride` | yes | dash fallback |
| Created | `createdAt` | yes | `new Date(v).toLocaleDateString()` |

### System ID Line

`⬡ tenants · {count} registered` — derived from loaded data (unfiltered count).

### Primary Action

`+ Create Tenant` button via DataTable's `addLabel` / `onAdd` props.

### Delete

DataTable's `onDelete` prop — confirm dialog (`Delete tenant "{name}"?`), then `api.remove(id)`.

No `onEdit` — the current API (`tenantsApi`) has no update endpoint, and the v2 prototype doesn't show an edit modal.

---

## 2. Create Tenant Modal

Same 3 fields as current production, wrapped in v2 field/input styles:

- **Name** (required) — text input
- **Description** — textarea, 3 rows
- **LLM Model Override** — text input, placeholder `e.g. gpt-4o, claude-3-5-sonnet`

Modal title: `Create Tenant`.

---

## 3. CSS

Replace `Tenants.module.css` with minimal v2 styles for the modal form fields only (DataTable handles table styling):

- `.field` — flex column with gap, label styling (10px uppercase tracked)
- `.input` — v2 input styling (zero radius, mono font, gold focus glow)
- Error banner as inline styles with `role="alert"` (same pattern as Groups)

---

## 4. Testing

Existing 8 tests should continue passing. Minor updates may be needed if assertions reference removed DOM structure (old hand-built table classes). Key behaviors to verify:
- Empty state rendering
- Tenant row data display
- Create modal open/submit flow
- Delete with confirm
- Error display

---

## 5. Excluded

- No edit modal (API doesn't support tenant updates)
- No filters (tenant list is small config data)
- No new shared components
