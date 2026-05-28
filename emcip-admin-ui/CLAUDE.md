# EMCIP Admin UI — Claude Code Rules

This file provides project-level guidance for the EMCIP Admin UI React app. It codifies the v2 visual + content system.

---

## What this project is

EMCIP is the **Enterprise Messenger Community Intelligence Platform** — a microservice system that watches Telegram groups as a real TDLib client, classifies intent, and either **Reacts / Summarizes / Moderates / Observes**. This React app is the operator console.

The visual identity is brass-and-ink, hexagonal sigils, a starfield humming behind every screen, narrated by *The Hitchhiker's Guide to the Galaxy* in calm sans. Practical, never decorative; serious, never solemn.

---

## Source of truth

| Concern | Where to look |
|---|---|
| Design tokens (colors, type scale, spacing, radii, shadows) | `src/main/frontend/src/theme/variables.css` |
| Shared components (Button, Badge, Modal, DataTable, SectionLabel) | `src/main/frontend/src/components/` |
| Page implementations | `src/main/frontend/src/pages/*/` |
| Layout (AppShell, Sidebar, SpaceBackground) | `src/main/frontend/src/layout/` |
| Iconography (Unicode glyphs, no icon library) | Sidebar nav definitions in `src/main/frontend/src/layout/Sidebar/Sidebar.jsx`; full table below |
| Voice & copy | section *Content rules* below |

**The prototypes are design references, not production code to copy verbatim.** Recreate them using the React app's established patterns (Vite + React + whatever state / routing / data-fetching it already uses). Match semantics, layout, copy, and tokens — not import paths.

---

## Hard rules (never break)

1. **Use semantic tokens only.** Never write hex values in component CSS / styled components / Tailwind arbitrary values. Reach for `var(--accent)`, `var(--fg-1)`, `var(--bg-card)`, `var(--signal-warn-fg)`, etc. If a needed token doesn't exist, add it to `tokens.css` — don't paper over with a one-off color.
2. **Two brand hues, nothing else.** Gold (`--c-gold-500` / `--accent`) and Violet (`--c-violet-500`). Everything else is parchment, ink, or signal pastels. No new accents.
3. **Display type is Cinzel, uppercase, tracked.** Always `font-family: var(--font-display)`, `text-transform: uppercase`, `letter-spacing: 0.10em` minimum (0.18–0.22em for section labels and page titles). Apply only to headings, page titles, section labels, and button glyphs — **never** to body copy.
4. **No emoji. Anywhere.** Not in copy, not in toasts, not in empty states. Unicode geometric/symbol glyphs (see icon table) are not emoji and are the only inline glyphs allowed.
5. **No icon libraries.** No Lucide, Heroicons, Material Icons, FontAwesome. If a new glyph is needed, extend the Unicode table in this file — don't import a sheet.
6. **The sidebar is always cosmic ink.** It does not flip with theme. Logo and active-row stay gold against night.
7. **No rounded corners on data surfaces.** Tables, modals, panels, inputs, buttons all use `border-radius: 0` in v2. Radii (`--r-xs … --r-pill`) exist for badges, avatars, and the rare pill — that's it.
8. **No `transform: scale()` on press.** Buttons darken on `:active`, full stop. No bounces, no springs.
9. **Animation is slow.** Hover transitions 150ms, theme swap 200ms, ambient (sky drift, ring rotation) measured in tens of seconds. Nothing else.
10. **Never invent new section/page titles in TitleCase.** Page titles are always ALL CAPS Cinzel; section labels inside cards/modals are `— LIKE THIS —` between em-dashes, uppercase, 10px, gold.

---

## Token reference (semantic, theme-aware)

Pulled from `tokens.css`. Use these names directly — never the underlying palette.

| Token | Role |
|---|---|
| `--fg-1` / `--fg-2` / `--fg-3` | Body text (primary / secondary / tertiary) |
| `--fg-on-accent` | Text on gold/violet fills |
| `--accent` | Gold — headings, focus, primary CTA |
| `--accent-soft` | Tinted background under hovered/active accent surfaces |
| `--accent-hover` | Brighter gold for `:hover` |
| `--bg-page` | App background (under sky) |
| `--bg-card` | Translucent panel fill (cards, modals, sidebars) |
| `--bg-input` | Form control fill |
| `--border` / `--border-strong` / `--rule` | Brass-tinted alpha borders / dividers |
| `--signal-ok-bg` / `--signal-ok-fg` | Green badge pair (ACTIVE, RESOLVED) |
| `--signal-info-bg` / `--signal-info-fg` | Blue badge pair (CLAIMED, SUMMARIZE) |
| `--signal-warn-bg` / `--signal-warn-fg` | Yellow badge pair (OPEN, MODERATE) |
| `--signal-stop-bg` / `--signal-stop-fg` | Red badge pair (BLOCK, DESTRUCTIVE) |
| `--signal-mute-bg` / `--signal-mute-fg` | Gray badge pair (DISMISSED, OBSERVE) |
| `--font-display` | Cinzel — all headings + section labels |
| `--font-body` | Inter / system-ui — all body copy and form controls |
| `--font-mono` | Source Code Pro — IDs, codes, table cell IDs, JSON dumps, character counters |
| `--sp-1 … --sp-7` | Spacing scale (4 / 8 / 12 / 16 / 24 / 32 / 48 px) |
| `--orb-glow` | Gold halo used in glows (focus rings, hover shadows) |

