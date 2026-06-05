---
name: emcip-admin-ui
description: >
  React frontend design for the EMCIP admin UI — design tokens, component
  patterns, layout rules, CSS Modules, and design-handoff workflow.
  Use this skill whenever working on any page, component, or layout in
  emcip-admin-ui; when implementing a design handoff; when adding a new UI
  page or component; when debugging a visual/layout bug; or when something
  looks wrong, overflows, clips, or scrolls in unexpected ways.
triggers:
  - "admin ui"
  - "frontend"
  - "handoff"
  - "design system"
  - "emcip-admin-ui"
  - "react page"
  - "new page"
  - "layout"
  - "overflow"
  - "scroll"
  - "modal"
  - "table"
  - "css module"
---

# EMCIP Admin UI — Frontend Design Skill

## Quick orientation

| Concern | Location |
|---------|----------|
| React + Vite source | `emcip-admin-ui/src/main/frontend/src/` |
| Design tokens | `src/theme/variables.css` |
| Shared components | `src/components/` |
| Pages | `src/pages/` |
| Layout shell | `src/layout/` |
| Run tests | `cd emcip-admin-ui/src/main/frontend && npm test -- --run` |

The full design-system contract lives in `emcip-admin-ui/CLAUDE.md`. This skill focuses on the implementation rules that matter most during coding — especially the non-obvious ones that have bitten us before.

---

## Hard rules (never break)

1. **Semantic tokens only.** Never write hex or raw rgba in component CSS. Reach for `var(--accent)`, `var(--fg-1)`, `var(--bg-card)`, etc. If the token you need doesn't exist, add it to `variables.css` — don't paper over with a one-off colour value.
2. **Two brand hues.** Gold (`--accent`) and Violet (`--c-violet-500`). Nothing else.
3. **Display type is Cinzel.** `font-family: var(--font-display)`, `text-transform: uppercase`, `letter-spacing: 0.18em` minimum. Headings, page titles, section labels, button glyphs only — never body copy.
4. **No emoji. No icon libraries.** Unicode geometric/symbol glyphs only (see table at bottom of this file).
5. **No rounded corners on data surfaces.** `border-radius: 0` on tables, modals, panels, inputs, buttons. Radii (`--r-*`) exist only for badges and avatars.
6. **No `transform: scale()` on press.** Buttons darken on `:active`, full stop.
7. **Slow animation.** Hover transitions 150ms, theme swap 200ms. Nothing faster.
8. **Sidebar never theme-flips.** Always cosmic ink regardless of light/dark mode.
9. **Never call `window.confirm()`.** Use `ConfirmDialog` for every destructive action.

---

## Token quick reference

| Token | Role |
|-------|------|
| `--fg-1` / `--fg-2` / `--fg-3` | Body text — primary / secondary / tertiary |
| `--fg-on-accent` | Text on gold fills |
| `--accent` | Gold — headings, focus ring, primary CTA |
| `--accent-hover` | Brighter gold on `:hover` |
| `--accent-soft` | Tinted background for hovered/active surfaces |
| `--bg-card` | Translucent panel fill (cards, modals) |
| `--bg-input` | Form control fill |
| `--border` / `--border-strong` | Brass-tinted alpha borders |
| `--rule` | Divider lines (thinner than border) |
| `--signal-ok-fg` / `--signal-ok-bg` | Green pair |
| `--signal-info-fg` / `--signal-info-bg` | Blue pair |
| `--signal-warn-fg` / `--signal-warn-bg` | Yellow pair |
| `--signal-stop-fg` / `--signal-stop-bg` | Red pair |
| `--signal-mute-fg` / `--signal-mute-bg` | Gray pair |
| `--orb-glow` | Gold halo for focus rings / glows |
| `--font-display` / `--font-body` / `--font-mono` | Cinzel / Inter / Source Code Pro |
| `--sp-1…--sp-7` | 4 / 8 / 12 / 16 / 24 / 32 / 48 px |

---

## Layout — the critical patterns

### AppShell: pin to viewport, never grow with content

