# Flags Page v2 Redesign — Design Spec

**Goal:** Restyle the Flags page with v2 design tokens. Introduce SegmentedControl shared component. Convert hand-built modal to shared Modal. No new features.

**Approach:** Replace v1 tokens in CSS, add v2 page header with system-id, convert FlagDetailModal to use shared Modal, replace Group/DM toggle with SegmentedControl, restyle reply section with v2 tokens.

---

## 1. New Shared Component: SegmentedControl

Create `src/components/SegmentedControl/SegmentedControl.jsx` — a general-purpose mode picker.

**Props:** `options` (array of `{ value, label }`), `value` (current selection), `onChange` (callback).

**Styling per CLAUDE.md spec:**
- One bordered strip of display-font buttons
- Active button: `background: var(--accent-soft)`, gold text (`var(--accent)`), `box-shadow: inset 0 -2px 0 var(--accent)` underline
- Inactive: transparent, `var(--fg-2)` text
- Container: `1px solid var(--border)`, `border-radius: 0`
- Font: `var(--font-display)` 10px uppercase tracked 0.14em
- Buttons: padding `6px 14px`, no individual borders (container border only)

---

## 2. Page Header

Replace hand-built `.header` with v2 page header pattern:

- System-id: `⚑ policy-engine · {total} flags`
- Filter selects (Decision, Page size) stay in header right side
- Remove inline `<small>` total count from `<h2>` (system-id shows it)

---

## 3. Table Styling

Keep hand-built table (DataTable can't support clickable-row → detail modal). Restyle with v2 tokens:

- th: `var(--font-body)` 10px uppercase tracked 0.18em, `color: var(--fg-3)`, `background: rgba(212,168,73,0.04)`, border-bottom `var(--rule)`
- td: border-bottom `var(--rule)`, font-size 13px
- Last row: no border-bottom removal (keep `--rule` consistent)
- Row hover: `rgba(212,168,73,0.04)`
- `.mono` cells: `var(--font-mono)` 12px `var(--fg-2)`
- `.message` cells: `var(--fg-2)` max-width 280px ellipsis
- Table border: `1px solid var(--border)`, `border-radius: 0`
- Loading/empty states: `var(--fg-3)` centered

---

## 4. FlagDetailModal → Shared Modal

Replace hand-built overlay/modal/header/close with shared `<Modal title="Flag Detail" onClose={onClose}>`. No `onSubmit` (no footer — the body has its own interactive elements).

Keep the modal body content:
- Detail grid (2-column: label + value)
- Status buttons row
- Reply toggle + reply section

Restyle detail grid labels with v2 tokens:
- Labels: `var(--font-body)` 10px uppercase tracked 0.18em `var(--fg-2)`
- Values: `var(--fg-1)` default, mono values use `var(--font-mono)` 12px `var(--fg-2)`
- Grid gap stays the same

---

## 5. Reply Section

Restyle with v2 tokens. Use SegmentedControl for Group/DM toggle. Use SectionLabel for the "Reply" heading.

**Changes:**
- Replace `▸ Reply` / `▾ Reply` toggle button with `SectionLabel` (always visible) and a collapse toggle
- Replace hand-built Group/DM `targetToggle` with `<SegmentedControl options={[{value:'GROUP',label:'Group'},{value:'DM',label:'DM'}]} />`
- Textarea: v2 `.input` pattern (border-radius: 0, `--bg-input`, `--fg-1`, `--font-mono` 13px, gold focus glow)
- Checkboxes: keep as-is (native checkboxes with labels)
- Account select: v2 `.input` pattern
- Error text: v2 inline error style with `role="alert"`, signal-stop tokens
- Success text: `var(--signal-ok-fg)`

**NOT included (future features):**
- Quote-reply and Silent note modes (need backend support)
- ChipRow component and reply templates
- Character counter footer
- Discard button

---

## 6. Error Styling

Both error locations (page-level and modal reply error) use v2 inline error pattern:
- `color: var(--signal-stop-fg)`
- `background: rgba(248,113,113,0.08)`
- `border: 1px solid rgba(248,113,113,0.25)`
- `padding: 8px 12px`
- `fontFamily: var(--font-mono)`
- `fontSize: 12px`
- `role="alert"`

---

## 7. Testing

No tests exist. Add tests covering:
- Table renders with flag data (decision badge, status badge, timestamp, message)
- Empty state message
- Detail modal opens on row click
- Status change in modal
- Reply section toggle and send
- Decision filter select
- Error display

Test pattern: `vi.mock` for `flagsApi`, `AuthProvider`/`ThemeProvider` wrappers.

---

## 8. Excluded

- Design handoff's full reply composer (4-mode picker, templates, char counter) — future feature work
- ChipRow shared component — future
- No backend changes