Themes flip via `<html data-theme="dark|light">`. Default is dark.

---

## Component recipes

These describe the **finished React component**, not the prototype's literal JSX. Recreate using the codebase's idioms (CSS Modules, styled-components, Tailwind, whatever is already in use).

### `<Button variant>` — primary / secondary / danger
- **Padding** 9px 16px, **font** display Cinzel uppercase 11px tracked 0.14em.
- **Primary:** gradient `linear-gradient(180deg, var(--orb-core) 0%, var(--accent) 100%)`, text `var(--fg-on-accent)`, border `var(--border-strong)`.
- **Secondary:** transparent fill, `var(--fg-1)` text, `var(--border)`; hover → `background: var(--accent-soft)`, `border-color: var(--accent)`.
- **Danger:** transparent fill, `var(--signal-stop-fg)` text and border; hover → `background: rgba(248,113,113,0.08)`.
- **No radius**, **no scale on press**. Disabled = `opacity: 0.4`, cursor not-allowed.

### `<Badge variant>` — gray / green / blue / yellow / red / violet
- Uppercase mono 10px, tracked 0.08em, 2px 8px padding, `border-radius: var(--r-pill)`.
- Background + foreground come from the matching `--signal-*-bg` / `--signal-*-fg` pair.

### `<Modal title onClose onSubmit?>`
- Fixed overlay `rgba(0,0,0,0.55)` with `backdrop-filter: blur(2px)`.
- Modal card 520px (max 95vw, 90vh), `var(--bg-card)`, 1px brass border, **no radius**, `backdrop-filter: blur(16px)`.
- Head: title `font-display` 12px tracked 0.18em uppercase gold + `✕` close button.
- Body: scrollable, 18px padding.
- Foot (only with `onSubmit`): right-aligned Secondary Cancel + Primary Submit.
- Esc closes. Click-on-overlay closes.

### `<DataTable>` — the workhorse
Pattern: page header → optional filter row → `<table class="tbl">`. See `src/main/frontend/src/components/DataTable/DataTable.jsx` for the exact prop surface (`rows`, `columns: [{ key, label, mono?, width?, render? }]`, `filters`, `onEdit`, `onDelete`, `emptyText`).
- Table is full-width, `border-collapse: collapse`, 1px brass border, no radius.
- Header row: display-font 10px tracked 0.18em uppercase gold.
- Row hover tints the row `rgba(212, 168, 73, 0.04)`.
- Mono cells use `var(--font-mono)` 12px `var(--fg-2)`.
- Empty state: centered, italic, `var(--fg-3)`.

### `<Sidebar>` — fixed 220px rail
- Cosmic ink fill, never theme-flips.
- Tenant selector at top, nav list, theme toggle + logout at bottom.
- Active row: gold left-border `border-left-color: var(--accent)`, active background tint, icon glyph gold.
- Hover: `rgba(123, 108, 246, 0.10)` — the violet whisper.

### Section labels inside cards/modals
The signature pattern: a row of mono em-dash-wrapped uppercase gold, 10px tracked 0.18em.
```jsx
<div className="section-label"><span>— Actions —</span></div>
```
Optional right-aligned mono sidekick (e.g. status, counter) — see the Reply composer in `FlagsPage.jsx` for the pattern.

### Segmented control (`.seg`)
Use for mode-style pickers (3–5 short options). One bordered strip of display-font buttons; active button gets `background: var(--accent-soft)`, gold text, and `inset 0 -2px 0 var(--accent)` underline. See the Reply composer for the canonical implementation.

### Chip row (`.chip`)
Templated quick-fill buttons. Mono 11px, transparent fill, brass border. Hover → gold border + gold text. Active → `var(--accent-soft)` fill. Ghost variant uses a dashed border.

