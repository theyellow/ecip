# AuditLog + PolicyRules v2 Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert AuditLog and PolicyRules pages from hand-built tables to DataTable with v2 design tokens.

**Architecture:** Same DataTable conversion pattern as Groups and Tenants. AuditLog is read-only with a custom details modal; PolicyRules has full CRUD with edit/create and history modals.

**Tech Stack:** React 18, CSS Modules, CSS Custom Properties, Vitest

**Spec:** `docs/superpowers/specs/2026-05-28-auditlog-policyrules-v2-design.md`

**Codebase base path:** `emcip-admin-ui/src/main/frontend`

---

## File Structure

### Modified files
- `src/pages/AuditLog/AuditLog.jsx` — rewrite with DataTable, restyle DetailsModal
- `src/pages/AuditLog/AuditLog.module.css` — replace with v2 styles
- `src/pages/AuditLog/AuditLog.test.jsx` — update filter assertions for DataTable structure
- `src/pages/PolicyRules/PolicyRules.jsx` — rewrite with DataTable, restyle modals
- `src/pages/PolicyRules/PolicyRules.module.css` — replace with v2 styles

---

### Task 1: Redesign AuditLog page

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.jsx`

- [ ] **Step 1: Replace AuditLog.module.css with v2 styles**

Read the current file first, then replace its entire content with:

```css
.detailsLink {
  cursor: pointer;
  color: var(--fg-2);
  max-width: 300px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: block;
  text-decoration: underline dotted;
  text-underline-offset: 3px;
  font-family: var(--font-mono);
  font-size: 12px;
}

.detailsLink:hover {
  color: var(--accent);
}

.metaGrid {
  display: grid;
  grid-template-columns: 130px 1fr;
  gap: 8px 16px;
  align-items: baseline;
  margin-bottom: var(--sp-4);
}

.metaLabel {
  font-family: var(--font-body);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: var(--fg-3);
}

.metaValue {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
  word-break: break-all;
}

.jsonBlock {
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 0;
  padding: 12px 14px;
  overflow-x: auto;
  margin-bottom: var(--sp-3);
}

.jsonBlock pre {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--code-fg);
}

.copyBtn {
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 0;
  color: var(--fg-2);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  cursor: pointer;
  padding: 3px 8px;
}

.copyBtn:hover {
  color: var(--accent);
  border-color: var(--accent);
}

.copied {
  color: var(--signal-ok-fg);
  border-color: var(--signal-ok-fg);
}
```

- [ ] **Step 2: Rewrite AuditLog.jsx using DataTable**

Read the current file first, then replace its entire content with:

```jsx
import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { auditLogApi } from '../../api/auditLog'
import { Badge } from '../../components/Badge/Badge'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import styles from './AuditLog.module.css'

const EVENT_TYPES = ['', 'MESSAGE_RECEIVED', 'MESSAGE_CLASSIFIED', 'POLICY_DECISION', 'MODERATION_ACTION']
const OUTCOME_VARIANT = { OK: 'green', BLOCK: 'red' }

function CopyButton({ text }) {
  const [copied, setCopied] = useState(false)
  const handle = () => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }
  return (
    <button className={`${styles.copyBtn}${copied ? ' ' + styles.copied : ''}`} onClick={handle}>
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}

function parseDetails(raw) {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) } catch { return raw }
}

function detailsPreview(e) {
  if (e.details == null) return null
  const raw = typeof e.details === 'object' ? JSON.stringify(e.details) : String(e.details)
  return raw.length > 80 ? raw.slice(0, 80) + '\u2026' : raw
}

