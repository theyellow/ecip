---
name: emcip-admin-ui
description: >
  React frontend design for the EMCIP admin UI — implementation rules,
  layout gotchas, component APIs, and the design-handoff workflow.
  Tokens, hard brand rules, and the icon table live in
  emcip-admin-ui/CLAUDE.md; this skill defers to it and never restates
  values that could drift. Use whenever working on any page, component,
  or layout in emcip-admin-ui; implementing a handoff; adding a page;
  or debugging a visual/layout bug (overflow, clip, unexpected scroll).
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
  - "permission"
  - "role"
  - "tenant"
  - "watched group"
---

# EMCIP Admin UI — Frontend Design Skill

## Source of truth — read CLAUDE.md first

`emcip-admin-ui/CLAUDE.md` is the **style bible**: design tokens, the two
brand hues, type rules, the full icon table, voice/copy rules, and the
route map. This skill carries only the *implementation* rules and the
non-obvious gotchas that have bitten us — it deliberately does **not**
restate token values or hex codes, because the last copy of this skill
drifted (it still listed "Violet" as a brand hue long after v2 dropped it).
When in doubt about a colour, token, or glyph, open `CLAUDE.md`.

| Concern | Location |
|---------|----------|
| React + Vite source | `emcip-admin-ui/src/main/frontend/src/` |
| Design tokens | `src/main/frontend/src/theme/variables.css` |
| Shared components | `src/main/frontend/src/components/` |
| Pages | `src/main/frontend/src/pages/` |
| Layout shell | `src/main/frontend/src/layout/` |
| Routing | `src/main/frontend/src/App.jsx` (react-router-dom) |
| Permissions | `src/main/frontend/src/auth/permissions.js` |
| Run tests | `cd emcip-admin-ui/src/main/frontend && npm test -- --run` |

---

## Hard rules (the ones most often broken in code)

1. **Semantic tokens only.** Never write hex or raw rgba in component CSS.
   Use `var(--accent)`, `var(--fg-1)`, `var(--bg-card)`, etc. Missing a
   token? Add it to `src/main/frontend/src/theme/variables.css` — don't paper over it.
2. **Two brand hues: Gold (`--accent`) and Teal (`--accent-2` / `--c-teal-500`).**
   Nothing else. **Violet is v1 residue — do not use it.** There is no
   `violet` badge variant.
3. **Display type is Cinzel,** uppercase, `letter-spacing: 0.18em` min.
   Headings, page titles, section labels, button glyphs only — never body.
4. **No emoji. No icon libraries.** Unicode geometric glyphs only — extend
   the table in `CLAUDE.md`, don't import a sheet.
5. **No rounded corners on data surfaces.** `border-radius: 0` on tables,
   modals, panels, inputs, buttons. Radii exist only for badges/avatars.
6. **No `transform: scale()` on press.** Buttons darken on `:active`.
7. **Slow animation.** Hover 150ms, theme swap 200ms. Nothing faster.
8. **Sidebar never theme-flips.** Always cosmic ink.
9. **Never call `window.confirm()`.** Use `ConfirmDialog` for every delete.

---

## Naming — three things the word "group" blurs

Banned: the bare word "group" in nav/titles. These are distinct entities;
mixing them is the single most common domain bug in this app.

| Term | What it is | Lives at |
|---|---|---|
| **Watched Group** (`◈`) | A Telegram chat EMCIP watches — keyed by `telegramChatId`, with `moderationLevel` / `autoRespond` / `welcomeMessage`. | `/groups`, `GROUPS_*` |
| **Watcher** (`⌘`) | A TDLib account (real Telegram login) sitting inside watched groups. Returned by `GET /api/groups/{chatId}/watchers`. | `/telegram`, `TELEGRAM_*` |
| **Role** (`◉` today, `⬠` planned) | An operator's console access level (`ADMIN` / `TENANT_ADMIN`). "Groups a user is in / permissions" is *this*. | `/users` today; `/roles` planned |

- Sidebar row and page `<h2>` read **WATCHED GROUPS**, never "Groups".
- A **Roles** page is planned (`/roles`, `⬠`, `ROLES_*`): manage access
  levels as a first-class list instead of a per-user field. Reuse the
  DataTable + Modal shell. Do **not** fold it into Watched Groups.

---

## Permission-gated nav — every page needs this

