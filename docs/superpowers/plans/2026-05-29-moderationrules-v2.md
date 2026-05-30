# ModerationRules Page v2 Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the ModerationRules page from a hand-built table to the shared DataTable component with v2 design tokens.

**Architecture:** Replace the hand-built table and header with DataTable (already built in PR #90). Restyle the create/edit modal form fields to match v2. Same conversion pattern as Groups, Tenants, AuditLog, and PolicyRules.

**Tech Stack:** React 18, CSS Modules, CSS Custom Properties, Vitest

**Spec:** `docs/superpowers/specs/2026-05-29-moderationrules-v2-design.md`

**Codebase base path:** `emcip-admin-ui/src/main/frontend`

---

## File Structure

### Modified files
- `src/pages/ModerationRules/ModerationRules.jsx` — rewrite to use DataTable, v2 modal form
- `src/pages/ModerationRules/ModerationRules.module.css` — replace with v2 field/input/hint styles
- `src/pages/ModerationRules/ModerationRules.test.jsx` — update assertions for DataTable structure

---

### Task 1: Restyle CSS and rewrite JSX with DataTable

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/ModerationRules/ModerationRules.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/ModerationRules/ModerationRules.jsx`

- [ ] **Step 1: Replace ModerationRules.module.css with v2 styles**

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

.hint {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  margin-top: 2px;
}

.pattern {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}
```

- [ ] **Step 2: Rewrite ModerationRules.jsx using DataTable**

Read the current file first, then replace its entire content with:

```jsx
import { useEffect, useState } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { moderationRulesApi } from '../../api/moderationRules'
import { Badge } from '../../components/Badge/Badge'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import styles from './ModerationRules.module.css'

const RULE_TYPES = ['KEYWORD', 'REGEX', 'LENGTH']
const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH']
const ACTIONS = ['FLAG', 'WARN', 'MUTE', 'BAN', 'DELETE', 'ESCALATE']

const PATTERN_HINT = {
  KEYWORD: 'Case-insensitive substring match — e.g. spam',
  REGEX:   'Case-insensitive regex — e.g. buy\\s+now',
  LENGTH:  'Maximum message length in characters — e.g. 1000',
}

const SEVERITY_VARIANT = { LOW: 'gray', MEDIUM: 'yellow', HIGH: 'red' }
const ACTION_VARIANT   = { FLAG: 'blue', WARN: 'yellow', MUTE: 'yellow', BAN: 'red', DELETE: 'red', ESCALATE: 'gray' }

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'tenantId', label: 'Tenant', mono: true, width: 100, render: v => v ? v.slice(0, 8) + '\u2026' : '\u2014' },
  { key: 'ruleType', label: 'Type', render: v => <Badge variant="gray">{v}</Badge> },
  { key: 'pattern', label: 'Pattern', mono: true, render: (v, row) => <span className={styles.pattern} title={v}>{v}</span> },
  { key: 'severity', label: 'Severity', render: v => <Badge variant={SEVERITY_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'action', label: 'Action', width: 110, render: v => <Badge variant={ACTION_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'enabled', label: 'Enabled', width: 80, render: v => v ? <Badge variant="green">ON</Badge> : <Badge variant="gray">OFF</Badge> },
]

function RuleModal({ rule, onClose, onSave, currentTenant }) {
  const [form, setForm] = useState({
    name:     rule?.name     ?? '',
    ruleType: rule?.ruleType ?? 'KEYWORD',
    pattern:  rule?.pattern  ?? '',
    severity: rule?.severity ?? 'MEDIUM',
    action:   rule?.action   ?? 'FLAG',
    enabled:  rule?.enabled  ?? true,
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={rule ? 'Edit Rule' : 'Create Rule'} onClose={onClose} onSubmit={() => onSave(form)}>
      <div className={styles.field}>
        <label>Rule Name *</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required disabled={!!rule} />
      </div>
      <div className={styles.field}>
        <label>Rule Type</label>
        <select className={styles.input} value={form.ruleType}
          onChange={e => set('ruleType', e.target.value)}>
          {RULE_TYPES.map(t => <option key={t}>{t}</option>)}
        </select>
      </div>
      <div className={styles.field}>
        <label>Pattern *</label>
        <input type={form.ruleType === 'LENGTH' ? 'number' : 'text'}
          className={styles.input} value={form.pattern}
          onChange={e => set('pattern', e.target.value)} required
          placeholder={PATTERN_HINT[form.ruleType]} />
        <p className={styles.hint}>{PATTERN_HINT[form.ruleType]}</p>
      </div>
      <div className={styles.field}>
        <label>Severity</label>
        <select className={styles.input} value={form.severity}
          onChange={e => set('severity', e.target.value)}>
          {SEVERITIES.map(s => <option key={s}>{s}</option>)}
        </select>
      </div>
      <div className={styles.field}>
        <label>Action</label>
        <select className={styles.input} value={form.action}
          onChange={e => set('action', e.target.value)}>
          {ACTIONS.map(a => <option key={a}>{a}</option>)}
        </select>
      </div>
      {rule && (
        <div className={styles.field}>
          <label>Enabled</label>
          <select className={styles.input} value={form.enabled ? 'true' : 'false'}
            onChange={e => set('enabled', e.target.value === 'true')}>
            <option value="true">Yes</option>
            <option value="false">No</option>
          </select>
        </div>
      )}
      <div className={styles.field}>
        <label>Tenant</label>
        <p className={styles.hint}>
          {rule?.tenantId
            ? rule.tenantId.slice(0, 8) + '\u2026'
            : currentTenant
              ? currentTenant.name
              : 'All tenants \u2014 select a tenant in the sidebar to scope this rule'}
        </p>
      </div>
    </Modal>
  )
}

export function ModerationRules() {
  const { currentTenant } = useAuth()
  const api = moderationRulesApi(useAuthRequest())
  const [rules, setRules] = useState([])
  const [modal, setModal] = useState(null)
  const [error, setError] = useState('')

  const load = () => api.list().then(setRules).catch(e => setError(e.message))
  useEffect(() => { load() }, [])

  const save = async form => {
    try {
      if (modal === 'add') await api.create(form)
      else await api.update(modal.id, form)
      setModal(null)
      load()
    } catch (e) { setError(e.message) }
  }

  const remove = async rule => {
    if (!confirm(`Delete rule "${rule.name}"?`)) return
    try { await api.remove(rule.id); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Moderation Rules"
        systemId={`\u2298 moderation-service \u00b7 ${rules.length} rules`}
        addLabel="+ Create Rule"
        onAdd={() => setModal('add')}
        columns={COLUMNS}
        rows={rules}
        onEdit={setModal}
        onDelete={remove}
        emptyText="No moderation rules defined"
      />

      {modal && (
        <RuleModal
          rule={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
          currentTenant={currentTenant}
        />
      )}
    </>
  )
}
```

- [ ] **Step 3: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/ModerationRules/ModerationRules.test.jsx 2>&1 | tail -30
```

Expected: several tests will fail due to:
- Enabled column now renders Badge `ON`/`OFF` instead of `✓`/`—`
- Edit button replaced by row click
- Possibly other DataTable structural changes

Note which tests pass and which fail. Proceed to Task 2.

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/ModerationRules/ModerationRules.jsx emcip-admin-ui/src/main/frontend/src/pages/ModerationRules/ModerationRules.module.css
git commit -m "feat(admin-ui): redesign ModerationRules page with v2 DataTable

Replace hand-built table with DataTable. Add severity/action/enabled
badges, system-id line, dynamic pattern hint. Restyle edit/create
modal with v2 tokens. Fifth page on v2 design system.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 2: Update ModerationRules tests for DataTable structure

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/ModerationRules/ModerationRules.test.jsx`

- [ ] **Step 1: Read current test file and run tests to identify failures**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/ModerationRules/ModerationRules.test.jsx 2>&1 | tail -40
```

- [ ] **Step 2: Update mock and fix failing tests**

Read the test file, then apply these changes:

1. **"displays rule row with badges"** — change `✓` assertion to `ON`:

Old:
```jsx
expect(screen.getByText('✓')).toBeInTheDocument()
```

New:
```jsx
expect(screen.getByText('ON')).toBeInTheDocument()
```

2. **"shows em-dash for disabled rule"** — change `—` assertion to `OFF`:

Old:
```jsx
expect(screen.getByText('—')).toBeInTheDocument()
```

New:
```jsx
expect(screen.getByText('OFF')).toBeInTheDocument()
```

3. **"opens Edit Rule modal with prefilled values"** — the old test clicks an Edit button. With DataTable, editing is via row click (`onEdit`). Update to click a text element in the row:

Old:
```jsx
await userEvent.click(screen.getByRole('button', { name: /edit/i }))
```

New:
```jsx
await userEvent.click(screen.getByText('no-spam'))
```

4. **"updates a rule and reloads list"** — same Edit button issue:

Old:
```jsx
await userEvent.click(screen.getByRole('button', { name: /edit/i }))
```

New:
```jsx
await userEvent.click(screen.getByText('no-spam'))
```

5. **"deletes a rule after confirmation"** and **"does not delete when confirmation is cancelled"** — the Delete button is now rendered by DataTable. It should still be queryable by `getByRole('button', { name: /delete/i })`. If multiple Delete buttons exist (DataTable renders one per row), use `getAllByRole` and take the first:

If the test fails because of multiple matches, change:
```jsx
await userEvent.click(screen.getByRole('button', { name: /delete/i }))
```
to:
```jsx
await userEvent.click(screen.getAllByRole('button', { name: /delete/i })[0])
```

- [ ] **Step 3: Run ModerationRules tests to verify all pass**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/ModerationRules/ModerationRules.test.jsx 2>&1 | tail -30
```

Expected: all 10 tests pass.

- [ ] **Step 4: Run full test suite**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run 2>&1 | tail -10
```

Expected: 88+ tests pass, pre-existing failures only (AIConfig).

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/ModerationRules/ModerationRules.test.jsx
git commit -m "test(admin-ui): update ModerationRules tests for v2 DataTable structure

Update enabled assertions (✓/— → ON/OFF badges). Replace Edit button
clicks with row clicks. Verify Delete button via DataTable.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task(s) |
|---|---|
| 1. DataTable conversion (columns, system-id, add, edit, delete) | Task 1 Step 2 |
| 2. Edit/Create Modal (6 fields + tenant display, pattern hint) | Task 1 Step 2 |
| 3. CSS (field, input, hint, pattern styles) | Task 1 Step 1 |
| 4. Testing | Task 2 |
| 5. Excluded (no filters, no ML categories) | Verified: no filters prop, no new data model |

**Placeholder scan:** No TBD/TODO found. All steps have concrete code.

**Type consistency:** COLUMNS `key` values (`name`, `tenantId`, `ruleType`, `pattern`, `severity`, `action`, `enabled`) match the API response fields used in the current production code and tests. DataTable props match the component defined in PR #90. `PATTERN_HINT`, `SEVERITY_VARIANT`, `ACTION_VARIANT` maps match production values. `styles.pattern` class referenced in both CSS and JSX.