The shell must be capped at `100vh` with `overflow: hidden` so the main
content and sidebar each scroll independently instead of the whole window:

```css
/* AppShell.module.css */
.shell { display: flex; height: 100vh; overflow: hidden; }
.main  { flex: 1; min-width: 0; height: 100vh; overflow-y: auto; scrollbar-gutter: stable; }
```

```css
/* Sidebar.module.css */
.sidebar { width: 220px; height: 100vh; overflow: hidden; }
.nav     { flex: 1; min-height: 0; display: flex; flex-direction: column;
           overflow-y: auto; overflow-x: hidden; }
```

`overflow-x: hidden` on the nav is deliberate — the CSS spec promotes
`overflow-x` to `auto` when `overflow-y` is non-`visible`, so omitting it
creates a phantom horizontal scrollbar from a 1px overflow.

### Flex children that need `overflow-x: auto` (table wrappers)

A flex item refuses to shrink below its content width by default, so
`overflow-x: auto` on a table wrapper never engages until you add:

```css
.tableWrapper { min-width: 0; overflow-x: auto; }
```

Wrap every `<table>` in a `<div className={styles.tableWrapper}>`.

---

## Modal pattern — always `createPortal`

`position: fixed` elements inside an ancestor with `backdrop-filter` get
trapped in that containing block. Every overlay must portal to `document.body`:

```jsx
import { createPortal } from 'react-dom'

return createPortal(
  <div className={styles.overlay}>…</div>,
  document.body
)
```

Both `Modal` and `ConfirmDialog` already do this. Any new overlay must too.

---

## Component usage

### Button
```jsx
import { Button } from '../../components/Button/Button'

<Button>Primary action</Button>
<Button variant="secondary">Cancel</Button>
<Button variant="danger">Delete</Button>
```

### Badge
```jsx
import { Badge } from '../../components/Badge/Badge'

<Badge variant="green">ACTIVE</Badge>
// Variants: green | blue | yellow | red | gray | violet
```

### DataTable
```jsx
import { DataTable } from '../../components/DataTable/DataTable'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'id', label: 'ID', mono: true, width: 100, render: v => v.slice(0, 8) + '…' },
  { key: 'status', label: 'Status', render: v => <Badge variant="green">{v}</Badge> },
]

<DataTable
  title="Items"
  systemId="⬡ service-name · 3 items"
  addLabel="+ Create Item"
  onAdd={() => setModal('create')}
  columns={COLUMNS}
  rows={rows}
  onEdit={setModal}            // row click + Edit button → opens modal with the row object
  onDelete={remove}            // DataTable handles ConfirmDialog internally
  deleteMessage={r => `Delete "${r.name}"? This cannot be undone.`}
  emptyText="No items found"
/>
```

`deleteMessage` is required whenever `onDelete` is provided — it populates
the ConfirmDialog. Never also call `window.confirm()`.

### Modal (for create/edit forms)
```jsx
import { Modal } from '../../components/Modal/Modal'

<Modal title="Edit Item" onClose={onClose} onSubmit={() => onSave(form)}>
  {/* form fields */}
</Modal>
```

`onSubmit` is optional — omit it if the modal has no Save button (e.g., detail views).

### ConfirmDialog (for non-DataTable deletes — e.g., Telegram accounts)
```jsx
import { ConfirmDialog } from '../../components/ConfirmDialog/ConfirmDialog'

const [pendingDelete, setPendingDelete] = useState(null)

// Trigger:
<Button variant="danger" onClick={() => setPendingDelete(item)}>Delete</Button>

// In JSX:
{pendingDelete && (
  <ConfirmDialog
    title="Delete record"
    message={`Delete "${pendingDelete.name}"? This cannot be undone.`}
    onConfirm={() => { remove(pendingDelete); setPendingDelete(null) }}
    onClose={() => setPendingDelete(null)}
  />
)}
```

### SectionLabel
```jsx
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'

<SectionLabel aside={<Button onClick={doThing}>+ Add</Button>}>
  Section Title
</SectionLabel>
```

---

## Page structure

Every page follows this shell — replicate it, don't invent a new pattern:

```jsx
export function MyPage() {
  const api = myApi(useAuthRequest())
  const [items, setItems] = useState([])
  const [modal, setModal] = useState(null)  // null | 'create' | rowObject
  const [error, setError] = useState('')

  const load = () => api.list().then(setItems).catch(e => setError(e.message))
  useEffect(() => { load() }, [])

  const save = async form => {
    try {
      if (modal === 'create') await api.create(form)
      else await api.update(modal.id, form)
      setModal(null); load()
    } catch (e) { setError(e.message) }
  }

  const remove = async item => {
    try { await api.remove(item.id); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <>
      {error && <p role="alert" style={ERROR_STYLE}>{error}</p>}

      <DataTable
        title="My Page"
        systemId="⬡ my-service · 0 items"
        addLabel="+ Create Item"
        onAdd={() => setModal('create')}
        columns={COLUMNS}
        rows={items}
        onEdit={setModal}
        onDelete={remove}
        deleteMessage={r => `Delete "${r.name}"? This cannot be undone.`}
        emptyText="No items"
      />

      {modal && (
        <ItemModal
          item={modal === 'create' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
        />
      )}
    </>
  )
}
```

Error style constant (use verbatim for visual consistency):
```js
const ERROR_STYLE = {
  color: 'var(--signal-stop-fg)',
  background: 'rgba(248,113,113,0.08)',
  border: '1px solid rgba(248,113,113,0.25)',
  padding: '8px 12px',
  fontFamily: 'var(--font-mono)',
  fontSize: '12px',
  marginBottom: 'var(--sp-3)',
}
```

---

## Form field pattern

`htmlFor` and `id` must match — required for both accessibility and Vitest
`getByLabelText` / `getByRole` queries:

```jsx
<div className={styles.field}>
  <label htmlFor="field-id">Label Text</label>
  <input
    id="field-id"
    type="text"
    className={styles.input}
    value={form.x}
    onChange={e => set('x', e.target.value)}
  />
  <p className={styles.hint}>Optional hint.</p>
</div>
```

Standard CSS Module rules for these classes (copy to every page/modal module that needs them):

```css
.field  { display: flex; flex-direction: column; gap: 5px; margin-bottom: var(--sp-2); }

.field label {
  font-family: var(--font-body);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fg-2);
}

.input {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  width: 100%;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

.hint { font-family: var(--font-mono); font-size: 11px; color: var(--fg-3); margin: 0; }
```

---

## Design handoff workflow

When the user pastes a design handoff from their AI design system:

1. **Read the whole handoff first** before opening any files.
2. **Identify affected files** — pages, components, CSS, `index.html`, and any backend endpoints needed.
3. **Read every file before editing it.** Don't guess at existing content.
4. **Implement using project patterns**, not the handoff's literal code. The handoff describes visual intent; use CSS Modules, the existing component API, and established idioms.
5. **Run tests after all changes:** `cd emcip-admin-ui/src/main/frontend && npm test -- --run`. Common breakage patterns to fix:
   - Tests that used `window.confirm` spy → update to click the `ConfirmDialog` button instead
   - Tests that assert on column text that was removed or truncated → update assertions to match new render output
   - Tests that index into `getAllByRole('combobox')` by position → if you added a filter dropdown, all subsequent indices shift by one
6. **One PR per handoff batch** unless the user asks to split.

---

## Copy and content rules

- Page titles and section labels: ALL CAPS Cinzel
- Button labels: Title Case (`Create Tenant`, `Send Reply`)
- Form labels: Title Case (`LLM Model Override`)
- Status badges: ALL CAPS matching the enum exactly (`ACTIVE`, `AWAITING_CODE`)
- Body copy: sentence case
- No exclamation marks, no "Oops", no "Awesome", no "Let's"
- One sentence usually does it

---

## Iconography (Unicode glyphs only — extend this table, never import a library)

| Glyph | Code | Page |
|-------|------|------|
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
| `☽` / `☀` | U+263D / U+2600 | Dark / Light mode toggle |
| `⏻` | U+23FB | Logout |
| `✕` | U+2715 | Close (modals, dialogs) |