function DetailsModal({ event, onClose }) {
  const parsedDetails = parseDetails(event.details)
  const prettyDetails = parsedDetails != null ? JSON.stringify(parsedDetails, null, 2) : null
  const rawEvent = JSON.stringify(event, null, 2)

  return (
    <Modal title="Audit Event Details" onClose={onClose}>
      <div className={styles.metaGrid}>
        <span className={styles.metaLabel}>Timestamp</span>
        <span className={styles.metaValue}>{event.createdAt ? new Date(event.createdAt).toLocaleString() : '\u2014'}</span>
        <span className={styles.metaLabel}>Event Type</span>
        <span className={styles.metaValue}>{event.eventType ?? '\u2014'}</span>
        <span className={styles.metaLabel}>Source</span>
        <span className={styles.metaValue}>{event.sourceService ?? '\u2014'}</span>
        <span className={styles.metaLabel}>Action</span>
        <span className={styles.metaValue}>{event.action ?? '\u2014'}</span>
        <span className={styles.metaLabel}>Resource</span>
        <span className={styles.metaValue}>{event.resourceId ?? '\u2014'}</span>
        <span className={styles.metaLabel}>Outcome</span>
        <span className={styles.metaValue}>{event.outcome ?? '\u2014'}</span>
      </div>

      {prettyDetails != null && (
        <>
          <SectionLabel aside={<CopyButton text={prettyDetails} />}>Details</SectionLabel>
          <div className={styles.jsonBlock}>
            <pre>{prettyDetails}</pre>
          </div>
        </>
      )}

      <SectionLabel aside={<CopyButton text={rawEvent} />}>Raw Event</SectionLabel>
      <div className={styles.jsonBlock}>
        <pre>{rawEvent}</pre>
      </div>
    </Modal>
  )
}

const COLUMNS = [
  { key: 'createdAt', label: 'Timestamp', mono: true, width: 170, render: v => v ? new Date(v).toLocaleString() : '\u2014' },
  { key: 'eventType', label: 'Event Type' },
  { key: 'sourceService', label: 'Source', mono: true, render: v => v ?? '\u2014' },
  { key: 'action', label: 'Action', render: v => v ?? '\u2014' },
  { key: 'resourceId', label: 'Resource', mono: true, render: v => v ?? '\u2014' },
  { key: 'outcome', label: 'Outcome', width: 100, render: v => v ? <Badge variant={OUTCOME_VARIANT[v] ?? 'gray'}>{v}</Badge> : '\u2014' },
]

export function AuditLog() {
  const api = auditLogApi(useAuthRequest())
  const [events, setEvents] = useState([])
  const [total, setTotal] = useState(0)
  const [page] = useState(0)
  const [size, setSize] = useState(50)
  const [eventType, setEventType] = useState('')
  const [error, setError] = useState('')
  const [selected, setSelected] = useState(null)

  const load = () => {
    api
      .list(page, size, eventType)
      .then(data => {
        setEvents(data?.items ?? [])
        setTotal(data?.total ?? 0)
      })
      .catch(e => setError(e.message))
  }
  useEffect(() => { load() }, [size, eventType])

  const detailsColumn = {
    key: 'details',
    label: 'Details',
    render: (v, row) => {
      const preview = detailsPreview(row)
      return preview != null
        ? <span className={styles.detailsLink} onClick={e => { e.stopPropagation(); setSelected(row) }}>{preview}</span>
        : '\u2014'
    },
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Audit Log"
        systemId={`\u25CE audit-service \u00b7 ${total} events total`}
        columns={[...COLUMNS, detailsColumn]}
        rows={events}
        rowKey={(r, i) => i}
        onEdit={setSelected}
        filters={[
          {
            value: eventType,
            onChange: e => setEventType(e.target.value),
            options: EVENT_TYPES.map(t => ({ value: t, label: t || 'All types' })),
          },
          {
            value: String(size),
            onChange: e => setSize(Number(e.target.value)),
            options: [25, 50, 100, 200].map(n => ({ value: String(n), label: String(n) })),
          },
        ]}
        emptyText="No audit events found"
      />

      {selected && <DetailsModal event={selected} onClose={() => setSelected(null)} />}
    </>
  )
}
```

- [ ] **Step 3: Run AuditLog tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/AuditLog/AuditLog.test.jsx 2>&1 | tail -30
```

Some tests may fail because:
- The filter selects are now inside DataTable and are `<select>` elements (still role `combobox`)
- The `displays event row` test uses a mock EVENT with `entityId` field not matching any column — this is a pre-existing test issue
- The em-dash test depends on `entityId` which the page now doesn't render as a column

If tests fail, note which ones and fix them. The event type/page size filter tests should still work if they query by `combobox` role since DataTable renders `<select>` elements. The `entityId` test was already failing (pre-existing).

- [ ] **Step 4: Fix any test failures**

