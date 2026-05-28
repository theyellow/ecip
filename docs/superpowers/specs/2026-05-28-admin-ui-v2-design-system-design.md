# Admin UI v2 Design System Integration — Design Spec

**Goal:** Integrate the v2 design handoff into the production admin-ui: replace the token system, restyle shared components, add a DataTable component, place project guidance, and redesign the Groups page as proof-of-concept.

**Approach:** Token replacement + component restyle (Approach A from brainstorming). Single token system from day one, v1 compat aliases for unredesigned pages.

---

## 1. Token System

Replace `src/main/frontend/src/theme/variables.css` with the v2 token system. The new file merges the handoff's `colors_and_type.css` (base palette, type scale, spacing, radii) and `tokens.css` (semantic tokens, theme switching, atmosphere) into a single production file.

### Token Rename Map (v1 → v2)

| v1 token | v2 token |
|---|---|
| `--bg-primary` | `--bg-app` |
| `--bg-secondary` | `--bg-app-soft` |
| `--bg-card` | `--bg-card` |
| `--text-primary` | `--fg-1` |
| `--text-secondary` | `--fg-2` |
| `--text-muted` | `--fg-3` |
| `--accent` | `--accent` |
| `--accent-hover` | `--accent-hover` |
| `--accent-text` | `--fg-on-accent` |
| `--accent-secondary` | `--accent-2` |
| `--border` | `--border` |
| `--shadow` | `--shadow-card` |
| `--sidebar-bg` | `--sidebar-bg` |
| `--sidebar-text` | `--sidebar-fg` |
| `--sidebar-active-bg` | `--sidebar-bg-active` |
| `--sidebar-active-text` | `--sidebar-fg-active` |
| `--badge-green-bg/text` | `--signal-ok-bg/fg` |
| `--badge-blue-bg/text` | `--signal-info-bg/fg` |
| `--badge-yellow-bg/text` | `--signal-warn-bg/fg` |
| `--badge-red-bg/text` | `--signal-stop-bg/fg` |
| `--badge-gray-bg/text` | `--signal-mute-bg/fg` |
| `--table-header-bg` | `--rule` |
| `--table-row-hover` | `--accent-soft` |
| `--input-bg` | `--bg-input` |

### New Tokens (not in v1)

- **Semantic surfaces**: `--bg-card-solid`, `--bg-sunken`, `--accent-soft`, `--border-strong`, `--rule`
- **Typography**: `--font-display`, `--font-body`, `--font-mono`, `--fs-xs` through `--fs-4xl`, `--lh-tight/snug/normal/loose`, `--tracking-display/label/caps`
- **Spacing**: `--sp-0` through `--sp-9` (4px grid)
- **Radii**: `--r-xs` through `--r-pill`
- **Orb / glow**: `--orb-core`, `--orb-mid`, `--orb-glow`
- **Sidebar**: `--sidebar-fg-muted`, `--sidebar-bg-hover`
- **Shadows**: `--shadow-card`, `--shadow-modal`
- **Atmosphere / sky**: `--sky-grad-*`, `--fog-tint*`, `--orb-ring*`, `--mono-fill/grad-*`
- **Code**: `--code-bg`, `--code-fg`, `--code-block-bg`, `--code-block-fg`

### v1 Compatibility

A `/* v1 compat aliases */` block at the bottom of `variables.css` maps old names to new:

