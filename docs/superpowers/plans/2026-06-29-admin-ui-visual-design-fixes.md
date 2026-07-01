# EMCIP Admin UI — Visual & Design System Fixes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all P0/P1/P2 UI defects found in the 2026-06-29 visual + design-system audit, and bring the sidebar nav and `emcip-admin-ui/CLAUDE.md` contract in sync with the 16-page live app.

**Architecture:** Pure frontend changes inside `emcip-admin-ui/src/main/frontend/src/`. No backend, no API changes, no new routes. Every style fix uses semantic tokens from `src/theme/variables.css`; no hex values ever.

**Tech Stack:** React 18, Vite, CSS Modules, React Router v6; Vitest + React Testing Library for tests.

## Global Constraints

- **Never write hex values** in component CSS — use semantic tokens from `src/theme/variables.css` exclusively.
- `border-radius: 0` on all data surfaces (tables, panels, inputs, buttons); `--r-pill` only for badges and the new scrollbar thumbs.
- No icon libraries. No emoji. Unicode geometric glyphs only (see iconography table in `emcip-admin-ui/CLAUDE.md`).
- Display type: Cinzel uppercase tracked — headings only. Body copy: Inter sentence-case.
- Empty-state copy: sentence case, italic `var(--fg-3)`, one sentence + one next-move sentence.
- All paths in this document are relative to `emcip-admin-ui/src/main/frontend/src/` unless stated otherwise.
- Run tests: `cd emcip-admin-ui/src/main/frontend && npx vitest run <path>` (or `npm test -- --run` for all).
- Before any file edit, re-read `src/theme/variables.css` to confirm token names — never rely on memory.

---

## Source documents

- `~/Downloads/handover-design-system.md` — Claude Design audit with token names and fix instructions
- `~/Downloads/handover-after-live-test-with-screenshots-as-mega-gif.md` — live visual walkthrough

---

### Task 1: Naming — "Watched Groups" rename + CLAUDE.md route map

**Decision recorded here:** The live codebase already uses **Decisions** everywhere (`/decisions` route, `Decisions` export, page `<h2>Decisions</h2>`). **"Groups"** in the sidebar must become **"Watched Groups"** per the prior handoff. Both the sidebar label and the `DataTable title` prop need updating. `emcip-admin-ui/CLAUDE.md`'s route map still lists the old 10-page v1 set — update it to the live 16-page reality (adding the 7 developer-built pages, fixing Flags→Decisions naming).

**Files:**
- Modify: `layout/Sidebar/Sidebar.jsx` (line 17)
- Modify: `pages/Groups/Groups.jsx` (DataTable `title` prop — search for the `DataTable` call)
- Modify: `emcip-admin-ui/CLAUDE.md` (route map table, glossary entry for Flags→Decisions)

**Interfaces:**
- Consumes: nothing from other tasks
- Produces: nothing consumed by other tasks

- [ ] **Step 1: Update sidebar nav label**

In `layout/Sidebar/Sidebar.jsx`, find the Groups NAV entry (line 17) and change `label`:

```js
{ to: '/groups', label: 'Watched Groups', icon: '◈', permission: 'GROUPS_READ' },
```

- [ ] **Step 2: Update Groups page DataTable title**

Open `pages/Groups/Groups.jsx`. Search for the `<DataTable` call and find the `title` prop. Change it:

```jsx
title="Watched Groups"
```

If there is a `systemId` prop referencing "groups", change it to match (e.g. `"◈ watched-groups · N groups"`). Do not rename the file, the component export, the route, or any permission constant — only the display strings.

- [ ] **Step 3: Run Groups tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Groups/
```

Expected: all pass. If any test asserts the old title text `"Groups"`, update the assertion to `"Watched Groups"`.

- [ ] **Step 4: Update emcip-admin-ui/CLAUDE.md route map**

In `emcip-admin-ui/CLAUDE.md`, find the `## Pages (route map)` section. Replace the table with:

```markdown
| Route | Component | Notes |
|---|---|---|
| `/tenants` | `Tenants` | v2 complete |
| `/intent-rules` | `IntentRules` | live, no design ref yet |
| `/intent-signal-config` | `IntentSignalConfig` | live, no design ref; reachable via "SIGNAL CONFIG →" from Intent Rules only |
| `/policy-rules` | `PolicyRules` | v2 complete |
| `/moderation-rules` | `ModerationRules` | v2 complete |
| `/decisions` | `Decisions` (exported from `pages/Flags/Flags.jsx`) | v2 complete; file named Flags — do not rename file |
| `/resolution-queue` | `ResolutionQueue` | live, no design ref yet |
| `/groups` | `Groups` (nav label: **Watched Groups**) | v2 complete |
| `/knowledge` | `Knowledge` (in `KnowledgePage.jsx`) | live, no design ref yet; tabbed: Search / Ingestion Jobs |
| `/research` | `ResearchPage` | live, no design ref yet |
| `/research/:id` | `SessionDetailPage` | live, no design ref yet |
| `/audit-log` | `AuditLog` | v2 complete |
| `/simulate` | `Simulate` | v2 complete |
| `/telegram` | `Telegram` | v2 complete |
| `/ai-config` | `AIConfig` | v2 complete |
| `/costs` | `Costs` | live, no design ref yet |
| `/integrations` | `IntegrationsPage` | live, no design ref yet; tabbed: Global Keys / Sources & Schedule / Run History |
| `/users` | `Users` | v2 complete |
```

Also find the glossary entry or any mention of `⚑ /flags` or `FlagsPage` and change to `⚑ /decisions` / `Decisions`.

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx \
        emcip-admin-ui/CLAUDE.md
git commit -m "fix(admin-ui): rename Groups → Watched Groups in nav; sync CLAUDE.md to 16-page reality"
```

---

### Task 2: Sidebar nav scrollbar styling + violet grep

The `.nav` already has `overflow-y: auto; min-height: 0` but renders the browser's default thick scrollbar. This task adds a thin themed scrollbar. It also verifies no violet hex values (`rgba(123, 108, 246, …)` / `#7b6cf6`) survived anywhere in the frontend source.

**Files:**
- Modify: `layout/Sidebar/Sidebar.module.css`

**Interfaces:**
- Consumes: nothing
- Produces: nothing

- [ ] **Step 1: Grep for violet residue**

```bash
grep -rni "123, 108, 246\|7b6cf6" emcip-admin-ui/src/main/frontend/src/
```

Expected: **zero matches**. If any hits appear, replace each with the correct token:
- Sidebar hover background → `var(--sidebar-bg-hover)`
- Sidebar select/input background → `var(--bg-input)`

- [ ] **Step 2: Add thin scrollbar rules to `.nav`**

In `layout/Sidebar/Sidebar.module.css`, find the `.nav` rule. It currently ends with `overflow-x: hidden; }`. Add scrollbar styling (keeping all existing properties):

```css
.nav {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 1rem 0;
  gap: 0.1rem;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
  scrollbar-color: var(--border) transparent;
}
.nav::-webkit-scrollbar       { width: 4px; }
.nav::-webkit-scrollbar-track { background: transparent; }
.nav::-webkit-scrollbar-thumb { background: var(--border); border-radius: var(--r-pill); }
```

- [ ] **Step 3: Manual acceptance check**

Shrink the browser to ~700px tall (or zoom to 150%). Verify:
1. All 16 nav items are reachable by scrolling inside the nav.
2. The theme toggle and Logout button (`.footer`) stay pinned at the very bottom.
3. The scrollbar is 4px wide, brass-tinted, and does not look like the browser's default chrome.

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.module.css
git commit -m "fix(admin-ui): thin themed scrollbar on sidebar nav; confirm zero violet hex residue"
```

---

### Task 3: Locate and remove the orphan `>` glyph

The visual review found a literal `>` floating at the right edge of every page. It is not part of any card. This task locates the source and removes it.

**Files:**
- Modify: whichever file the grep identifies

**Interfaces:**
- Consumes: nothing
- Produces: nothing

- [ ] **Step 1: Search for the glyph in CSS content properties**

```bash
grep -rn "content:.*>" emcip-admin-ui/src/main/frontend/src/
```

- [ ] **Step 2: Search for the glyph as a JSX literal**

```bash
# right-arrow/chevron Unicode glyphs in string literals
grep -rn "'\u203A\|'\u00BB\|'\u25B8\|\">\"\|{.*'>'.*}" emcip-admin-ui/src/main/frontend/src/