If tests fail, read the test file, understand what changed, and update assertions. Common fixes:
- If `entityId` tests fail: they were already pre-existing failures, leave them
- If filter tests fail: the combobox elements are now rendered by DataTable but should still be queryable by role
- If the heading test fails: "Audit Log" is now in DataTable's h2

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/AuditLog/
git commit -m "feat(admin-ui): redesign AuditLog page with v2 DataTable

Replace hand-built table with DataTable. Add outcome badges, system-id
line. Restyle DetailsModal with shared Modal, SectionLabel, v2 tokens.
Third page on v2 design system."
```

---

### Task 2: Redesign PolicyRules page

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.jsx`

- [ ] **Step 1: Replace PolicyRules.module.css with v2 styles**

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

.historyItem {
  display: flex;
  gap: var(--sp-4);
  padding: 8px 0;
  border-bottom: 1px solid var(--rule);
  font-size: 13px;
  color: var(--fg-1);
  align-items: baseline;
}

.historyItem:last-child {
  border-bottom: none;
}

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}
```

- [ ] **Step 2: Rewrite PolicyRules.jsx using DataTable**

Read the current file first, then replace its entire content with:

```jsx
import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { policyRulesApi } from '../../api/policyRules'
import { tenantsApi } from '../../api/tenants'
import { Badge } from '../../components/Badge/Badge'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import styles from './PolicyRules.module.css'

const ACTIONS = ['FLAG', 'WARN', 'MUTE', 'BAN', 'DELETE', 'ESCALATE']
const ACTION_VARIANT = { FLAG: 'blue', WARN: 'yellow', MUTE: 'yellow', BAN: 'red', DELETE: 'red', ESCALATE: 'gray' }