```css
/* v1 compat — remove when all pages are redesigned */
:root {
  --bg-primary: var(--bg-app);
  --bg-secondary: var(--bg-app-soft);
  --text-primary: var(--fg-1);
  --text-secondary: var(--fg-2);
  --text-muted: var(--fg-3);
  --accent-text: var(--fg-on-accent);
  --shadow: var(--shadow-card);
  --sidebar-text: var(--sidebar-fg);
  --sidebar-active-bg: var(--sidebar-bg-active);
  --sidebar-active-text: var(--sidebar-fg-active);
  --badge-green-bg: var(--signal-ok-bg);
  --badge-green-text: var(--signal-ok-fg);
  --badge-blue-bg: var(--signal-info-bg);
  --badge-blue-text: var(--signal-info-fg);
  --badge-yellow-bg: var(--signal-warn-bg);
  --badge-yellow-text: var(--signal-warn-fg);
  --badge-red-bg: var(--signal-stop-bg);
  --badge-red-text: var(--signal-stop-fg);
  --badge-gray-bg: var(--signal-mute-bg);
  --badge-gray-text: var(--signal-mute-fg);
  --table-header-bg: var(--rule);
  --table-row-hover: var(--accent-soft);
  --input-bg: var(--bg-input);
}
```

This means existing pages continue working with no CSS changes. The mechanical rename pass updates shared components and the Groups page to use v2 names directly.

---

## 2. Font Setup

Add two variable font files to `src/main/frontend/public/fonts/`:
- `Cinzel-Variable.ttf` (400–900) — source: `documentation/fonts/` or Google Fonts
- `SourceCodePro-Variable.ttf` (200–900) — source: `documentation/fonts/` or Google Fonts

Declare `@font-face` rules at the top of the new `variables.css`. Inter stays as the system-ui fallback — no bundle needed.

Update `index.css` with global typography rules from the handoff's `colors_and_type.css`:
- `h1`–`h4`: Cinzel, uppercase, tracked, gold
- `body`: Inter/system-ui
- `code`/`pre`: Source Code Pro
- `a`, `:focus-visible`, `::selection` styles

---

## 3. Shared Components

### Restyle Existing (same JSX API, updated CSS)

**Button**:
- `border-radius: 0`
- Primary: gradient fill `linear-gradient(180deg, var(--orb-core) 0%, var(--accent) 100%)`, text `var(--fg-on-accent)`, border `var(--border-strong)`
- Secondary: transparent, `var(--fg-1)` text, `var(--border)`; hover → `var(--accent-soft)` bg, gold border
- Danger: transparent, `var(--signal-stop-fg)` text/border; hover → faint red bg
- Font: `var(--font-display)` 11px uppercase tracked 0.14em
- `:active` darkens only — no `transform: scale()`
- Disabled: `opacity: 0.4`, `cursor: not-allowed`

**Badge**:
- Keep `border-radius: var(--r-pill)`
- Font: `var(--font-mono)` 10px uppercase tracked 0.08em
- Rename variant tokens: `green` → `--signal-ok-*`, `blue` → `--signal-info-*`, `yellow` → `--signal-warn-*`, `red` → `--signal-stop-*`, `gray` → `--signal-mute-*`
- Add `violet` variant using `--accent-2` derived colors

**Modal**:
- `border-radius: 0`
- Overlay: `rgba(0,0,0,0.55)`, `backdrop-filter: blur(2px)`
- Card: 520px (max 95vw, max 90vh), `var(--bg-card)`, 1px `var(--border)`, `backdrop-filter: blur(16px)`
- Title: `var(--font-display)` 12px tracked 0.18em uppercase gold
- Close button: `✕`
- Footer: right-aligned Cancel (secondary) + Submit (primary)

### Add New

**DataTable** (`src/main/frontend/src/components/DataTable/DataTable.jsx`):

Props:
```
title: string               — page heading (rendered ALL CAPS via CSS)
systemId?: string            — mono subheading (e.g., "◈ tdlib-adapter · 5 watched")
addLabel?: string            — primary action button text
onAdd?: () => void           — handler for primary action
columns: Column[]            — { key, label, render?, mono?, width? }
rows: any[]                  — data array
rowKey?: (row) => string     — defaults to row.id
onEdit?: (row) => void       — makes rows clickable
onDelete?: (row) => void     — adds a Delete column
filters?: Filter[]           — { value, onChange, options: [{value, label}] }
emptyText?: string           — empty state message
```

