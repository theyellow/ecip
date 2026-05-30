# Telegram Page v2 Redesign — Design Spec

**Goal:** Restyle the Telegram page with v2 design tokens. No structural changes — the page keeps its hand-built table (DataTable doesn't support expandable rows or custom multi-button action columns).

**Approach:** Replace v1 tokens with v2 equivalents in CSS. Add system-id line. Restyle modals with v2 field/input pattern. Use SectionLabel for groups panel header.

---

## 1. Page Header

Add v2 page header with system-id line:

`⌘ tdlib-adapter · {count} accounts`

Replace the hand-built header div with the standard v2 page header pattern (same as DataTable pages but without DataTable).

---

## 2. Accounts Table

Keep the hand-built table. Restyle with v2 tokens:

**Columns (unchanged):** Name, Phone (mono), Status (Badge), Actions (Groups/Auth/Logout/Delete buttons)

**Styling changes:**
- Table: `border-collapse: collapse`, 1px `var(--border)`, no border-radius
- Header row: 10px uppercase tracked `var(--font-display)` gold — matching DataTable `th` style
- Row hover: `rgba(212, 168, 73, 0.04)` — matching DataTable
- Mono cells: `var(--font-mono)` 12px `var(--fg-2)`
- Empty state: italic `var(--fg-3)`

**Status Badge:** Already uses `<Badge>` with correct variant map — no change needed.

**Actions column:** Keep all 4 buttons. Already uses `<Button>` component — no change needed.

---

## 3. Groups Panel (expanded row)

Restyle the inline groups panel:

- Use `SectionLabel` component for "Watched Groups" header (with Discover button as `aside`)
- Inner table: same v2 table styling as outer table
- Empty state: italic `var(--fg-3)` — "No groups watched. Use Discover to add groups."

---

## 4. Add Account Modal

Already uses shared `Modal` component. Restyle form fields with v2 `.field`/`.input` pattern:

- Display Name (text)
- Phone Number (text)
- Advanced toggle (collapsible): API ID (number), API Hash (text)
- Advanced hint text below toggle

Use `Modal`'s built-in `onSubmit` instead of manual Save/Cancel buttons at the bottom.

---

## 5. Auth Wizard Modal

Already uses shared `Modal`. Restyle:
- Code step: text describing what to enter, v2 styled input
- Password step: same pattern
- Error display within modal

---

## 6. Discover Modal

Already uses shared `Modal`. Restyle:
- Inner table with v2 tokens
- Refresh button in header
- Loading/empty/error states with v2 tokens

---

## 7. CSS

Replace `Telegram.module.css` with v2 styles. Key changes:

- All `var(--text-muted)`, `var(--text-primary)`, `var(--text-secondary)` → v2 equivalents (`--fg-1`, `--fg-2`, `--fg-3`)
- All `var(--bg-secondary)` → `var(--bg-input)` or `var(--bg-card)`
- All `border-radius: 0.35rem/0.5rem/6px/8px` → `border-radius: 0`
- Table header: v2 display font style
- `.error` → inline styles with `role="alert"` (v2 signal tokens)
- `.input` → v2 input pattern (mono font, gold focus glow)
- `.field`/`.label` → v2 field pattern (10px uppercase tracked)

---

## 8. Testing

6 existing tests. Expected changes:
- "Add Account" button text changes to "+ Add Account" — test queries `/add account/i` regex which will match
- Modal form uses `Modal`'s `onSubmit` — the "Save" button becomes Modal's built-in submit button, still queryable by `/save/i`
- No structural DOM changes that would break expand/discover/watch flow tests

All 6 tests should continue passing with minimal or no updates.

---

## 9. Excluded

- No DataTable conversion (expandable rows + multi-action columns not supported)
- No backend changes
- No new features
- No new shared components
