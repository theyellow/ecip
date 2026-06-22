# Deep Research Agent — Plan C: Admin UI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Research Sessions admin UI page covering US-27.6 (research UI page), US-27.7 (live progress via polling), and US-27.8 (session history + comparison view).

**Architecture:** A new `/research` route renders a session list (DataTable) with a Start Research modal; `/research/:id` renders a tabbed detail page (Overview, Evidence, Report) that polls for status updates every 3 s while a session is RUNNING/CREATED. Comparison mode lets operators select two sessions and view them side-by-side. All data flows through the existing `ResearchProxyController` at `/api/admin/knowledge/research`.

**Tech Stack:** React 18, React Router, CSS Modules, vanilla fetch API (existing pattern), existing design tokens + components (Button, Badge, Modal, DataTable, SegmentedControl).

---

## File Map

| Action | Path (relative to `emcip-admin-ui/src/main/frontend/src/`) | Responsibility |
|--------|------|----------------|
| Create | `api/research.js` | researchApi factory — 6 API methods |
| Modify | `App.jsx` | Add `/research` and `/research/:id` routes |
| Modify | `layout/Sidebar/Sidebar.jsx` | Add Research nav entry |
| Create | `pages/Research/ResearchPage.jsx` | Session list + comparison mode + Start modal trigger |
| Create | `pages/Research/ResearchPage.module.css` | Page styles |
| Create | `pages/Research/StartResearchModal.jsx` | New session form modal |
| Create | `pages/Research/SessionDetailPage.jsx` | Session detail with polling, tabs, pause/resume |
| Create | `pages/Research/SessionDetailPage.module.css` | Detail page styles |
| Create | `pages/Research/ReportViewer.jsx` | Markdown content renderer + download |
| Create | `pages/Research/ComparisonView.jsx` | Side-by-side session comparison panel |

---

## Task 1: Research API service

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/api/research.js`

**Before coding, read:**
- `emcip-admin-ui/src/main/frontend/src/api/knowledge.js` — understand the factory pattern (function that receives `request` and returns an object of method functions)

- [ ] **Step 1: Create `research.js`**

```javascript
// emcip-admin-ui/src/main/frontend/src/api/research.js

export function researchApi(request) {
  return {
    /**
     * Start a new research session.
     * body: { question, tenantId?, maxIterations?, maxLlmCalls?, costLimitUsd?,
     *         webSearchEnabled?, reportTemplate? }
     * Returns ResearchSessionDto (synchronous — may take 10-30s).
     */
    startSession: (body) =>
      request('/api/admin/knowledge/research', {
        method: 'POST',
        body: JSON.stringify(body),
      }),

    /**
     * List all sessions, optionally filtered by tenantId.
     * Returns ResearchSessionDto[] (without evidence array populated).
     */
    listSessions: (tenantId) => {
      const params = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
      return request(`/api/admin/knowledge/research${params}`)
    },

    /**
     * Get a single session with its full evidence list.
     * Returns ResearchSessionDto (with evidence array).
     */
    getSession: (id) =>
      request(`/api/admin/knowledge/research/${id}`),

    /**
     * Pause a RUNNING session.
     * Returns updated ResearchSessionDto.
     */
    pauseSession: (id) =>
      request(`/api/admin/knowledge/research/${id}/pause`, { method: 'POST' }),

    /**
     * Resume a PAUSED session.
     * Returns updated ResearchSessionDto.
     */
    resumeSession: (id) =>
      request(`/api/admin/knowledge/research/${id}/resume`, { method: 'POST' }),

    /**
     * Get the compiled report for a completed session.
     * Returns ResearchReportDto: { id, tenantId, sessionId, template, title, content, version, createdAt }
     * content is the full Markdown text.
     */
    getReport: (id) =>
      request(`/api/admin/knowledge/research/${id}/report`),
  }
}
```

- [ ] **Step 2: Verify the file builds**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-ui package -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS (no new imports yet — just creating the file).

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/api/research.js
git commit -m "feat(27c): add researchApi service"
```

---

## Task 2: Research page shell + session list

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Research/ResearchPage.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Research/ResearchPage.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/App.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`

**Before coding, read:**
- `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx` — reference for `useAuth` + `useAuthRequest` usage and page structure
- `emcip-admin-ui/src/main/frontend/src/App.jsx` — understand route definition syntax
- `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx` — understand NAV array structure

- [ ] **Step 1: Create `ResearchPage.module.css`**

```css
/* emcip-admin-ui/src/main/frontend/src/pages/Research/ResearchPage.module.css */

.compareBanner {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-4);
  background: var(--accent-soft);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  margin-bottom: var(--sp-4);
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.checkCell {
  display: flex;
  align-items: center;
  justify-content: center;
}

.checkCell input[type='checkbox'] {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
  cursor: pointer;
}