# literal > in JSX text nodes (not part of JSX angle brackets)
grep -rn "> <\|>$" emcip-admin-ui/src/main/frontend/src/ --include="*.jsx"
```

- [ ] **Step 3: Search AppShell and SpaceBackground specifically**

These render on every page, making them the most likely host:

```bash
grep -n ">" emcip-admin-ui/src/main/frontend/src/layout/AppShell/AppShell.jsx
grep -n ">" emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.jsx
grep -n "content" emcip-admin-ui/src/main/frontend/src/layout/AppShell/AppShell.module.css
grep -n "content" emcip-admin-ui/src/main/frontend/src/index.css
```

- [ ] **Step 4: Remove or scope the element**

Once located:
- If it's a CSS `::after { content: ">" }` applying to a wide selector: either remove the rule or tighten the selector to its intended element only.
- If it's a JSX text node in a shared wrapper: remove it.
- If its purpose is unclear after reading context, remove it — the visual review shows it's purely an artifact.

- [ ] **Step 5: Verify on three routes**

Navigate to `/tenants`, `/decisions`, and `/groups`. Confirm no `>` appears at the right edge.

- [ ] **Step 6: Commit**

```bash
git add <modified files>
git commit -m "fix(admin-ui): remove orphan > glyph visible on every page"
```

---

### Task 4: DataTable — sticky action column + styled horizontal scrollbar

On Groups and AI Config the Delete button is cut off by horizontal overflow. Fix: make the action column `position: sticky; right: 0` so the primary action is never the thing that scrolls away.

**Files:**
- Modify: `components/DataTable/DataTable.module.css`
- Modify: `components/DataTable/DataTable.jsx`
- Modify: `components/DataTable/DataTable.test.jsx`

**Interfaces:**
- Consumes: nothing from prior tasks
- Produces: `DataTable` always renders the action `<td>` with `.actionsSticky` class — used by Groups, AI Config, Tenants, ModerationRules, Users, PolicyRules, ResolutionQueue

- [ ] **Step 1: Write the failing test**

In `components/DataTable/DataTable.test.jsx`, add:

```jsx
import { render } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { DataTable } from './DataTable'

describe('DataTable — sticky actions', () => {
  it('action cell has actionsSticky class when onDelete is provided', () => {
    const cols = [{ key: 'name', label: 'Name' }]
    const rows = [{ id: '1', name: 'Row 1' }]
    const { container } = render(
      <DataTable
        columns={cols}
        rows={rows}
        onDelete={vi.fn()}
        deleteMessage={() => 'Delete?'}
      />
    )
    const actionTd = container.querySelector('td.actionsSticky')
    expect(actionTd).toBeTruthy()
  })

  it('action column header also has actionsSticky class', () => {
    const cols = [{ key: 'name', label: 'Name' }]
    const rows = [{ id: '1', name: 'Row 1' }]
    const { container } = render(
      <DataTable columns={cols} rows={rows} onDelete={vi.fn()} deleteMessage={() => ''} />
    )
    const actionTh = container.querySelector('th.actionsSticky')
    expect(actionTh).toBeTruthy()
  })
})
```

(Check what import style the existing tests in that file use and match it.)

- [ ] **Step 2: Run to confirm it fails**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/components/DataTable/DataTable.test.jsx
```

Expected: FAIL — `actionsSticky` class not found.

- [ ] **Step 3: Add CSS to DataTable.module.css**

In `components/DataTable/DataTable.module.css`:

```css
/* Sticky rightmost action column — primary action always visible */
.actionsSticky {
  position: sticky;
  right: 0;
  background: var(--bg-card);
  box-shadow: -6px 0 10px -6px rgba(0, 0, 0, 0.45);
  display: flex;
  gap: 6px;
  justify-content: flex-end;
}

/* Match header background when sticky header cell overlaps scrolled rows */
thead .actionsSticky {
  background: rgba(212, 168, 73, 0.04);
}

/* Thin styled horizontal scrollbar on the wrapper */
.wrapper {
  overflow-x: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--border) transparent;
}
.wrapper::-webkit-scrollbar       { height: 4px; }
.wrapper::-webkit-scrollbar-track { background: transparent; }
.wrapper::-webkit-scrollbar-thumb { background: var(--border); border-radius: var(--r-pill); }
```

Note: `thead .actionsSticky` is a descendant selector and does not need CSS Modules treatment because it targets a child — it will work as written inside a `.module.css` file.

- [ ] **Step 4: Apply `.actionsSticky` in DataTable.jsx**

In `components/DataTable/DataTable.jsx`:

1. In the `<thead>` row, change the action `<th>`:
```jsx
// Before:
{onDelete && <th style={{ width: 80 }}></th>}
// After:
{onDelete && <th className={styles.actionsSticky} style={{ width: 80 }}></th>}
```

2. In the `<tbody>` rows, change the action `<td>`:
```jsx
// Before:
<td className={styles.actions} onClick={e => e.stopPropagation()}>
// After:
<td className={styles.actionsSticky} onClick={e => e.stopPropagation()}>
```

(The old `.actions` class can be deleted from the CSS file — it is fully replaced by `.actionsSticky`.)

- [ ] **Step 5: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/components/DataTable/DataTable.test.jsx
```

Expected: PASS.

- [ ] **Step 6: Manual acceptance**

Open `/groups` and `/ai-config` at the default viewport. Scroll the tables horizontally. The Delete button stays pinned to the right edge. The horizontal scrollbar (if present) is 4px tall and brass-tinted.

- [ ] **Step 7: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.jsx \
        emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.module.css \
        emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.test.jsx
git commit -m "fix(admin-ui): sticky action column in DataTable; styled horizontal scrollbar"
```

---

### Task 5: Decisions page — Status column always visible

`pages/Flags/Flags.jsx` renders its own custom `<table>` (not `DataTable`) with 7 columns. The Status column (rightmost) scrolls out of view at narrow widths, hiding the NEW/REVIEWED/ACTIONED control. Fix it with `position: sticky; right: 0`.

**Files:**
- Modify: `pages/Flags/Flags.module.css`
- Modify: `pages/Flags/Flags.jsx`
- Modify: `pages/Flags/Flags.test.jsx`

**Interfaces:**
- Consumes: nothing from prior tasks
- Produces: nothing consumed by other tasks

- [ ] **Step 1: Write the failing test**

Read `pages/Flags/Flags.test.jsx` first to understand the existing mock setup (auth context, API mock patterns). Then add:

```jsx
it('Status column header has stickyCol class', async () => {
  // Use the same vi.mock patterns as the existing tests in this file
  // (mock flagsApi to return { items: [], total: 0 })
  const { container } = render(
    <MemoryRouter>
      {/* wrap with whatever auth provider the existing tests use */}
      <Decisions />
    </MemoryRouter>
  )
  const ths = Array.from(container.querySelectorAll('th'))
  const statusTh = ths.find(th => th.textContent.trim() === 'Status')
  expect(statusTh).toBeTruthy()
  expect(statusTh.className).toContain('stickyCol')
})
```

Match the import and mock style from the existing `Flags.test.jsx`.

- [ ] **Step 2: Run to confirm it fails**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Flags/Flags.test.jsx
```

Expected: FAIL.

- [ ] **Step 3: Add `.stickyCol` CSS**

In `pages/Flags/Flags.module.css`, add:

```css
/* Sticky rightmost data column */
.stickyCol {
  position: sticky;
  right: 0;
  background: var(--bg-card);
  box-shadow: -6px 0 10px -6px rgba(0, 0, 0, 0.45);
}

