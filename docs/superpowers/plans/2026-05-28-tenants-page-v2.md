# Tenants Page v2 Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the Tenants page from a hand-built table to the shared DataTable component with v2 design tokens.

**Architecture:** Replace the hand-built table and header with DataTable (already built in PR #90). Restyle the create modal form fields to match v2. Same conversion pattern as the Groups page redesign.

**Tech Stack:** React 18, CSS Modules, CSS Custom Properties, Vitest

**Spec:** `docs/superpowers/specs/2026-05-28-tenants-page-v2-design.md`

**Codebase base path:** `emcip-admin-ui/src/main/frontend`

---

## File Structure

### Modified files
- `src/pages/Tenants/Tenants.jsx` — rewrite to use DataTable, v2 modal form
- `src/pages/Tenants/Tenants.module.css` — replace with v2 field/input styles for modal
- `src/pages/Tenants/Tenants.test.jsx` — update assertions for new DataTable-based structure

---

### Task 1: Restyle Tenants CSS and rewrite JSX with DataTable

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Tenants/Tenants.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Tenants/Tenants.jsx`

- [ ] **Step 1: Replace Tenants.module.css with v2 styles**

Read the current file first, then replace its entire content with:

```css
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: var(--sp-2);
}

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
```

- [ ] **Step 2: Rewrite Tenants.jsx using DataTable**

Read the current file first, then replace its entire content with:

```jsx
import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { tenantsApi } from '../../api/tenants'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import styles from './Tenants.module.css'

const COLUMNS = [
  { key: 'id', label: 'ID', mono: true, width: 100, render: v => `${v?.slice(0, 8)}\u2026` },
  { key: 'name', label: 'Name' },
  { key: 'description', label: 'Description', render: v => v || '\u2014' },
  { key: 'llmModelOverride', label: 'LLM Override', mono: true, render: v => v || '\u2014' },
  { key: 'createdAt', label: 'Created', mono: true, width: 110, render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
]

function TenantModal({ onClose, onSave }) {
  const [form, setForm] = useState({ name: '', description: '', llmModelOverride: '' })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title="Create Tenant" onClose={onClose} onSubmit={() => onSave(form)}>
      <div className={styles.field}>
        <label>Name *</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required />
      </div>
      <div className={styles.field}>
        <label>Description</label>
        <textarea className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} rows={3} />
      </div>
      <div className={styles.field}>
        <label>LLM Model Override</label>
        <input type="text" className={styles.input} value={form.llmModelOverride}
          onChange={e => set('llmModelOverride', e.target.value)}
          placeholder="e.g. gpt-4o, claude-3-5-sonnet" />
      </div>
    </Modal>
  )
}

export function Tenants() {
  const api = tenantsApi(useAuthRequest())
  const [tenants, setTenants] = useState([])
  const [showModal, setShowModal] = useState(false)
  const [error, setError] = useState('')

  const load = () => api.list().then(setTenants).catch(e => setError(e.message))
  useEffect(() => { load() }, [])

  const save = async form => {
    try { await api.create(form); setShowModal(false); load() }
    catch (e) { setError(e.message) }
  }

  const remove = async tenant => {
    if (!confirm(`Delete tenant "${tenant.name}"?`)) return
    try { await api.remove(tenant.id); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Tenants"
        systemId={`\u2B21 tenants \u00b7 ${tenants.length} registered`}
        addLabel="+ Create Tenant"
        onAdd={() => setShowModal(true)}
        columns={COLUMNS}
        rows={tenants}
        onDelete={remove}
        emptyText="No tenants registered"
      />

      {showModal && <TenantModal onClose={() => setShowModal(false)} onSave={save} />}
    </>
  )
}
```

- [ ] **Step 3: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Tenants/Tenants.test.jsx 2>&1 | tail -20
```

Expected: tests may fail if they query for old DOM structure. Proceed to Task 2 if failures occur.

- [ ] **Step 4: Commit (if tests pass)**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/Tenants/
git commit -m "feat(admin-ui): redesign Tenants page with v2 DataTable

Replace hand-built table with shared DataTable component. Add system-id
line, v2 styled create modal. Second page on v2 design system."
```

---

### Task 2: Update Tenants tests for DataTable structure

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Tenants/Tenants.test.jsx`

This task is only needed if tests fail in Task 1 Step 3. The existing tests query behavior (button clicks, text content, role queries) which should mostly survive. But some may break due to:
- The `h2` heading is now inside DataTable's page header
- The "Create Tenant" button text changed to "+ Create Tenant"
- The table structure is now rendered by DataTable

- [ ] **Step 1: Read the current test file and identify failures**

Read `emcip-admin-ui/src/main/frontend/src/pages/Tenants/Tenants.test.jsx` and compare assertions against the new JSX structure.

The current tests are:
1. `renders empty table when no tenants exist` — queries for `Tenants` text ✅ (still rendered by DataTable h2)
2. `displays tenant row with truncated id, name, description, llm override and date` — queries text content ✅
3. `shows em-dash for missing description and llmModelOverride` — queries `—` text ✅
4. `opens Create Tenant modal when button clicked` — queries `button` with name `/create tenant/i` — need to check if this still matches `+ Create Tenant`
5. `creates a tenant and reloads list` — queries `button` with name `/create tenant/i` then `/save/i` — same concern
6. `shows error message when list fails` — queries `role="alert"` ✅
7. `deletes a tenant after confirmation` — queries `button` with name `/delete/i` ✅
8. `does not delete when confirmation is cancelled` — same ✅

The regex `/create tenant/i` will match `+ Create Tenant` since regex is a substring match. Most tests should pass without changes.

- [ ] **Step 2: Run tests and fix any failures**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Tenants/Tenants.test.jsx 2>&1 | tail -30
```

If all 8 pass, skip to Step 3. If any fail, update the specific failing assertions to match the new DOM structure. Common fixes:
- If button query fails: update regex to match new button text
- If table structure query fails: update to query by text content instead of table structure

- [ ] **Step 3: Run full test suite**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run 2>&1 | tail -10
```

Expected: 87+ tests pass, same 6 pre-existing failures.

- [ ] **Step 4: Commit (if not already committed in Task 1)**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/Tenants/
git commit -m "feat(admin-ui): redesign Tenants page with v2 DataTable

Replace hand-built table with shared DataTable component. Add system-id
line, v2 styled create modal. Second page on v2 design system."
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task(s) |
|---|---|
| 1. DataTable conversion (columns, system-id, add, delete) | Task 1 Step 2 |
| 2. Create Tenant Modal (3 fields, v2 styling) | Task 1 Steps 1-2 |
| 3. CSS (field, input styles) | Task 1 Step 1 |
| 4. Testing | Task 2 |
| 5. Excluded (no edit, no filters) | Verified: no onEdit, no filters props |

**Placeholder scan:** No TBD/TODO found. All steps have concrete code.

**Type consistency:** COLUMNS `key` values (`id`, `name`, `description`, `llmModelOverride`, `createdAt`) match the API response fields used in the current production code and tests. DataTable props match the component defined in PR #90.