.questionCell {
  max-width: 340px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
```

- [ ] **Step 2: Create `ResearchPage.jsx`**

```jsx
// emcip-admin-ui/src/main/frontend/src/pages/Research/ResearchPage.jsx

import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { researchApi } from '../../api/research'
import { StartResearchModal } from './StartResearchModal'
import { ComparisonView } from './ComparisonView'
import styles from './ResearchPage.module.css'

const STATUS_VARIANT = {
  COMPLETED: 'green',
  RUNNING:   'blue',
  CREATED:   'gray',
  PAUSED:    'yellow',
  FAILED:    'red',
}

const TEMPLATE_LABEL = {
  TOPIC:      'Topic',
  PERSON:     'Person',
  FACT_CHECK: 'Fact Check',
}

export function ResearchPage() {
  const { currentTenant } = useAuth()
  const request = useAuthRequest()
  const navigate = useNavigate()

  const [sessions, setSessions] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [showModal, setShowModal] = useState(false)
  // Comparison: set of up to 2 session IDs
  const [compareIds, setCompareIds] = useState(new Set())
  const [showCompare, setShowCompare] = useState(false)

  const loadSessions = useCallback(() => {
    setLoading(true)
    researchApi(request)
      .listSessions(currentTenant?.id)
      .then(setSessions)
      .catch((e) => setError(e?.body?.message ?? 'Failed to load sessions'))
      .finally(() => setLoading(false))
  }, [request, currentTenant])

  useEffect(() => {
    loadSessions()
  }, [loadSessions])

  function handleCheckbox(sessionId, checked) {
    setCompareIds((prev) => {
      const next = new Set(prev)
      if (checked) {
        if (next.size < 2) next.add(sessionId)
      } else {
        next.delete(sessionId)
      }
      return next
    })
  }

  function handleSessionStarted(newSession) {
    setShowModal(false)
    navigate(`/research/${newSession.id}`)
  }

  const filtered = statusFilter
    ? sessions.filter((s) => s.status === statusFilter)
    : sessions

  const compareList = sessions.filter((s) => compareIds.has(s.id))

  const COLUMNS = [
    {
      key: '_compare',
      label: '',
      width: '40px',
      render: (_, row) => (
        <div className={styles.checkCell}>
          <input
            type="checkbox"
            checked={compareIds.has(row.id)}
            disabled={!compareIds.has(row.id) && compareIds.size >= 2}
            onChange={(e) => handleCheckbox(row.id, e.target.checked)}
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      ),
    },
    {
      key: 'question',
      label: 'Question',
      render: (val) => <span className={styles.questionCell}>{val}</span>,
    },
    {
      key: 'status',
      label: 'Status',
      width: '120px',
      render: (_, row) => (
        <Badge variant={STATUS_VARIANT[row.status] ?? 'gray'}>{row.status}</Badge>
      ),
    },
    {
      key: 'reportTemplate',
      label: 'Template',
      width: '110px',
      render: (val) => TEMPLATE_LABEL[val] ?? val ?? '—',
    },
    {
      key: 'costUsedUsd',
      label: 'Cost',
      width: '80px',
      mono: true,
      render: (val) => `$${(val ?? 0).toFixed(2)}`,
    },
    {
      key: 'iterationsUsed',
      label: 'Iterations',
      width: '90px',
      mono: true,
      render: (val, row) => `${val} / ${row.maxIterations}`,
    },
    {
      key: 'createdAt',
      label: 'Created',
      width: '180px',
      mono: true,
      render: (val) => val ? new Date(val).toLocaleString() : '—',
    },
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h2>DEEP RESEARCH</h2>
          <div className="system-id">⌘ knowledge-engine · internal</div>
        </div>
        <div style={{ display: 'flex', gap: 'var(--sp-3)' }}>
          {compareIds.size === 2 && (
            <Button variant="secondary" onClick={() => setShowCompare(true)}>
              Compare (2)
            </Button>
          )}
          <Button variant="primary" onClick={() => setShowModal(true)}>
            Start Research
          </Button>
        </div>
      </div>

      {compareIds.size > 0 && compareIds.size < 2 && (
        <div className={styles.compareBanner}>
          {compareIds.size} of 2 sessions selected for comparison — select one more
          <Button variant="secondary" onClick={() => setCompareIds(new Set())}>
            Clear
          </Button>
        </div>
      )}

      {error && <p style={{ color: 'var(--signal-stop-fg)' }}>{error}</p>}

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
        emptyText={loading ? 'Loading sessions…' : 'No research sessions yet'}
      />

      {showModal && (
        <StartResearchModal
          onClose={() => setShowModal(false)}
          onStarted={handleSessionStarted}
        />
      )}

      {showCompare && compareList.length === 2 && (
        <ComparisonView
          sessionA={compareList[0]}
          sessionB={compareList[1]}
          onClose={() => setShowCompare(false)}
        />
      )}
    </div>
  )
}
```

- [ ] **Step 3: Add route to `App.jsx`**

Read `App.jsx`. Add the import and two routes.

Add import near the other page imports:
```jsx
import { ResearchPage } from './pages/Research/ResearchPage'
import { SessionDetailPage } from './pages/Research/SessionDetailPage'
```

Inside the `<Route element={<AppShell />}>` block, add after the `knowledge` route:
```jsx
<Route path="research" element={<ResearchPage />} />
<Route path="research/:id" element={<SessionDetailPage />} />
```

- [ ] **Step 4: Add Research to Sidebar nav**

Read `Sidebar.jsx`. In the `NAV` array, add after the `knowledge` entry:
```javascript
{ to: '/research', label: 'Research', icon: '⬟', permission: 'KNOWLEDGE_READ' },
```

- [ ] **Step 5: Build to verify**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-ui package -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS. (SessionDetailPage and ComparisonView don't exist yet — the build will fail on those imports. That's OK — note them as stubs until Task 4 + 6. Actually: to avoid build failures, create stub files first.)

Actually: create stub files now so the build passes immediately, then fill them in Tasks 4 and 6.

Create `emcip-admin-ui/src/main/frontend/src/pages/Research/SessionDetailPage.jsx`:
```jsx
export function SessionDetailPage() { return null }
```

Create `emcip-admin-ui/src/main/frontend/src/pages/Research/ComparisonView.jsx`:
```jsx
export function ComparisonView() { return null }
```

Create `emcip-admin-ui/src/main/frontend/src/pages/Research/StartResearchModal.jsx`:
```jsx
export function StartResearchModal() { return null }
```

Now build:
```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-ui package -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
git add \
  emcip-admin-ui/src/main/frontend/src/pages/Research/ResearchPage.jsx \
  emcip-admin-ui/src/main/frontend/src/pages/Research/ResearchPage.module.css \
  emcip-admin-ui/src/main/frontend/src/pages/Research/SessionDetailPage.jsx \
  emcip-admin-ui/src/main/frontend/src/pages/Research/ComparisonView.jsx \
  emcip-admin-ui/src/main/frontend/src/pages/Research/StartResearchModal.jsx \
  emcip-admin-ui/src/main/frontend/src/App.jsx \
  emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx
git commit -m "feat(27c): add Research page shell, session list, routes, sidebar entry"
```

---

## Task 3: Start Research modal

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Research/StartResearchModal.jsx` (replace stub)

**Before coding, read:**
- `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.jsx` — reference for form modal pattern
- `emcip-admin-ui/src/main/frontend/src/components/Modal/Modal.jsx` — Modal props
- `emcip-admin-ui/src/main/frontend/src/components/SegmentedControl/SegmentedControl.jsx` — props confirmed: `{ options, value, onChange }`

The modal submits `POST /api/admin/knowledge/research`. Since `startSession` is synchronous and may take 10-30 s (LLM calls), the submit button shows a loading state while the POST is in flight.

- [ ] **Step 1: Replace `StartResearchModal.jsx` with full implementation**

```jsx
// emcip-admin-ui/src/main/frontend/src/pages/Research/StartResearchModal.jsx

import { useState } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { Modal } from '../../components/Modal/Modal'
import { SegmentedControl } from '../../components/SegmentedControl/SegmentedControl'
import { researchApi } from '../../api/research'

const TEMPLATE_OPTIONS = [
  { value: 'TOPIC',      label: 'Topic' },
  { value: 'PERSON',     label: 'Person' },
  { value: 'FACT_CHECK', label: 'Fact Check' },
]

export function StartResearchModal({ onClose, onStarted }) {
  const { currentTenant } = useAuth()
  const request = useAuthRequest()

  const [question, setQuestion] = useState('')
  const [template, setTemplate] = useState('TOPIC')
  const [webSearch, setWebSearch] = useState(false)
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [maxIterations, setMaxIterations] = useState(10)
  const [maxLlmCalls, setMaxLlmCalls] = useState(20)
  const [costLimit, setCostLimit] = useState(1.0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit() {
    if (!question.trim()) {
      setError('Question is required.')
      return
    }
    setError('')
    setLoading(true)
    try {
      const body = {
        question: question.trim(),
        reportTemplate: template,
        webSearchEnabled: webSearch,
        maxIterations: Number(maxIterations) || 10,
        maxLlmCalls: Number(maxLlmCalls) || 20,
        costLimitUsd: Number(costLimit) || 1.0,
      }
      if (currentTenant?.id) {
        body.tenantId = currentTenant.id
      }
      const session = await researchApi(request).startSession(body)
      onStarted(session)
    } catch (e) {
      setError(e?.body?.message ?? 'Failed to start research session.')
      setLoading(false)
    }
  }

  return (
    <Modal
      title="START RESEARCH SESSION"
      onClose={onClose}
      onSubmit={handleSubmit}
      submitLabel={loading ? 'Running…' : 'Run Research'}
    >
      <label style={{ display: 'block', marginBottom: 'var(--sp-1)', fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-2)' }}>
        QUESTION *
      </label>
      <textarea
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
        rows={4}
        placeholder="What do you want to research? e.g. 'Is John Smith coordinating disinformation campaigns?'"
        disabled={loading}
        style={{
          width: '100%',
          boxSizing: 'border-box',
          padding: 'var(--sp-2) var(--sp-3)',
          background: 'var(--bg-input)',
          border: '1px solid var(--border)',
          color: 'var(--fg-1)',
          fontFamily: 'var(--font-body)',
          fontSize: 14,
          lineHeight: 1.5,
          resize: 'vertical',
          borderRadius: 'var(--r-xs)',
        }}
      />

      <div style={{ marginTop: 'var(--sp-4)' }}>
        <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-2)', marginBottom: 'var(--sp-2)' }}>
          REPORT TEMPLATE
        </div>
        <SegmentedControl
          options={TEMPLATE_OPTIONS}
          value={template}
          onChange={setTemplate}
        />
      </div>

      <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--sp-2)', marginTop: 'var(--sp-4)', cursor: 'pointer', fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-2)' }}>
        <input
          type="checkbox"
          checked={webSearch}
          onChange={(e) => setWebSearch(e.target.checked)}
          disabled={loading}
          style={{ width: 16, height: 16, accentColor: 'var(--accent)', cursor: 'pointer' }}
        />
        Enable web search (SearXNG / Brave fallback)
      </label>

      <div style={{ marginTop: 'var(--sp-4)' }}>
        <button
          type="button"
          onClick={() => setShowAdvanced((v) => !v)}
          style={{
            background: 'none',
            border: 'none',
            color: 'var(--accent)',
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            cursor: 'pointer',
            padding: 0,
            letterSpacing: '0.08em',
          }}
        >
          {showAdvanced ? '▾' : '▸'} ADVANCED SETTINGS
        </button>

        {showAdvanced && (
          <div style={{ marginTop: 'var(--sp-3)', display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 'var(--sp-3)' }}>
            <div>
              <label style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-2)', display: 'block', marginBottom: 4 }}>
                MAX ITERATIONS
              </label>
              <input
                type="number"
                min={1}
                max={50}
                value={maxIterations}
                onChange={(e) => setMaxIterations(e.target.value)}
                disabled={loading}
                style={{ width: '100%', padding: '6px 8px', background: 'var(--bg-input)', border: '1px solid var(--border)', color: 'var(--fg-1)', fontFamily: 'var(--font-mono)', fontSize: 13, boxSizing: 'border-box' }}
              />
            </div>
            <div>
              <label style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-2)', display: 'block', marginBottom: 4 }}>
                MAX LLM CALLS
              </label>
              <input
                type="number"
                min={1}
                max={100}
                value={maxLlmCalls}
                onChange={(e) => setMaxLlmCalls(e.target.value)}
                disabled={loading}
                style={{ width: '100%', padding: '6px 8px', background: 'var(--bg-input)', border: '1px solid var(--border)', color: 'var(--fg-1)', fontFamily: 'var(--font-mono)', fontSize: 13, boxSizing: 'border-box' }}
              />
            </div>
            <div>
              <label style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-2)', display: 'block', marginBottom: 4 }}>
                COST LIMIT (USD)
              </label>
              <input
                type="number"
                min={0.01}
                step={0.10}
                value={costLimit}
                onChange={(e) => setCostLimit(e.target.value)}
                disabled={loading}
                style={{ width: '100%', padding: '6px 8px', background: 'var(--bg-input)', border: '1px solid var(--border)', color: 'var(--fg-1)', fontFamily: 'var(--font-mono)', fontSize: 13, boxSizing: 'border-box' }}
              />
            </div>
          </div>
        )}
      </div>

      {loading && (
        <p style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--accent)', marginTop: 'var(--sp-3)' }}>
          ◈ Running research… this may take up to 30 seconds.
        </p>
      )}

      {error && (
        <p style={{ color: 'var(--signal-stop-fg)', fontFamily: 'var(--font-mono)', fontSize: 12, marginTop: 'var(--sp-2)' }}>
          {error}
        </p>
      )}
    </Modal>
  )
}
```

- [ ] **Step 2: Build to verify**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-ui package -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Research/StartResearchModal.jsx
git commit -m "feat(27c): add StartResearchModal — question, template, web search, advanced settings"
```

---

## Task 4: Session detail page + live polling

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Research/SessionDetailPage.jsx` (replace stub)
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Research/SessionDetailPage.module.css`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Research/ReportViewer.jsx` (create full implementation, not stub — Task 5 fills it out but we need the import to resolve)

**Before coding, read:**
- `emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.jsx` — reference for `setInterval` polling pattern
- `emcip-admin-ui/src/main/frontend/src/pages/Research/ResearchPage.jsx` — for STATUS_VARIANT and TEMPLATE_LABEL constants (duplicate them here; don't import from another page)

The detail page:
- Loads the session on mount via `getSession(id)`
- If status is `CREATED` or `RUNNING`, starts a 3 s polling interval
- Stops polling when status becomes `COMPLETED`, `FAILED`, or `PAUSED`
- Three tabs: Overview, Evidence, Report

- [ ] **Step 1: Create `SessionDetailPage.module.css`**

```css
/* emcip-admin-ui/src/main/frontend/src/pages/Research/SessionDetailPage.module.css */

.backLink {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-1);
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
  text-decoration: none;
  margin-bottom: var(--sp-4);
  cursor: pointer;
  background: none;
  border: none;
  padding: 0;
}

.backLink:hover {
  color: var(--accent);
}

.question {
  font-family: var(--font-body);
  font-size: 18px;
  color: var(--fg-1);
  margin: var(--sp-3) 0;
  line-height: 1.4;
}

.metaRow {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-4);
  flex-wrap: wrap;
}

.metaStat {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
  background: var(--bg-sunken);
  border: 1px solid var(--border);
  padding: 2px 8px;
  border-radius: var(--r-xs);
}

.tabBar {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--border);
  margin-bottom: var(--sp-4);
}

.tab {
  padding: 8px 20px;
  font-family: var(--font-display);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fg-2);
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}

.tab:hover {
  color: var(--fg-1);
}

.tabActive {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

.metaGrid {
  display: grid;
  grid-template-columns: 160px 1fr;
  gap: var(--sp-2) var(--sp-4);
  font-family: var(--font-mono);
  font-size: 13px;
  margin-bottom: var(--sp-4);
}

.metaLabel {
  color: var(--fg-2);
}

.metaValue {
  color: var(--fg-1);
}

.errorBox {
  background: var(--signal-stop-bg);
  color: var(--signal-stop-fg);
  border: 1px solid var(--signal-stop-fg);
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-xs);
  font-family: var(--font-mono);
  font-size: 13px;
  margin-top: var(--sp-3);
}

.actions {
  display: flex;
  gap: var(--sp-3);
  margin-bottom: var(--sp-4);
}

.pollingIndicator {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.evidenceTable {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.evidenceTable th {
  text-align: left;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.08em;
  color: var(--fg-2);
  padding: var(--sp-2) var(--sp-3);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.evidenceTable td {
  padding: var(--sp-2) var(--sp-3);
  border-bottom: 1px solid var(--rule);
  vertical-align: top;
  color: var(--fg-1);
  line-height: 1.4;
}

.evidenceTable tr:hover td {
  background: var(--accent-soft);
}

.findingCell {
  max-width: 400px;
}

.sourceRefCell {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
}

.confScore {
  font-family: var(--font-mono);
  font-size: 12px;
}

.noReport {
  text-align: center;
  padding: var(--sp-7);
  color: var(--fg-3);
  font-family: var(--font-mono);
  font-size: 13px;
}
```

- [ ] **Step 2: Create stub `ReportViewer.jsx`** (full implementation in Task 5)

```jsx
// emcip-admin-ui/src/main/frontend/src/pages/Research/ReportViewer.jsx
export function ReportViewer({ report }) {
  if (!report) return null
  return (
    <div style={{ fontFamily: 'var(--font-mono)', fontSize: 13, color: 'var(--fg-2)', padding: 'var(--sp-4)' }}>
      Loading report…
    </div>
  )
}
```

- [ ] **Step 3: Replace `SessionDetailPage.jsx` with full implementation**

```jsx
// emcip-admin-ui/src/main/frontend/src/pages/Research/SessionDetailPage.jsx

import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuthRequest } from '../../auth/AuthContext'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { researchApi } from '../../api/research'
import { ReportViewer } from './ReportViewer'
import styles from './SessionDetailPage.module.css'

const STATUS_VARIANT = {
  COMPLETED: 'green',
  RUNNING:   'blue',
  CREATED:   'gray',
  PAUSED:    'yellow',
  FAILED:    'red',
}

const TEMPLATE_LABEL = {
  TOPIC:      'Topic',
  PERSON:     'Person',
  FACT_CHECK: 'Fact Check',
}

const STRATEGY_LABEL = {
  TOPIC_EXPLORATION: 'Topic',
  PERSON_ANALYSIS:   'Person',
  OPINION_MAPPING:   'Opinion',
  COMPARISON:        'Compare',
  FACT_VERIFICATION: 'Fact',
}

const POLLING_STATUSES = new Set(['CREATED', 'RUNNING'])
const POLL_INTERVAL_MS = 3000

export function SessionDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const request = useAuthRequest()

  const [session, setSession] = useState(null)
  const [report, setReport] = useState(null)
  const [loadingReport, setLoadingReport] = useState(false)
  const [activeTab, setActiveTab] = useState('overview')
  const [error, setError] = useState('')
  const [actionLoading, setActionLoading] = useState(false)

  const pollingRef = useRef(null)
  const api = researchApi(request)

  function stopPolling() {
    if (pollingRef.current) {
      clearInterval(pollingRef.current)
      pollingRef.current = null
    }
  }

  function startPolling() {
    stopPolling()
    pollingRef.current = setInterval(async () => {
      try {
        const updated = await api.getSession(id)
        setSession(updated)
        if (!POLLING_STATUSES.has(updated.status)) {
          stopPolling()
          // Load report if completed
          if (updated.status === 'COMPLETED' && updated.reportId) {
            loadReport()
          }
        }
      } catch (_) {
        // silent — keep polling
      }
    }, POLL_INTERVAL_MS)
  }

  async function loadReport() {
    setLoadingReport(true)
    try {
      const r = await api.getReport(id)
      setReport(r)
    } catch (_) {
      // report may not exist yet
    } finally {
      setLoadingReport(false)
    }
  }

  useEffect(() => {
    api.getSession(id)
      .then((s) => {
        setSession(s)
        if (POLLING_STATUSES.has(s.status)) {
          startPolling()
        }
        if (s.status === 'COMPLETED' && s.reportId) {
          loadReport()
        }
      })
      .catch((e) => setError(e?.body?.message ?? 'Failed to load session'))

    return () => stopPolling()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  async function handlePause() {
    setActionLoading(true)
    try {
      const updated = await api.pauseSession(id)
      setSession(updated)
      stopPolling()
    } catch (e) {
      setError(e?.body?.message ?? 'Failed to pause session')
    } finally {
      setActionLoading(false)
    }
  }

  async function handleResume() {
    setActionLoading(true)
    try {
      const updated = await api.resumeSession(id)
      setSession(updated)
      startPolling()
    } catch (e) {
      setError(e?.body?.message ?? 'Failed to resume session')
    } finally {
      setActionLoading(false)
    }
  }

  if (error && !session) {
    return (
      <div className="page">
        <button className={styles.backLink} onClick={() => navigate('/research')}>
          ← Back to Research
        </button>
        <p style={{ color: 'var(--signal-stop-fg)' }}>{error}</p>
      </div>
    )
  }

  if (!session) {
    return (
      <div className="page">
        <button className={styles.backLink} onClick={() => navigate('/research')}>
          ← Back to Research
        </button>
        <p style={{ color: 'var(--fg-3)', fontFamily: 'var(--font-mono)', fontSize: 13 }}>
          Loading…
        </p>
      </div>
    )
  }

  const evidenceCount = session.evidence?.length ?? 0

  return (
    <div className="page">
      <button className={styles.backLink} onClick={() => navigate('/research')}>
        ← Back to Research
      </button>

      <div className="page-header" style={{ marginBottom: 'var(--sp-2)' }}>
        <div>
          <h2>SESSION DETAIL</h2>
          <div className="system-id">⌘ knowledge-engine · internal</div>
        </div>
      </div>

      <p className={styles.question}>{session.question}</p>

      <div className={styles.metaRow}>
        <Badge variant={STATUS_VARIANT[session.status] ?? 'gray'}>{session.status}</Badge>
        {session.reportTemplate && (
          <Badge variant="gray">{TEMPLATE_LABEL[session.reportTemplate] ?? session.reportTemplate}</Badge>
        )}
        <span className={styles.metaStat}>
          {session.iterationsUsed} / {session.maxIterations} iterations
        </span>
        <span className={styles.metaStat}>
          {session.llmCallsUsed} / {session.maxLlmCalls} LLM calls
        </span>
        <span className={styles.metaStat}>
          ${(session.costUsedUsd ?? 0).toFixed(2)} / ${(session.costLimitUsd ?? 1).toFixed(2)}
        </span>
        {POLLING_STATUSES.has(session.status) && (
          <span className={styles.pollingIndicator}>◈ live</span>
        )}
      </div>

      {(session.status === 'RUNNING') && (
        <div className={styles.actions}>
          <Button variant="secondary" onClick={handlePause} disabled={actionLoading}>
            Pause
          </Button>
        </div>
      )}
      {session.status === 'PAUSED' && (
        <div className={styles.actions}>
          <Button variant="secondary" onClick={handleResume} disabled={actionLoading}>
            Resume
          </Button>
        </div>
      )}

      {session.errorMessage && (
        <div className={styles.errorBox}>{session.errorMessage}</div>
      )}

      {/* Tab bar */}
      <div className={styles.tabBar}>
        {[
          { key: 'overview', label: 'Overview' },
          { key: 'evidence', label: `Evidence (${evidenceCount})` },
          { key: 'report',   label: 'Report' },
        ].map(({ key, label }) => (
          <button
            key={key}
            className={`${styles.tab} ${activeTab === key ? styles.tabActive : ''}`}
            onClick={() => setActiveTab(key)}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Overview tab */}
      {activeTab === 'overview' && (
        <div className={styles.metaGrid}>
          <span className={styles.metaLabel}>Session ID</span>
          <span className={styles.metaValue} style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{session.id}</span>

          <span className={styles.metaLabel}>Tenant ID</span>
          <span className={styles.metaValue} style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{session.tenantId ?? '—'}</span>

          <span className={styles.metaLabel}>Status</span>
          <span className={styles.metaValue}>{session.status}</span>

          <span className={styles.metaLabel}>Template</span>
          <span className={styles.metaValue}>{TEMPLATE_LABEL[session.reportTemplate] ?? session.reportTemplate ?? '—'}</span>

          <span className={styles.metaLabel}>Web Search</span>
          <span className={styles.metaValue}>{session.webSearchEnabled ? 'Enabled' : 'Disabled'}</span>

          <span className={styles.metaLabel}>Iterations</span>
          <span className={styles.metaValue}>{session.iterationsUsed} used of {session.maxIterations} max</span>

          <span className={styles.metaLabel}>LLM Calls</span>
          <span className={styles.metaValue}>{session.llmCallsUsed} used of {session.maxLlmCalls} max</span>

          <span className={styles.metaLabel}>Cost Used</span>
          <span className={styles.metaValue}>${(session.costUsedUsd ?? 0).toFixed(4)} of ${(session.costLimitUsd ?? 1).toFixed(2)} limit</span>

          <span className={styles.metaLabel}>Created</span>
          <span className={styles.metaValue} style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>
            {session.createdAt ? new Date(session.createdAt).toLocaleString() : '—'}
          </span>

          <span className={styles.metaLabel}>Updated</span>
          <span className={styles.metaValue} style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>
            {session.updatedAt ? new Date(session.updatedAt).toLocaleString() : '—'}
          </span>

          <span className={styles.metaLabel}>Report ID</span>
          <span className={styles.metaValue} style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>
            {session.reportId ?? '—'}
          </span>
        </div>
      )}

      {/* Evidence tab */}
      {activeTab === 'evidence' && (
        evidenceCount === 0 ? (
          <p style={{ color: 'var(--fg-3)', fontFamily: 'var(--font-mono)', fontSize: 13 }}>
            No evidence collected yet.
          </p>
        ) : (
          <table className={styles.evidenceTable}>
            <thead>
              <tr>
                <th>#</th>
                <th>Sub-question</th>
                <th>Strategy</th>
                <th>Finding</th>
                <th>Source</th>
                <th>Ref</th>
                <th>Conf.</th>
              </tr>
            </thead>
            <tbody>
              {session.evidence.map((e, i) => (
                <tr key={e.id}>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-3)' }}>
                    {e.iteration + 1}
                  </td>
                  <td style={{ fontSize: 12, color: 'var(--fg-2)', maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {e.subQuestion}
                  </td>
                  <td>
                    <Badge variant="gray">{STRATEGY_LABEL[e.queryStrategy] ?? e.queryStrategy}</Badge>
                  </td>
                  <td className={styles.findingCell}>{e.finding}</td>
                  <td>
                    <Badge variant={e.sourceType === 'WEB_SEARCH' ? 'blue' : 'gray'}>
                      {e.sourceType === 'WEB_SEARCH' ? 'WEB' : 'KB'}
                    </Badge>
                  </td>
                  <td className={styles.sourceRefCell} title={e.sourceRef}>{e.sourceRef}</td>
                  <td className={styles.confScore}>
                    {(e.confidenceScore * 100).toFixed(0)}%
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      )}

      {/* Report tab */}
      {activeTab === 'report' && (
        session.status !== 'COMPLETED' ? (
          <p className={styles.noReport}>
            Run research to completion to generate a report.
          </p>
        ) : loadingReport ? (
          <p className={styles.noReport}>Loading report…</p>
        ) : report ? (
          <ReportViewer report={report} />
        ) : (
          <p className={styles.noReport}>No report available for this session.</p>
        )
      )}
    </div>
  )
}
```

- [ ] **Step 4: Build to verify**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-ui package -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip
git add \
  emcip-admin-ui/src/main/frontend/src/pages/Research/SessionDetailPage.jsx \
  emcip-admin-ui/src/main/frontend/src/pages/Research/SessionDetailPage.module.css \
  emcip-admin-ui/src/main/frontend/src/pages/Research/ReportViewer.jsx
git commit -m "feat(27c): add SessionDetailPage — tabs, live polling, evidence table, pause/resume"
```

---

## Task 5: Report viewer

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Research/ReportViewer.jsx` (replace stub)

The report content is LLM-generated Markdown with predictable structure:
`## Section Title`, `- bullet`, `### sub-heading`, body paragraphs.
We render it with a simple line-by-line React renderer — no library dependency, safe (no `dangerouslySetInnerHTML`).

- [ ] **Step 1: Replace `ReportViewer.jsx` with full implementation**

```jsx
// emcip-admin-ui/src/main/frontend/src/pages/Research/ReportViewer.jsx

import styles from './ReportViewer.module.css'

/**
 * Renders a ResearchReportDto's Markdown content as styled React elements.
 * Handles: ## headings, ### sub-headings, - bullet lists, blank lines, body paragraphs.
 * Does NOT use dangerouslySetInnerHTML — content is rendered as text nodes only.
 */
function renderMarkdownLines(content) {
  if (!content) return null
  const lines = content.split('\n')
  const elements = []
  let listBuffer = []
  let key = 0

  function flushList() {
    if (listBuffer.length > 0) {
      elements.push(
        <ul key={key++} className={styles.list}>
          {listBuffer.map((item, i) => (
            <li key={i} className={styles.listItem}>{item}</li>
          ))}
        </ul>
      )
      listBuffer = []
    }
  }

  for (const line of lines) {
    if (line.startsWith('## ')) {
      flushList()
      elements.push(<h3 key={key++} className={styles.h2}>{line.slice(3)}</h3>)
    } else if (line.startsWith('### ')) {
      flushList()
      elements.push(<h4 key={key++} className={styles.h3}>{line.slice(4)}</h4>)
    } else if (line.startsWith('# ')) {
      flushList()
      elements.push(<h2 key={key++} className={styles.h1}>{line.slice(2)}</h2>)
    } else if (line.startsWith('- ')) {
      listBuffer.push(line.slice(2))
    } else if (line.trim() === '') {
      flushList()
      elements.push(<div key={key++} className={styles.spacer} />)
    } else {
      flushList()
      elements.push(<p key={key++} className={styles.para}>{line}</p>)
    }
  }
  flushList()
  return elements
}

export function ReportViewer({ report }) {
  if (!report) return null

  function handleDownload() {
    const blob = new Blob([report.content], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `research-report-${report.sessionId}.md`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <div>
          <div className={styles.title}>{report.title}</div>
          <div className={styles.meta}>
            {report.template} · v{report.version} · {report.createdAt ? new Date(report.createdAt).toLocaleString() : ''}
          </div>
        </div>
        <button className={styles.downloadBtn} onClick={handleDownload} type="button">
          ↓ Download .md
        </button>
      </div>

      <div className={styles.content}>
        {renderMarkdownLines(report.content)}
      </div>
    </div>
  )
}
```

- [ ] **Step 2: Create `ReportViewer.module.css`**

```css
/* emcip-admin-ui/src/main/frontend/src/pages/Research/ReportViewer.module.css */

.container {
  background: var(--bg-sunken);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: var(--sp-4);
  border-bottom: 1px solid var(--border);
  gap: var(--sp-4);
}

.title {
  font-family: var(--font-display);
  font-size: 14px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--accent);
  margin-bottom: var(--sp-1);
}

.meta {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  letter-spacing: 0.06em;
}

.downloadBtn {
  flex-shrink: 0;
  background: none;
  border: 1px solid var(--border);
  color: var(--fg-2);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.08em;
  padding: 4px 12px;
  cursor: pointer;
  border-radius: var(--r-xs);
  white-space: nowrap;
  transition: color 0.15s, border-color 0.15s;
}

.downloadBtn:hover {
  color: var(--accent);
  border-color: var(--accent);
}

.content {
  padding: var(--sp-5) var(--sp-5) var(--sp-6);
  max-height: 60vh;
  overflow-y: auto;
}

.h1 {
  font-family: var(--font-display);
  font-size: 18px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--accent);
  margin: var(--sp-4) 0 var(--sp-3);
}

.h2 {
  font-family: var(--font-display);
  font-size: 13px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--accent);
  margin: var(--sp-4) 0 var(--sp-2);
  padding-bottom: var(--sp-1);
  border-bottom: 1px solid var(--rule);
}

.h3 {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
  letter-spacing: 0.08em;
  margin: var(--sp-3) 0 var(--sp-1);
  text-transform: uppercase;
}

.para {
  font-family: var(--font-body);
  font-size: 14px;
  line-height: 1.6;
  color: var(--fg-1);
  margin: 0 0 var(--sp-2);
}

.list {
  margin: 0 0 var(--sp-3);
  padding-left: var(--sp-4);
}

.listItem {
  font-family: var(--font-body);
  font-size: 14px;
  line-height: 1.6;
  color: var(--fg-1);
  margin-bottom: var(--sp-1);
}

.listItem::marker {
  color: var(--accent);
}

.spacer {
  height: var(--sp-2);
}
```

- [ ] **Step 3: Build to verify**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-ui package -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip
git add \
  emcip-admin-ui/src/main/frontend/src/pages/Research/ReportViewer.jsx \
  emcip-admin-ui/src/main/frontend/src/pages/Research/ReportViewer.module.css
git commit -m "feat(27c): add ReportViewer — Markdown renderer with download button"
```

---

## Task 6: Comparison view

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Research/ComparisonView.jsx` (replace stub)
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Research/ComparisonView.module.css`

The comparison view is a modal-style overlay showing two sessions side-by-side. Props:
- `sessionA` / `sessionB` — ResearchSessionDto objects (no evidence loaded; just list-level fields)
- `onClose` — dismiss handler

- [ ] **Step 1: Create `ComparisonView.module.css`**

```css
/* emcip-admin-ui/src/main/frontend/src/pages/Research/ComparisonView.module.css */

.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  z-index: 200;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: var(--sp-6);
  overflow-y: auto;
}

.dialog {
  background: var(--bg-card);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-md);
  width: 100%;
  max-width: 960px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-4) var(--sp-5);
  border-bottom: 1px solid var(--border);
}