/* Header variant matches th background */
.table thead .stickyCol {
  background: var(--accent-soft);
}
```

- [ ] **Step 4: Apply `.stickyCol` in Flags.jsx**

In `pages/Flags/Flags.jsx`, find the `<th>Status</th>` in the header row (~line 400) and the corresponding `<td>` in the row render. Add `className`:

```jsx
// header
<th className={styles.stickyCol}>Status</th>

// body td (the one rendering the Status badge)
<td className={styles.stickyCol}>
  <Badge variant={STATUS_VARIANT[f.signalStatus] ?? 'gray'}>{f.signalStatus ?? 'NEW'}</Badge>
</td>
```

- [ ] **Step 5: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Flags/Flags.test.jsx
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.module.css \
        emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.test.jsx
git commit -m "fix(admin-ui): sticky Status column in Decisions table — always visible"
```

---

### Task 6: DataTable empty state — sentence case + body font

The `.empty` CSS class in `DataTable.module.css` applies `text-transform: uppercase; font-family: var(--font-display); letter-spacing: 0.10em;` — this is why `emptyText="No moderation rules defined"` renders as **NO MODERATION RULES DEFINED**. Fix the class, then update the affected `emptyText` props to sentence-case copy with a next-move sentence.

**Files:**
- Modify: `components/DataTable/DataTable.module.css`
- Modify: `pages/ModerationRules/ModerationRules.jsx`
- Modify: `pages/PolicyRules/PolicyRules.jsx`
- Modify: `components/DataTable/DataTable.test.jsx`