### Reply composer (Flag detail) — **new in this handoff**
Lives in `FlagsPage.jsx` under `— Reply —`. Composition:
1. `.seg` mode picker: Public reply / Quote-reply / Private DM / Silent note.
2. `.seg-hint` mono line describing the selected mode.
3. `.chip-row` of templates that prefill the textarea + a dashed `Clear` ghost chip.
4. `<textarea class="input reply-textarea">`.
5. `.reply-foot`: left = mono `{n} chars · {MODE}` counter, right = Secondary `Discard` + Primary `Send reply` (or `Save note` in silent mode). Disabled until text present; flips to `Sent ✓` after dispatch.

When wiring this into production, the **Send reply** action POSTs to `POST /api/flags/{id}/reply` — see the admin-api FlagController.

---

## Pages (route map)

| Route hash | Component | Status in prototype |
|---|---|---|
| `#tenants` | `TenantsPage` | v2 redesign complete |
| `#telegram` | `TelegramPage` | v2 redesign complete |
| `#audit-log` | `AuditLogPage` | v2 redesign complete |
| `#ai-config` | `AIConfigPage` | v2 redesign complete |
| `#policy-rules` | `PolicyRulesPage` | v2 redesign complete |
| `#moderation-rules` | `ModerationRulesPage` | v2 redesign complete |
| `#flags` | `FlagsPage` | v2 redesign complete (incl. Reply composer) |
| `#groups` | `GroupsPage` | v2 redesign complete |
| `#simulate` | `SimulatePage` | v2 redesign complete |
| `#users` | `UsersPage` | v2 redesign complete |

The prototype uses `hashchange` routing because it's a single static HTML file. **In the production app, use the existing router** (probably React Router) — preserve route names so deep links stay stable.

---

## Iconography table (extend, don't replace)

| Glyph | Code | Used for |
|---|---|---|
| `⬡` | U+2B21 | Tenants |
| `⚖` | U+2696 | Policy Rules |
| `⊘` | U+2298 | Moderation Rules |
| `⚑` | U+2691 | Flags |
| `◈` | U+25C8 | Groups |
| `◎` | U+25CE | Audit Log |
| `▶` | U+25B6 | Simulate Event |
| `⌘` | U+2318 | Telegram |
| `✦` | U+2726 | AI Config |
| `◉` | U+25C9 | Users |
| `☽` | U+263D | Dark mode |
| `☀` | U+2600 | Light mode |
| `⏻` | U+23FB | Logout |
| `▾ ▸` | U+25BE / U+25B8 | Expand / collapse |
| `✕` | U+2715 | Close |

If a new surface needs a glyph not above, pick one from Unicode geometric/miscellaneous blocks and add it to this table in the same PR.

---

## Content rules

- **Voice:** you/we, never I. Imperative for commands (`Watch Group`, `Submit Code`), indicative for state.
- **Case:**
  - **Page titles & section labels:** ALL CAPS Cinzel tracked.
  - **Buttons:** Title Case verb + noun (`Add Account`, `Send Reply`).
  - **Form labels:** Title Case (`Phone Number`, `LLM Model Override`).
  - **Status badges:** ALL CAPS matching the enum exactly (`ACTIVE`, `AWAITING_CODE`).
  - **Body copy:** sentence case.
- **No exclamation marks. No "Oops". No "Awesome". No "Let's".**
- **One sentence usually does it.** If a second is needed, it tells the user the next move (`No groups watched. Use Discover to add groups.`).
- **Enums stay raw.** Don't friendly-remap `AWAITING_CODE` to `Waiting for code` — engineers read this.

---

## What to do when adding a new page

1. Look at the closest existing page implementation in `src/main/frontend/src/pages/` and lift its layout shell (page header + filter row + DataTable, or page header + cards grid).
2. Use `<Button>`, `<Badge>`, `<Modal>`, `<DataTable>` from the codebase's component layer — don't reimplement.
3. Pick a Unicode glyph from the icon table (or add one) for the sidebar nav item.
4. Use only semantic tokens for any new CSS.
5. Page header pattern, always:
   ```jsx
   <div className="page-header">
     <div>
       <h2>SECTION NAME</h2>
       <div className="system-id">⌘ service-name · port · short status</div>
     </div>
     {primaryAction && <Button>{primaryAction}</Button>}
   </div>
   ```
6. Empty states: one sentence, italic, `var(--fg-3)`, plus a one-sentence next-move.

---

## What this file does **not** cover

- **API contracts / data shapes.** Backend integration (endpoints, payloads, auth, websockets) lives in the repo's own docs. Wire data-fetching using whatever the rest of the app already uses (React Query, RTK, hand-rolled hooks). The prototypes use in-memory seed arrays — replace with real calls, keep the prop shapes.
- **Routing library specifics.** Hash routing in the prototypes is an artifact of being a single-file demo.
- **Build pipeline.** This file is design + UX rules only.

When unsure about a non-design decision, ask the user before inventing one.