.headerTitle {
  font-family: var(--font-display);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--accent);
}

.closeBtn {
  background: none;
  border: none;
  color: var(--fg-2);
  font-size: 18px;
  cursor: pointer;
  line-height: 1;
  padding: 0 4px;
}

.closeBtn:hover {
  color: var(--fg-1);
}

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
  padding: var(--sp-5);
  gap: var(--sp-5);
}

.card {
  background: var(--bg-sunken);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  padding: var(--sp-4);
}

.cardLabel {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
  letter-spacing: 0.1em;
  text-transform: uppercase;
  margin-bottom: var(--sp-2);
}

.question {
  font-family: var(--font-body);
  font-size: 14px;
  line-height: 1.4;
  color: var(--fg-1);
  margin-bottom: var(--sp-3);
  min-height: 40px;
}

.statsGrid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
}

.stat {
  background: var(--bg-card);
  border: 1px solid var(--rule);
  border-radius: var(--r-xs);
  padding: var(--sp-2) var(--sp-3);
}

.statLabel {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  margin-bottom: 2px;
}

.statValue {
  font-family: var(--font-mono);
  font-size: 14px;
  color: var(--fg-1);
}

.badges {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-bottom: var(--sp-3);
}
```

- [ ] **Step 2: Replace `ComparisonView.jsx` with full implementation**

```jsx
// emcip-admin-ui/src/main/frontend/src/pages/Research/ComparisonView.jsx