**Interfaces:**
- Consumes: nothing from prior tasks (but the test benefits from Task 4's additions being committed first so the test file is current)
- Produces: nothing consumed by other tasks

- [ ] **Step 1: Write the failing test**

In `components/DataTable/DataTable.test.jsx`, add:

```jsx
it('renders emptyText without uppercase transform (body font class)', () => {
  const { container } = render(
    <DataTable
      columns={[{ key: 'name', label: 'Name' }]}
      rows={[]}
      emptyText="No rules defined. Create a rule to get started."
    />
  )
  const emptyTd = container.querySelector('td')
  expect(emptyTd?.textContent).toBe('No rules defined. Create a rule to get started.')
  // The cell must NOT carry the display-font / uppercase class
  // In jsdom computed styles don't resolve, so assert class names instead
  expect(emptyTd?.className).not.toMatch(/display/i)
})
```

- [ ] **Step 2: Run to see the initial state (may already pass — that's fine; proceed with the CSS fix regardless)**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/components/DataTable/DataTable.test.jsx
```

- [ ] **Step 3: Fix `.empty` in DataTable.module.css**

Replace the entire `.empty` rule:

```css
/* Before */
.empty {
  text-align: center;
  color: var(--fg-3);
  padding: var(--sp-7);
  font-style: italic;
  font-family: var(--font-display);
  letter-spacing: 0.10em;
  text-transform: uppercase;
  font-size: 12px;
}

/* After */
.empty {
  text-align: center;
  color: var(--fg-3);
  padding: var(--sp-7);
  font-style: italic;
  font-family: var(--font-body);
  font-size: 13px;
}
```

- [ ] **Step 4: Update emptyText strings**

`pages/ModerationRules/ModerationRules.jsx` — find `emptyText` prop on `DataTable` (currently `"No records"` or `"NO MODERATION RULES DEFINED"`) and set:
```jsx
emptyText="No moderation rules defined. Create a rule to start filtering messages."
```

`pages/PolicyRules/PolicyRules.jsx` — find `emptyText` prop (currently `"No policy rules defined"`) and set:
```jsx
emptyText="No policy rules defined. Create a rule to route intents to actions."
```

Audit the rest of the app:
```bash
grep -rn "emptyText" emcip-admin-ui/src/main/frontend/src/
```
For every result, check if the string is ALL CAPS or missing a next-move sentence. Update any that are. Leave already-correct ones (e.g. `"No research sessions yet. Start one to begin."` from Task 7 — add that one when Task 7 runs).

- [ ] **Step 5: Run all DataTable tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/components/DataTable/
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.module.css \
        emcip-admin-ui/src/main/frontend/src/pages/ModerationRules/ModerationRules.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.jsx \
        emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.test.jsx
git commit -m "fix(admin-ui): empty state — sentence case body font; update emptyText copy"
```

---

### Task 7: Research page — fix contradictory loading/error states

`ResearchPage.jsx` currently passes `emptyText={loading ? 'Loading sessions…' : '...'}` to `DataTable`. When `loadSessions()` rejects, `error` is set but `loading` may still be true for a moment — the error banner and "Loading sessions…" both appear. Fix by separating loading, error, and empty rendering.

**Files:**
- Modify: `pages/Research/ResearchPage.jsx`
- Modify: `pages/Research/ResearchPage.module.css`
- Create: `pages/Research/ResearchPage.test.jsx`

**Interfaces:**
- Consumes: nothing from prior tasks
- Produces: nothing consumed by other tasks

- [ ] **Step 1: Write the failing test**

Create `pages/Research/ResearchPage.test.jsx`:

```jsx
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ currentTenant: null }),
  useAuthRequest: () => vi.fn(),
}))

vi.mock('../../api/research', () => ({
  researchApi: () => ({
    listSessions: () => Promise.reject(new Error('Network error')),
  }),
}))

import { ResearchPage } from './ResearchPage'

describe('ResearchPage loading/error states', () => {
  it('shows error message without simultaneously showing loading text', async () => {
    render(<MemoryRouter><ResearchPage /></MemoryRouter>)

    const errorEl = await screen.findByRole('alert')
    expect(errorEl).toBeInTheDocument()
    expect(errorEl.textContent).toMatch(/couldn't load/i)

    // Loading text must not co-exist with the error
    expect(screen.queryByText(/loading sessions/i)).not.toBeInTheDocument()
  })

  it('shows a Retry button when load fails', async () => {
    render(<MemoryRouter><ResearchPage /></MemoryRouter>)
    const retryBtn = await screen.findByRole('button', { name: /retry/i })
    expect(retryBtn).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run to confirm it fails**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Research/ResearchPage.test.jsx
```

Expected: FAIL — error and loading text coexist; no Retry button.

- [ ] **Step 3: Refactor ResearchPage.jsx loading/error/table rendering**

Find the section where `DataTable` is rendered. Replace the entire block with:

```jsx
{loading && (
  <p className={styles.loadingText}>Loading sessions…</p>
)}

{!loading && error && (
  <p role="alert" className={styles.errorBanner}>
    Couldn't load research sessions.{' '}
    <button className={styles.retryBtn} onClick={loadSessions}>Retry</button>
  </p>
)}

{!loading && (
  <DataTable
    columns={COLUMNS}
    rows={filtered}
    rowKey={(r) => r.id}
    onEdit={(row) => navigate(`/research/${row.id}`)}
    filters={[
      {
        value: statusFilter,
        options: [
          { value: '', label: 'All statuses' },
          { value: 'COMPLETED', label: 'Completed' },
          { value: 'RUNNING', label: 'Running' },
          { value: 'FAILED', label: 'Failed' },
          { value: 'PAUSED', label: 'Paused' },
          { value: 'CREATED', label: 'Created' },
        ],
        onChange: (v) => setStatusFilter(v),
      },
    ]}
    emptyText="No research sessions yet. Start one to begin."
  />
)}
```

Remove the old `{error && <p style=...>}` block that was above the DataTable.

- [ ] **Step 4: Add CSS to ResearchPage.module.css**

```css
.loadingText {
  color: var(--fg-3);
  font-style: italic;
  font-family: var(--font-body);
  font-size: 13px;
  padding: var(--sp-7) 0;
  text-align: center;
}

.errorBanner {
  color: var(--signal-stop-fg);
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.25);
  padding: 10px 14px;
  font-family: var(--font-mono);
  font-size: 12px;
  margin-bottom: var(--sp-3);
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}

.retryBtn {
  background: none;
  border: 1px solid var(--signal-stop-fg);
  color: var(--signal-stop-fg);
  font-family: var(--font-mono);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  padding: 3px 10px;
  cursor: pointer;
}

.retryBtn:hover {
  background: rgba(248, 113, 113, 0.08);
}
```

- [ ] **Step 5: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Research/ResearchPage.test.jsx
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/Research/ResearchPage.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/Research/ResearchPage.module.css \
        emcip-admin-ui/src/main/frontend/src/pages/Research/ResearchPage.test.jsx
git commit -m "fix(admin-ui): research page — separate loading/error/empty states; add Retry button"
```

---

### Task 8: PolicyRules — "Global" badge for tenant-less rules

When a tenant is selected in the sidebar, the three existing global rules (no `tenantId`) still appear in the list with no visual distinction. Add a `tenantId` column that renders a `Global` badge when the value is null.

**Files:**
- Modify: `pages/PolicyRules/PolicyRules.jsx`
- Modify: `pages/PolicyRules/PolicyRules.tenant.test.jsx`

**Interfaces:**
- Consumes: nothing from prior tasks
- Produces: nothing consumed by other tasks

- [ ] **Step 1: Read the existing test to understand the mock pattern**

```bash
cat emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.tenant.test.jsx
```

Note: the file uses `vi.mock('../../auth/AuthContext', ...)` and `vi.mock('../../api/policyRules', ...)` patterns. Match these exactly in the new test.

- [ ] **Step 2: Write the failing test**

In `PolicyRules.tenant.test.jsx`, add inside the existing `describe` block (or as a new one):

```jsx
describe('PolicyRules — global rule badge', () => {
  it('shows a Global badge for rules with no tenantId', async () => {
    // Override the policyRulesApi mock for this test only
    vi.mocked(policyRulesApi('').list).mockResolvedValue([
      { id: 'aaa', name: 'global-rule', targetIntent: 'SPAM', action: 'FLAG',
        priority: 0, tenantId: null, effectiveFrom: null, effectiveTo: null },
      { id: 'bbb', name: 'tenant-rule', targetIntent: 'SPAM', action: 'FLAG',
        priority: 1, tenantId: 'abc-123', effectiveFrom: null, effectiveTo: null },
    ])

    render(<PolicyRules />)

    // Wait for table to populate
    await screen.findByText('global-rule')

    // Global badge present for tenant-less row
    const badges = screen.getAllByText('Global')
    expect(badges.length).toBe(1)

    // No Global badge on the tenanted row
    const rows = screen.getAllByRole('row')
    const tenantRow = rows.find(r => r.textContent.includes('tenant-rule'))
    expect(tenantRow?.textContent).not.toContain('Global')
  })
})
```

Adjust mock setup as needed to match the file's import style. Run first to confirm it fails.

- [ ] **Step 3: Run to confirm it fails**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/PolicyRules/PolicyRules.tenant.test.jsx
```

Expected: FAIL — no "Global" badge rendered.

- [ ] **Step 4: Add tenantId column to PolicyRules.jsx**

In `pages/PolicyRules/PolicyRules.jsx`, find the `columns` array (around line 226). Add a `tenantId` column between "Action" and "Priority":

```jsx
const columns = [
  { key: 'name', label: 'Rule Name' },
  { key: 'targetIntent', label: 'Intent', render: v => <Badge variant="gray">{v}</Badge> },
  { key: 'action', label: 'Action', width: 110,
    render: v => <Badge variant={ACTION_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  {
    key: 'tenantId',
    label: 'Tenant',
    width: 110,
    render: v => v
      ? <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--fg-2)' }}>
          {v.slice(0, 8) + '\u2026'}
        </span>
      : <Badge variant="gray">Global</Badge>,
  },
  { key: 'priority', label: 'Priority', mono: true, width: 80 },
  { key: 'effectiveFrom', label: 'From', mono: true, width: 110,
    render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
  { key: 'effectiveTo', label: 'To', mono: true, width: 110,
    render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
]
```

- [ ] **Step 5: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/PolicyRules/PolicyRules.tenant.test.jsx
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.tenant.test.jsx
git commit -m "feat(admin-ui): Global badge on tenant-less policy rules"
```

---

### Task 9: Pipeline Trace — add dot legend

`PipelineTrace.jsx` already computes per-stage dot colors via `dotColor()`. But the colors carry no visible legend — the visual review noted four identical grey dots with no indication of what grey vs lit means. Add a compact legend.

**Files:**
- Modify: `pages/Simulate/PipelineTrace.jsx`
- Modify: `pages/Simulate/PipelineTrace.module.css`
- Modify: `pages/Simulate/Simulate.test.jsx`

**Interfaces:**
- Consumes: nothing from prior tasks
- Produces: nothing consumed by other tasks

- [ ] **Step 1: Read PipelineTrace.module.css**

```bash
cat emcip-admin-ui/src/main/frontend/src/pages/Simulate/PipelineTrace.module.css
```

Note the `.panel`, `.stages`, `.dot`, `.stageName` classes and the overall flex layout. The legend will sit between the `<SectionLabel>` and the stage list.

- [ ] **Step 2: Write a failing test**

In `pages/Simulate/Simulate.test.jsx` (read the file first to match mock patterns), add:

```jsx
it('PipelineTrace panel contains a legend', () => {
  const { container } = render(<PipelineTrace result={null} loading={false} />)
  const legend = container.querySelector('.legend')
  // Or: assert that the word "waiting" appears in the legend text
  expect(container.textContent).toMatch(/waiting/i)
})
```

Import `PipelineTrace` at the top of the test file. Adjust the assertion to whatever is practical given the existing mock setup.

- [ ] **Step 3: Run to confirm it fails**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Simulate/Simulate.test.jsx
```

Expected: FAIL.

- [ ] **Step 4: Add the legend JSX**

In `PipelineTrace.jsx`, find the line with `<SectionLabel>Pipeline Trace</SectionLabel>`. Add the legend immediately after it (before `{loading && ...}`):

```jsx
<SectionLabel>Pipeline Trace</SectionLabel>

<div className={styles.legend}>
  <span className={styles.legendItem}>
    <span className={styles.legendDot} style={{ background: 'var(--border-strong)' }} />
    waiting
  </span>
  <span className={styles.legendItem}>
    <span className={styles.legendDot} style={{ background: 'var(--accent)' }} />
    processing
  </span>
  <span className={styles.legendItem}>
    <span className={styles.legendDot} style={{ background: 'var(--signal-ok-fg)' }} />
    done
  </span>
  <span className={styles.legendItem}>
    <span className={styles.legendDot} style={{ background: 'var(--signal-stop-fg)' }} />
    blocked
  </span>
</div>
```

- [ ] **Step 5: Add legend CSS to PipelineTrace.module.css**

```css
.legend {
  display: flex;
  gap: var(--sp-4);
  flex-wrap: wrap;
  margin: var(--sp-2) 0 var(--sp-3);
}

.legendItem {
  display: flex;
  align-items: center;
  gap: 5px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.legendDot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
```

- [ ] **Step 6: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Simulate/Simulate.test.jsx
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/Simulate/PipelineTrace.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/Simulate/PipelineTrace.module.css \
        emcip-admin-ui/src/main/frontend/src/pages/Simulate/Simulate.test.jsx
git commit -m "feat(admin-ui): dot legend on Pipeline Trace panel"
```

---

## Out of scope for this plan

These items from the design doc are deferred — they each need their own plan or are blocked on Design deliverables:

- **Toasts on mutating actions** (§3) — requires a `ToastContext` provider, toast component, and wiring to every `onSave`/`onDelete` call across the app. Significant cross-cutting change.
- **Modal recipe audit** (§3) — no screenshots of modals in the GIF; needs a separate visual walkthrough.
- **Light mode (Parchment) full pass** (§3) — needs a visual walkthrough of all 16 pages + modals in light theme.
- **Design references for the 8 undocumented pages** (§2.2) — Design will deliver HTML/JSX references; plan to execute when those arrive.
- **`admin@ecip.io` seed data typo** — data/seed cleanup, not UI code; fix in admin-api seed data separately.