The sidebar renders `NAV` filtered by `hasPermission(role, permission)`.
A page that ships without a permission is invisible or unguarded. Adding a
page is therefore **three** edits, not one:

```js
// src/main/frontend/src/auth/permissions.js — add the pair to each role that should see it
ADMIN:        [ /* … */ 'ROLES_READ', 'ROLES_WRITE' ],
TENANT_ADMIN: [ /* … */ ],   // omit if tenant admins shouldn't see it
```

```jsx
// src/main/frontend/src/layout/Sidebar/Sidebar.jsx — NAV entry carries its permission + glyph
{ to: '/roles', label: 'Roles', icon: '⬠', permission: 'ROLES_READ' },
```

```jsx
// src/main/frontend/src/App.jsx — register the route
<Route path="/roles" element={<RolesPage />} />
```

Routing is **react-router-dom** with `/kebab-case` paths and `<NavLink>` —
not hash routing (that's a prototype artifact). Preserve route names so
deep links stay stable.

---

## Multi-tenancy — most pages are tenant-scoped

- `ADMIN` sees a tenant `<select>` ("All Tenants" + each tenant) in the
  sidebar; `TENANT_ADMIN` sees a static single tenant.
- The active tenant lives in `useAuth().currentTenant`; `useAuthRequest()`
  carries it to the API. Pages generally don't pass `tenantId` by hand —
  but list/create payloads may include it (see `Groups.jsx`'s tenant
  `<select>`).
- Tests cover this (`Groups.tenant.test.jsx`). When you add a tenant-aware
  page, add a tenant-scope test alongside it.

---

## Layout — the critical patterns

### AppShell: pin to viewport, never grow with content

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

`overflow-x: hidden` on the nav is deliberate — the spec promotes
`overflow-x` to `auto` when `overflow-y` is non-`visible`, so omitting it
creates a phantom horizontal scrollbar from a 1px overflow.

### Flex children that need `overflow-x: auto` (table wrappers)

A flex item won't shrink below its content width until you add `min-width: 0`:

```css
.tableWrapper { min-width: 0; overflow-x: auto; }
```

Wrap every `<table>` in a `<div className={styles.tableWrapper}>`.

### Modal pattern — always `createPortal`

`position: fixed` inside an ancestor with `backdrop-filter` gets trapped in
that containing block. Every overlay portals to `document.body`:

```jsx
import { createPortal } from 'react-dom'
return createPortal(<div className={styles.overlay}>…</div>, document.body)
```

`Modal` and `ConfirmDialog` already do this. Any new overlay must too.

---

## Component usage

### Button
```jsx
<Button>Primary action</Button>
<Button variant="secondary">Cancel</Button>
<Button variant="danger">Delete</Button>
```

### Badge
```jsx
<Badge variant="green">ACTIVE</Badge>
// Variants: green | blue | yellow | red | gray   (no violet)
```

### DataTable — the workhorse

```jsx
const COLUMNS = [
  { key: 'name', label: 'Group' },
  { key: 'telegramChatId', label: 'Chat ID', mono: true, width: 180 },
  { key: 'moderationLevel', label: 'Mod', width: 100,
    render: v => <Badge variant={LEVEL_VARIANT[v] ?? 'gray'}>{v}</Badge> },
]

<DataTable
  title="Watched Groups"
  systemId={`◈ groups · ${rows.length} watched`}   // use the PAGE's own glyph
  addLabel="+ Add Group"
  onAdd={() => setModal('add')}
  columns={COLUMNS}
  rows={rows}
  rowKey={r => r.telegramChatId ?? r.id}            // when the entity isn't keyed by `id`
  filters={[{                                        // optional filter row
    value: levelFilter,
    onChange: e => setLevelFilter(e.target.value),
    options: [{ value: '', label: 'All moderation levels' }, /* … */],
  }]}
  onEdit={setModal}                                  // row click + Edit → modal with the row
  onDelete={remove}                                  // ConfirmDialog handled internally
  deleteMessage={g => `Stop watching "${g.name}"? This cannot be undone.`}
  emptyText="No groups match this filter"
/>
```

- **`rowKey`** is required whenever the entity isn't keyed by `id`
  (Watched Groups key on `telegramChatId`).
- **`filters`** renders the filter dropdown row. Note: adding one shifts
  every `getAllByRole('combobox')` index in that page's tests by one.
- **`deleteMessage`** copy uses the **domain verb**: Watched Groups *"Stop
  watching…"*, owned records *"Delete…"*. Required whenever `onDelete` is set.

### Modal / ConfirmDialog / SectionLabel
Unchanged — `Modal` takes optional `onSubmit` (omit for detail views);
`ConfirmDialog` for non-DataTable deletes (e.g. Telegram accounts).

---

## Form field pattern

`htmlFor` and `id` must match — required for a11y and for Vitest
`getByLabelText` / `getByRole`. **`Users.jsx` follows this; `Groups.jsx`'s
modal still uses bare labels — fix bare labels when you touch a file rather
than copying them.** ("Match the existing file" loses to this rule.)

```jsx
<div className={styles.field}>
  <label htmlFor="field-id">Label Text</label>
  <input id="field-id" type="text" className={styles.input}
    value={form.x} onChange={e => set('x', e.target.value)} />
  <p className={styles.hint}>Optional hint.</p>
</div>
```

Standard CSS for `.field` / `.field label` / `.input` / `.input:focus` /
`.hint`: copy from any existing page module (they're identical) — or lift
the block in `CLAUDE.md`'s form-field section.

---

## Page structure

```jsx
export function MyPage() {
  const api = myApi(useAuthRequest())
  const [items, setItems] = useState([])
  const [modal, setModal] = useState(null)   // null | 'create' | rowObject
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
    try { await api.remove(item.id); load() } catch (e) { setError(e.message) }
  }

  return (
    <>
      {error && <p role="alert" style={ERROR_STYLE}>{error}</p>}
      <DataTable title="My Page" systemId="◉ my-service · 0 items"
        addLabel="+ Create Item" onAdd={() => setModal('create')}
        columns={COLUMNS} rows={items} onEdit={setModal} onDelete={remove}
        deleteMessage={r => `Delete "${r.name}"? This cannot be undone.`}
        emptyText="No items" />
      {modal && (
        <ItemModal item={modal === 'create' ? null : modal}
          onClose={() => setModal(null)} onSave={save} />
      )}
    </>
  )
}
```

```js
const ERROR_STYLE = {
  color: 'var(--signal-stop-fg)',
  background: 'rgba(248,113,113,0.08)',
  border: '1px solid rgba(248,113,113,0.25)',
  padding: '8px 12px',
  fontFamily: 'var(--font-mono)', fontSize: '12px',
  marginBottom: 'var(--sp-3)',
}
```

---

## Design-handoff workflow

1. **Read the whole handoff** before opening files.
2. **Identify affected files** — pages, components, CSS, `index.html`,
   plus `permissions.js` / `Sidebar.jsx` / `App.jsx` if a page is added.
3. **Read every file before editing.** Don't guess existing content.
4. **Implement with project patterns,** not the handoff's literal code —
   CSS Modules, existing component APIs, established idioms.
5. **Run tests after changes:** `npm test -- --run`. Common breakage:
   - `window.confirm` spies → click the `ConfirmDialog` button instead.
   - Asserting on removed/truncated column text → update assertions.
   - `getAllByRole('combobox')` by index → adding a `filters` dropdown
     shifts every later index by one.
   - Tenant-scope tests → keep `currentTenant` wired in new pages.
6. **One PR per handoff batch** unless asked to split.

---

## Copy and content rules

- Page titles & section labels: ALL CAPS Cinzel. Nav reads **WATCHED GROUPS**.
- Buttons: Title Case verb + noun (`Add Group`, `Send Reply`).
- Form labels: Title Case (`LLM Model Override`).
- Status badges: ALL CAPS matching the enum exactly (`ACTIVE`, `AWAITING_CODE`).
- Body copy: sentence case.
- No exclamation marks, no "Oops"/"Awesome"/"Let's". One sentence usually does it.

## Iconography

The full table lives in `CLAUDE.md`. Page glyphs in use:
`⬡` Tenants · `⚖` Policy Rules · `⊘` Moderation Rules · `⚑` Flags ·
`◈` Watched Groups · `◎` Audit Log · `▶` Simulate · `⌘` Telegram ·
`✦` AI Config · `◉` Users · `⬠` Roles (planned) · `☽`/`☀` theme · `⏻` Logout · `✕` Close.
Need a new glyph? Add it to the table in `CLAUDE.md` in the same PR.