import { Badge } from '../../components/Badge/Badge'
import styles from './ComparisonView.module.css'

const STATUS_VARIANT = {
  COMPLETED: 'green',
  RUNNING:   'blue',
  CREATED:   'gray',
  PAUSED:    'yellow',
  FAILED:    'red',
}

const TEMPLATE_LABEL = {
  TOPIC:      'Topic',
  PERSON:     'Person',
  FACT_CHECK: 'Fact Check',
}

function SessionCard({ session, label }) {
  return (
    <div className={styles.card}>
      <div className={styles.cardLabel}>{label}</div>

      <p className={styles.question}>{session.question}</p>

      <div className={styles.badges}>
        <Badge variant={STATUS_VARIANT[session.status] ?? 'gray'}>{session.status}</Badge>
        {session.reportTemplate && (
          <Badge variant="gray">{TEMPLATE_LABEL[session.reportTemplate] ?? session.reportTemplate}</Badge>
        )}
        {session.reportId && <Badge variant="green">Report ✓</Badge>}
      </div>

      <div className={styles.statsGrid}>
        <div className={styles.stat}>
          <div className={styles.statLabel}>Iterations</div>
          <div className={styles.statValue}>
            {session.iterationsUsed} / {session.maxIterations}
          </div>
        </div>
        <div className={styles.stat}>
          <div className={styles.statLabel}>LLM Calls</div>
          <div className={styles.statValue}>
            {session.llmCallsUsed} / {session.maxLlmCalls}
          </div>
        </div>
        <div className={styles.stat}>
          <div className={styles.statLabel}>Cost Used</div>
          <div className={styles.statValue}>
            ${(session.costUsedUsd ?? 0).toFixed(3)}
          </div>
        </div>
        <div className={styles.stat}>
          <div className={styles.statLabel}>Evidence</div>
          <div className={styles.statValue}>
            {session.evidence?.length ?? '—'}
          </div>
        </div>
        <div className={styles.stat} style={{ gridColumn: '1 / -1' }}>
          <div className={styles.statLabel}>Created</div>
          <div className={styles.statValue} style={{ fontSize: 12 }}>
            {session.createdAt ? new Date(session.createdAt).toLocaleString() : '—'}
          </div>
        </div>
      </div>
    </div>
  )
}