Structure:
- Page header: `<h2>` title + system-id + filters + add button
- `<table>`: full-width, `border-collapse: collapse`, 1px brass border, no radius
- Header row: `var(--font-display)` 10px tracked 0.18em uppercase gold
- Row hover: `var(--accent-soft)` tint
- Mono cells: `var(--font-mono)` 12px `var(--fg-2)`
- Empty state: centered, italic, `var(--fg-3)`
- Clickable rows: `cursor: pointer` when `onEdit` is set

CSS Module: `DataTable.module.css`

**SectionLabel** (`src/main/frontend/src/components/SectionLabel/SectionLabel.jsx`):

Renders `<div className={styles.label}><span>— {children} —</span></div>`.

CSS: `var(--font-mono)` 10px uppercase gold tracked 0.18em, centered.

---

## 4. Project Guidance

Place adapted handoff `CLAUDE.md` at `emcip-admin-ui/CLAUDE.md`.

Adaptations from the handoff original:
- "Source of truth" table points at production paths (`src/main/frontend/src/theme/variables.css`, `src/main/frontend/src/components/`, etc.) instead of `design_references/`
- Remove "drop this file" instructions — it's already in place
- Keep all 10 hard rules verbatim
- Keep token reference, component recipes, icon table, content rules verbatim
- Keep the "what to do when adding a new page" recipe
- Note that `design_handoff_emcip_admin/` no longer exists — the CLAUDE.md is self-contained

---

## 5. Groups Page Redesign

Replace the current hand-built Groups page with a DataTable-driven implementation.

### Layout

```
page-header: "GROUPS" + system-id + moderation-level filter
DataTable: columns from API data
GroupEditModal: read-only metadata grid + moderation level select
```

### Columns

| Column | Source field | Mono? | Render |
|---|---|---|---|
| Group | `name` | no | plain text |
| Chat ID | `telegramChatId` | yes | plain text |
| Moderation | `moderationLevel` | no | `<Badge variant={...}>` |
| Auto-respond | `autoRespond` | no | `<Badge>` yes/no |
| Description | `description` | no | truncated, `—` fallback |

The v2 prototype shows additional columns (account, members, language, firstSeen) that the current API does not return. These are omitted — no backend changes in this PR.

### System ID Line

`◈ groups · {count} watched` — derived from loaded data.

### Edit Modal

- Title: `Edit · {group.name}`
- Read-only metadata grid: Chat ID, Auto-respond, Tenant
- Editable: moderation level (select), name, description, welcome message
- Keep existing form fields that the API supports; don't remove functionality

### Delete

Handled by DataTable's `onDelete` prop — confirm dialog, then `api.remove()`.

### Moderation Level Filter

Dropdown in the DataTable filter row: "All moderation levels" + `LOW` / `MEDIUM` / `HIGH` / `STRICT`. Client-side filter on the loaded data.

---

## 6. Cleanup

Delete `emcip-admin-ui/design_handoff_emcip_admin/` at the end. The permanent artifacts are:
- `emcip-admin-ui/CLAUDE.md` (project guidance)
- `src/main/frontend/src/theme/variables.css` (v2 tokens)
- `src/main/frontend/public/fonts/` (font files)
- Updated components and pages

---

## 7. Testing

- All existing Vitest tests must pass after token rename (tests don't depend on CSS values, so this is expected to be green)
- New test: `DataTable.test.jsx` — renders columns, handles empty state, calls onEdit/onDelete, renders filters
- Groups page: existing `Groups.test.jsx` updated if its assertions reference changed class names or structure

---

## 8. Excluded (future backlog items)

The following items are added to `BACKLOG.md` as follow-on work:

- **Admin UI v2: page redesigns** (S each × 9 pages) — Flags, Tenants, PolicyRules, ModerationRules, AuditLog, Telegram, AIConfig, Simulate, Users, Login/Sidebar
- **Admin UI v2: new components** (S) — SegmentedControl, ChipRow, ReplyComposer (built when Flags page is redesigned)
- **Admin UI v2: remove v1 compat aliases** (XS) — after all pages redesigned