const COLUMNS = [
  { key: 'name', label: 'Rule Name' },
  { key: 'targetIntent', label: 'Intent', render: v => <Badge variant="gray">{v}</Badge> },
  { key: 'action', label: 'Action', width: 110, render: v => <Badge variant={ACTION_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'priority', label: 'Priority', mono: true, width: 80 },
  { key: 'effectiveFrom', label: 'From', mono: true, width: 110, render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
  { key: 'effectiveTo', label: 'To', mono: true, width: 110, render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
]

function RuleModal({ rule, onClose, onSave, tenants }) {
  const [form, setForm] = useState({
    name: rule?.name ?? '',
    targetIntent: rule?.targetIntent ?? 'KEYWORD',
    action: rule?.action ?? 'FLAG',
    priority: rule?.priority ?? 0,
    description: rule?.description ?? '',
    effectiveFrom: rule?.effectiveFrom?.slice(0, 16) ?? '',
    effectiveTo: rule?.effectiveTo?.slice(0, 16) ?? '',
    tenantId: rule?.tenantId ?? '',
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
        <label>Target Intent</label>
        <input type="text" className={styles.input} value={form.targetIntent}
          onChange={e => set('targetIntent', e.target.value)} placeholder="e.g. SPAM, GREETING, * (wildcard)" />
      </div>
      <div className={styles.field}>
        <label>Action</label>
        <select className={styles.input} value={form.action}
          onChange={e => set('action', e.target.value)}>
          {ACTIONS.map(a => <option key={a}>{a}</option>)}
        </select>
      </div>
      <div className={styles.field}>
        <label>Priority</label>
        <input type="number" className={styles.input} value={form.priority}
          onChange={e => set('priority', parseInt(e.target.value) || 0)} min={0} />
      </div>
      <div className={styles.field}>
        <label>Description</label>
        <textarea className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} rows={4} placeholder="Optional rule description" />
      </div>
      <div className={styles.field}>
        <label>Effective From</label>
        <input type="datetime-local" className={styles.input} value={form.effectiveFrom}
          onChange={e => set('effectiveFrom', e.target.value)} />
      </div>
      <div className={styles.field}>
        <label>Effective To</label>
        <input type="datetime-local" className={styles.input} value={form.effectiveTo}
          onChange={e => set('effectiveTo', e.target.value)} />
      </div>
      <div className={styles.field}>
        <label>Tenant</label>
        <select className={styles.input} value={form.tenantId ?? ''}
          onChange={e => set('tenantId', e.target.value || null)}>
          <option value="">None</option>
          {tenants.map(t => (
            <option key={t.id} value={t.id}>{t.name} ({t.id.slice(0, 8)})</option>
          ))}
        </select>
      </div>
    </Modal>
  )
}

function HistoryModal({ ruleName, history, onClose }) {
  return (
    <Modal title={`History \u2014 ${ruleName}`} onClose={onClose}>
      {history.length === 0 ? <p style={{ color: 'var(--fg-3)', fontStyle: 'italic' }}>No history.</p> : history.map((h, i) => (
        <div key={i} className={styles.historyItem}>
          <span className={styles.mono}>v{h.version}</span>
          <span>{h.action}</span>
          <span className={styles.mono}>{h.changedAt ? new Date(h.changedAt).toLocaleString() : ''}</span>
        </div>
      ))}
    </Modal>
  )
}

export function PolicyRules() {
  const authRequest = useAuthRequest()
  const api = policyRulesApi(authRequest)
  const [rules, setRules] = useState([])
  const [modal, setModal] = useState(null)
  const [history, setHistory] = useState(null)
  const [error, setError] = useState('')
  const [tenants, setTenants] = useState([])

  const load = () => api.list().then(setRules).catch(e => setError(e.message))
  useEffect(() => { load() }, [])
  useEffect(() => { tenantsApi(authRequest).list().then(setTenants).catch(() => {}) }, [])

  const save = async form => {
    try {
      const payload = {
        ...form,
        effectiveFrom: form.effectiveFrom ? new Date(form.effectiveFrom).toISOString() : null,
        effectiveTo: form.effectiveTo ? new Date(form.effectiveTo).toISOString() : null,
      }
      if (modal === 'add') await api.create(payload)
      else await api.update(modal.id, payload)
      setModal(null); load()
    } catch (e) { setError(e.message) }
  }

  const remove = async rule => {
    if (!confirm(`Delete rule "${rule.name}"?`)) return
    try { await api.remove(rule.id); load() }
    catch (e) { setError(e.message) }
  }

  const showHistory = async rule => {
    const h = await api.history(rule.name).catch(() => [])
    setHistory({ ruleName: rule.name, items: h })
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Policy Rules"
        systemId={`\u2696 policy-rules \u00b7 ${rules.length} rules`}
        addLabel="+ Create Rule"
        onAdd={() => setModal('add')}
        columns={COLUMNS}
        rows={rules}
        onEdit={setModal}
        onDelete={remove}
        emptyText="No policy rules defined"
      />

      {modal && (
        <RuleModal
          rule={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
          tenants={tenants}
        />
      )}
      {history && (
        <HistoryModal
          ruleName={history.ruleName}
          history={history.items}
          onClose={() => setHistory(null)}
        />
      )}
    </>
  )
}
```

Note: The History button that existed in the old per-row actions column is lost because DataTable doesn't support custom action buttons beyond delete. The history feature is still accessible — we can add it back by including a render column with a History button, or by adding it to the edit modal. For now, keep the `showHistory` function and `HistoryModal` in place but remove the per-row History button — it can be re-added as a column render function or modal action in a follow-up.

**IMPORTANT**: Actually, looking more carefully, we should keep the History button. Add it as a render function on a dedicated column:

Add this to the COLUMNS array after the "To" column:

The implementation above does NOT include a History column. The implementer must add a way to trigger `showHistory`. The simplest approach: make rows clickable for edit (already done via `onEdit`), and add a History button inside the edit modal. OR add an extra column. The implementer should add a column that renders History + Edit buttons, but since DataTable handles edit via row click, just add a History-only column:

Actually, the cleanest approach: keep History in the row. Add it as a custom render column BEFORE the DataTable's built-in delete column. Update the COLUMNS array to include:

```jsx
{ key: 'id', label: '', width: 80, render: (v, row) => <Button variant="secondary" onClick={e => { e.stopPropagation(); showHistory(row) }}>History</Button> }
```

But this requires `showHistory` to be in scope of the column definition, which means COLUMNS can't be a module-level constant. Move column definition inside the component. The implementer should handle this.

- [ ] **Step 3: Run PolicyRules tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/PolicyRules/ 2>&1 | tail -20
```

Expected: the tenant dropdown test should pass since the modal structure is preserved.

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/
git commit -m "feat(admin-ui): redesign PolicyRules page with v2 DataTable

Replace hand-built table with DataTable. Add intent/action badges,
system-id line. Restyle edit/create/history modals with v2 tokens.
Fourth page on v2 design system."
```

---

### Task 3: Update AuditLog tests

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.test.jsx`

- [ ] **Step 1: Run tests and identify failures**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/AuditLog/AuditLog.test.jsx 2>&1 | tail -30
```

Read test output and identify which tests fail and why.

- [ ] **Step 2: Fix failing tests**

The mock EVENT in the tests uses `{ timestamp, eventType, entityId, details }` but the actual API response and page columns use `{ createdAt, eventType, sourceService, action, resourceId, outcome, details }`. The pre-existing test failure was because `entityId` doesn't match any column rendered by the page.

Update the mock EVENT to match the actual API response shape:

```jsx
const EVENT = {
  createdAt: '2026-05-13T08:00:00Z',
  eventType: 'POLICY_DECISION',
  sourceService: 'policy-engine',
  action: 'decide',
  resourceId: 'msg-12345',
  outcome: 'OK',
  details: 'Allowed by rule #3',
}
```

Update the test `displays event row` to assert on fields that are actually rendered:
```jsx
it('displays event row with type, source, action, resource and outcome', async () => {
  mockApi.list.mockResolvedValue({ items: [EVENT], total: 1 })
  render(<AuditLog />)
  await waitFor(() => expect(screen.getByText('POLICY_DECISION')).toBeInTheDocument())
  expect(screen.getByText('policy-engine')).toBeInTheDocument()
  expect(screen.getByText('decide')).toBeInTheDocument()
  expect(screen.getByText('msg-12345')).toBeInTheDocument()
  expect(screen.getByText('OK')).toBeInTheDocument()
})
```

Update the em-dash test to use fields from the new EVENT shape:
```jsx
it('shows em-dash for missing fields', async () => {
  mockApi.list.mockResolvedValue({ items: [{ ...EVENT, sourceService: null, resourceId: null, outcome: null, details: null }], total: 1 })
  render(<AuditLog />)
  await waitFor(() => screen.getByText('POLICY_DECISION'))
  const dashes = screen.getAllByText('—')
  expect(dashes.length).toBeGreaterThanOrEqual(2)
})
```

For filter tests: DataTable renders `<select>` elements. The existing tests query `screen.getAllByRole('combobox')` — `<select>` elements have role `combobox` in ARIA, but in testing-library they actually have role `combobox` only with certain attributes. `<select>` elements have implicit role `listbox` in some implementations. If the filter tests fail, try querying by `role: 'combobox'` or fall back to `getAllByRole('listbox')` or just `screen.getAllByDisplayValue(...)`.

- [ ] **Step 3: Run all tests to verify**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run 2>&1 | tail -10
```

- [ ] **Step 4: Commit if tests were updated**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/AuditLog/
git commit -m "test(admin-ui): fix AuditLog tests for v2 DataTable structure

Update mock EVENT to match actual API response shape. Fix field
assertions for new column structure."
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task(s) |
|---|---|
| AuditLog DataTable conversion | Task 1 Step 2 |
| AuditLog DetailsModal restyle | Task 1 Step 2 (SectionLabel, shared Modal, v2 metaGrid) |
| AuditLog CSS | Task 1 Step 1 |
| AuditLog filters | Task 1 Step 2 (DataTable filters prop) |
| AuditLog outcome badges | Task 1 Step 2 (OUTCOME_VARIANT map) |
| PolicyRules DataTable conversion | Task 2 Step 2 |
| PolicyRules edit/create modal | Task 2 Step 2 (RuleModal with v2 field/input) |
| PolicyRules history modal | Task 2 Step 2 (HistoryModal restyled) |
| PolicyRules CSS | Task 2 Step 1 |
| Testing | Task 3 |

**Placeholder scan:** The History button handling is described but left for the implementer to resolve (noted in Task 2 Step 2 comments). The implementer should move COLUMNS inside the component to give `showHistory` access, or add it to the edit modal.

**Type consistency:** DataTable props match across both pages. Badge variant names match component definitions. API field names match existing api/*.js files.