export function ComparisonView({ sessionA, sessionB, onClose }) {
  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
        <div className={styles.header}>
          <span className={styles.headerTitle}>Session Comparison</span>
          <button className={styles.closeBtn} onClick={onClose} aria-label="Close">×</button>
        </div>

        <div className={styles.grid}>
          <SessionCard session={sessionA} label="Session A" />
          <SessionCard session={sessionB} label="Session B" />
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 3: Build to verify**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-ui package -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip
git add \
  emcip-admin-ui/src/main/frontend/src/pages/Research/ComparisonView.jsx \
  emcip-admin-ui/src/main/frontend/src/pages/Research/ComparisonView.module.css
git commit -m "feat(27c): add ComparisonView — side-by-side session comparison modal"
```

---

## Task 7: Final build + backlog updates

**Files:**
- No code changes — full clean build verification
- Modify: `docs/superpowers/BACKLOG.md`

- [ ] **Step 1: Full clean package of admin UI**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-ui clean package -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

If it fails, run without `-q` to see errors:
```bash
mvn -pl emcip-admin-ui clean package 2>&1 | grep -E "ERROR|error TS|SyntaxError" | head -20
```

Fix any issues before continuing.

- [ ] **Step 2: Full clean build of both backend modules**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine,emcip-admin-api clean verify -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 121+ tests passing.

- [ ] **Step 3: Update BACKLOG.md**

Read `docs/superpowers/BACKLOG.md`.

In `§2. Open — Feature Work`, remove the `27C` row.

In `§5. Completed`, add:
```
| 27B | Deep Research Agent — web search (US-27.3) + report generation (US-27.5) | ✅ [date of PR] |
| 27C | Deep Research Agent — Admin UI: session list, Start Research modal, live polling, report viewer, comparison view (US-27.6, 27.7, 27.8) | ✅ [date] |
```

Update the header date to today.

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip
git add docs/superpowers/BACKLOG.md
git commit -m "docs(27c): mark 27B + 27C complete in backlog"
```

---

## Self-Review

### Spec Coverage

| Requirement | Task |
|---|---|
| US-27.6: Research UI page with session list | Task 2 (ResearchPage, DataTable, status badges) |
| US-27.6: Start new research session form | Task 3 (StartResearchModal: question, template, web search, advanced) |
| US-27.6: Navigate to session detail | Task 2 (onEdit → navigate to /research/:id) |
| US-27.7: Live progress — poll while RUNNING/CREATED | Task 4 (setInterval at 3s, stop on terminal status) |
| US-27.7: Live indicator in UI | Task 4 (◈ live pulsing indicator in metaRow) |
| US-27.7: Pause / Resume controls | Task 4 (Pause/Resume buttons, handlePause/handleResume) |
| US-27.8: Session history (list page) | Task 2 (session list with filters) |
| US-27.8: Session detail with evidence | Task 4 (Evidence tab with evidence table) |
| US-27.8: Report viewer | Tasks 4 + 5 (Report tab → ReportViewer) |
| US-27.8: Markdown download | Task 5 (handleDownload → Blob URL) |
| US-27.8: Comparison view (2 sessions side-by-side) | Tasks 2 + 6 (checkbox selection, ComparisonView modal) |

### Gaps

1. **US-27.8 "comparison view"** — the ComparisonView shows list-level session fields (stats, badges) but does NOT show evidence side-by-side, because the list endpoint does not return evidence arrays. The detail view shows full evidence; to compare evidence you'd need to load both sessions in full. This is a deliberate scope decision — loading two full sessions in the comparison modal is feasible but adds complexity and latency. The current view covers the key comparison use case (cost, iterations, template, status, report status). Full evidence comparison can be added as a follow-up.

2. **SSE (Server-Sent Events)** — US-27.7 says "live progress stream". No SSE endpoint exists in the backend (sessions are synchronous). Polling at 3s intervals is functionally equivalent for the use case and avoids new backend work. The `◈ live` indicator communicates live status to the user.

3. **Confirmation before starting (long operation warning)** — StartResearchModal shows "this may take up to 30 seconds" during submission. There is no pre-submit confirmation dialog. This is adequate for an operator-facing tool.

### Placeholder Scan

No TBD, TODO, or incomplete steps. All component code is complete. All CSS properties reference actual design tokens from variables.css.

### Type Consistency

- `researchApi(request)` — defined Task 1, used in Tasks 2, 3, 4 ✅
- `ResearchPage` → `StartResearchModal(onClose, onStarted)` — defined Task 3, wired in Task 2 ✅
- `ComparisonView(sessionA, sessionB, onClose)` — defined Task 6, wired in Task 2 ✅
- `ReportViewer(report)` — defined Task 5, used in Task 4 ✅
- `SessionDetailPage` — defined Task 4, route registered Task 2 ✅
- Route paths: `/research` (ResearchPage), `/research/:id` (SessionDetailPage) — consistent across App.jsx and navigate() calls ✅
