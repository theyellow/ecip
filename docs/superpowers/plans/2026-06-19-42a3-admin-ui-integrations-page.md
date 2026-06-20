# Epic 42 — Knowledge Enrichment: Admin UI Integrations Page

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `⊕ INTEGRATIONS` page to the admin UI. ADMIN sees three tabs: Global Keys, Sources & Schedule, Run History. TENANT_ADMIN sees one tab: My API Keys. Sidebar entry is visible to both roles via `INTEGRATIONS_TENANT_MANAGE`.

**Architecture:** New page component under `src/pages/IntegrationsPage/`. New API client at `src/api/integrations.js`. Sidebar entry and React Router route added. Follows the existing CSS Modules + DataTable + Badge component patterns.

**Prerequisites:** Plan A.2 complete — REST endpoints deployed and accessible.

**Design reference:** `docs/superpowers/specs/2026-06-19-42-knowledge-enrichment-connectors-design.md` (Admin UI section), `.superpowers/brainstorm/975323-1781874539/content/admin-ui-themed.html`, `.superpowers/brainstorm/975323-1781874539/content/admin-ui-perms.html`

**Read before writing any code:**
- `emcip-admin-ui/src/main/frontend/src/theme/variables.css` — design tokens
- `emcip-admin-ui/src/main/frontend/src/auth/permissions.js` — current constants
- `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx` — NAV array
- `emcip-admin-ui/src/main/frontend/src/App.jsx` — route registrations
- An existing page (e.g. `src/pages/KnowledgePage/KnowledgePage.jsx`) — component pattern

---

## File Map

**Modify**
- `emcip-admin-ui/src/main/frontend/src/auth/permissions.js`
- `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`
- `emcip-admin-ui/src/main/frontend/src/App.jsx`

**New**
- `emcip-admin-ui/src/main/frontend/src/api/integrations.js`
- `emcip-admin-ui/src/main/frontend/src/pages/IntegrationsPage/IntegrationsPage.jsx`
- `emcip-admin-ui/src/main/frontend/src/pages/IntegrationsPage/IntegrationsPage.module.css`

---

## Task 1: Permissions and routing wiring

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/auth/permissions.js`
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/App.jsx`

- [ ] **Step 1: Add permission constants to permissions.js**

Read the current `permissions.js` file. Add `INTEGRATIONS_GLOBAL_MANAGE` and `INTEGRATIONS_TENANT_MANAGE` to both role arrays:

```js
export const ROLE_PERMISSIONS = {
  ADMIN: [
    'GROUPS_READ', 'GROUPS_WRITE',
    'POLICY_RULES_READ', 'POLICY_RULES_WRITE',
    'MODERATION_RULES_READ', 'MODERATION_RULES_WRITE',
    'AUDIT_READ',
    'TELEGRAM_READ', 'TELEGRAM_WRITE',
    'SIMULATE_WRITE',
    'AI_CONFIG_READ', 'AI_CONFIG_WRITE',
    'COSTS_READ',
    'TENANTS_READ', 'TENANTS_WRITE',
    'USERS_READ', 'USERS_WRITE',
    'RESOLUTION_REVIEW_READ', 'RESOLUTION_REVIEW_WRITE',
    'KNOWLEDGE_READ', 'KNOWLEDGE_WRITE',
    'INTEGRATIONS_GLOBAL_MANAGE',
    'INTEGRATIONS_TENANT_MANAGE',
  ],
  TENANT_ADMIN: [
    'GROUPS_READ', 'GROUPS_WRITE',
    'POLICY_RULES_READ', 'POLICY_RULES_WRITE',
    'MODERATION_RULES_READ', 'MODERATION_RULES_WRITE',
    'AUDIT_READ',
    'TELEGRAM_READ', 'TELEGRAM_WRITE',
    'SIMULATE_WRITE',
    'COSTS_READ',
    'RESOLUTION_REVIEW_READ', 'RESOLUTION_REVIEW_WRITE',
    'KNOWLEDGE_READ', 'KNOWLEDGE_WRITE',
    'INTEGRATIONS_TENANT_MANAGE',
  ],
}

export function hasPermission(role, permission) {
  return ROLE_PERMISSIONS[role]?.includes(permission) ?? false
}
```

- [ ] **Step 2: Add sidebar entry to Sidebar.jsx**

Read `Sidebar.jsx`. Add the integrations entry at the end of the NAV array, before `Users`:

```js
  { to: '/integrations', label: 'Integrations', icon: '⊕', permission: 'INTEGRATIONS_TENANT_MANAGE' },
```

The full NAV array should look like (order: existing entries, then Integrations before Users):

```js
const NAV = [
  { to: '/tenants',          label: 'Tenants',          icon: '⬡', permission: 'TENANTS_READ' },
  { to: '/policy-rules',     label: 'Policy Rules',     icon: '⚖', permission: 'POLICY_RULES_READ' },
  { to: '/moderation-rules', label: 'Moderation Rules', icon: '⊘', permission: 'MODERATION_RULES_READ' },
  { to: '/decisions',        label: 'Decisions',        icon: '⚑',       permission: 'AUDIT_READ' },
  { to: '/resolution-queue', label: 'Resolution Queue', icon: '\u2297', permission: 'RESOLUTION_REVIEW_READ' },
  { to: '/groups',           label: 'Groups',           icon: '◈',       permission: 'GROUPS_READ' },
  { to: '/knowledge',        label: 'Knowledge',        icon: '◆',       permission: 'KNOWLEDGE_READ' },
  { to: '/audit-log',        label: 'Audit Log',        icon: '◎', permission: 'AUDIT_READ' },
  { to: '/simulate',         label: 'Simulate Event',   icon: '▶', permission: 'SIMULATE_WRITE' },
  { to: '/telegram',         label: 'Telegram',         icon: '⌘', permission: 'TELEGRAM_READ' },
  { to: '/ai-config',        label: 'AI Config',        icon: '✦', permission: 'AI_CONFIG_READ' },
  { to: '/costs',            label: 'LLM Costs',         icon: '\u229B', permission: 'COSTS_READ' },
  { to: '/integrations',     label: 'Integrations',     icon: '⊕', permission: 'INTEGRATIONS_TENANT_MANAGE' },
  { to: '/users',            label: 'Users',            icon: '◉', permission: 'USERS_READ' },
]
```

- [ ] **Step 3: Add route to App.jsx**

Read `App.jsx`. Find the existing routes block and add the integrations route alongside the other page routes. The exact JSX depends on how other routes are registered — follow the same pattern. It should look like:

```jsx
<Route path="integrations" element={<IntegrationsPage />} />
```

Also add the import at the top of App.jsx alongside the other page imports:

```jsx
import { IntegrationsPage } from './pages/IntegrationsPage/IntegrationsPage'
```

- [ ] **Step 4: Verify sidebar renders without error**

Start the dev server:

```bash
cd emcip-admin-ui/src/main/frontend
npm run dev | cat &
```

Open the browser, log in, verify the sidebar shows `⊕ INTEGRATIONS` for both ADMIN and TENANT_ADMIN roles. The route `/integrations` should not 404 (it may render an empty page if `IntegrationsPage` returns `null` at this stage — that's fine).

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/auth/permissions.js \
        emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx \
        emcip-admin-ui/src/main/frontend/src/App.jsx
git commit -m "feat(42): add Integrations nav entry and route"
```

---

## Task 2: API client

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/api/integrations.js`

- [ ] **Step 1: Read knowledge.js**

Read `emcip-admin-ui/src/main/frontend/src/api/knowledge.js` to confirm the factory function pattern and base URL conventions.

- [ ] **Step 2: Create integrations.js**

```js
/**
 * integrations.js — API client for Epic 42 enrichment integrations.
 *
 * Follows the same factory-function pattern as knowledge.js.
 * All key responses return { maskedKey } — the raw value is never sent by the backend.
 */

export function integrationsApi(request) {
  return {
    // --- Global key management (INTEGRATIONS_GLOBAL_MANAGE) ---

    /** GET /api/v1/admin/integrations/keys — list all global keys */
    listGlobalKeys: () =>
      request('/api/v1/admin/integrations/keys'),

    /** GET /api/v1/admin/integrations/keys?tenantId=... — list keys for a specific tenant */
    listKeysByTenant: (tenantId) =>
      request(`/api/v1/admin/integrations/keys?tenantId=${encodeURIComponent(tenantId)}`),

    /** POST /api/v1/admin/integrations/keys */
    createKey: (vendorId, apiKey, enabled = true) =>
      request('/api/v1/admin/integrations/keys', {
        method: 'POST',
        body: JSON.stringify({ vendorId, apiKey, enabled }),
      }),

    /** PUT /api/v1/admin/integrations/keys/{id} */
    updateKey: (id, vendorId, apiKey, enabled) =>
      request(`/api/v1/admin/integrations/keys/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify({ vendorId, apiKey, enabled }),
      }),

    /** DELETE /api/v1/admin/integrations/keys/{id} */
    deleteKey: (id) =>
      request(`/api/v1/admin/integrations/keys/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }),

    // --- Tenant key management (INTEGRATIONS_TENANT_MANAGE) ---

    /** GET /api/v1/tenant/integrations/keys — list own tenant keys */
    listOwnKeys: () =>
      request('/api/v1/tenant/integrations/keys'),

    /** PUT /api/v1/tenant/integrations/keys/{vendorId} — upsert own key */
    upsertOwnKey: (vendorId, apiKey, enabled = true) =>
      request(`/api/v1/tenant/integrations/keys/${encodeURIComponent(vendorId)}`, {
        method: 'PUT',
        body: JSON.stringify({ vendorId, apiKey, enabled }),
      }),

    /** DELETE /api/v1/tenant/integrations/keys/{vendorId} */
    deleteOwnKey: (vendorId) =>
      request(`/api/v1/tenant/integrations/keys/${encodeURIComponent(vendorId)}`, {
        method: 'DELETE',
      }),

    // --- Sources & schedule (INTEGRATIONS_GLOBAL_MANAGE) ---

    /** GET /api/v1/admin/integrations/sources */
    listSources: () =>
      request('/api/v1/admin/integrations/sources'),

    /**
     * POST /api/v1/admin/integrations/sources/{id}/trigger
     * Returns { runId: "uuid" }
     */
    triggerSource: (sourceId) =>
      request(`/api/v1/admin/integrations/sources/${encodeURIComponent(sourceId)}/trigger`, {
        method: 'POST',
      }),

    /** GET /api/v1/admin/integrations/sources/{id}/runs */
    listRuns: (sourceId, page = 0, size = 20) =>
      request(
        `/api/v1/admin/integrations/sources/${encodeURIComponent(sourceId)}/runs?page=${page}&size=${size}`
      ),

    /** GET /api/v1/admin/integrations/sources/{id}/runs/{runId} */
    getRun: (sourceId, runId) =>
      request(
        `/api/v1/admin/integrations/sources/${encodeURIComponent(sourceId)}/runs/${encodeURIComponent(runId)}`
      ),
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/api/integrations.js
git commit -m "feat(42): add integrations API client"
```

---

## Task 3: IntegrationsPage — component and CSS

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/pages/IntegrationsPage/IntegrationsPage.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/IntegrationsPage/IntegrationsPage.module.css`

**Before writing:** Read `variables.css` again to have accurate token names. Read one existing page (e.g. `KnowledgePage.jsx` or `TenantsPage.jsx`) to match component pattern, CSS Modules usage, and how `useAuth` / `useAuthRequest` are consumed.

The full VENDOR_LIST of 13 connectors:

```js
const VENDORS = [
  { id: 'wikipedia',        name: 'Wikipedia',         requiresKey: false },
  { id: 'arxiv',            name: 'arXiv',             requiresKey: false },
  { id: 'pubmed',           name: 'PubMed',            requiresKey: false }, // optional
  { id: 'wikidata',         name: 'Wikidata',          requiresKey: false },
  { id: 'openalex',         name: 'OpenAlex',          requiresKey: false },
  { id: 'semantic-scholar', name: 'Semantic Scholar',  requiresKey: false }, // optional
  { id: 'biorxiv',          name: 'bioRxiv / medRxiv', requiresKey: false },
  { id: 'core',             name: 'CORE',              requiresKey: true  },
  { id: 'zenodo',           name: 'Zenodo',            requiresKey: false },
  { id: 'unpaywall',        name: 'Unpaywall',         requiresKey: false },
  { id: 'doaj',             name: 'DOAJ',              requiresKey: false },
  { id: 'exa',              name: 'Exa Search',        requiresKey: true  },
  { id: 'brave',            name: 'Brave Search',      requiresKey: true  },
]
```

- [ ] **Step 1: Create IntegrationsPage.module.css**

```css
/* IntegrationsPage.module.css */

.page { padding: var(--sp-5); }

.pageHeader {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--sp-5);
}

.pageHeader h2 {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--accent);
  margin: 0 0 4px;
}

.systemId {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* Tab strip */
.tabs {
  display: flex;
  border-bottom: 1px solid var(--border);
  margin-bottom: var(--sp-4);
}

.tab {
  font-family: var(--font-display);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  padding: 9px 18px;
  color: var(--fg-2);
  cursor: pointer;
  border: none;
  background: transparent;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}

.tab:hover { color: var(--accent-hover); }

.tabActive {
  color: var(--accent);
  border-bottom-color: var(--accent);
  background: var(--accent-soft);
}

/* Table */
.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.tbl th {
  font-family: var(--font-display);
  font-size: 9px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--accent);
  padding: 8px 12px;
  text-align: left;
  border-bottom: 1px solid var(--border-strong);
  background: var(--bg-sunken);
}

.tbl td {
  padding: 8px 12px;
  border-bottom: 1px solid var(--rule);
  color: var(--fg-2);
}

.tbl tr:hover td { background: rgba(212, 168, 73, 0.04); }

.mono { font-family: var(--font-mono); font-size: 11px; }
.muted { color: var(--fg-3); font-style: italic; }
.name { color: var(--fg-1); font-family: var(--font-mono); }

/* Cards (Sources & Schedule tab) */
.sourceGrid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--sp-3);
}

.sourceCard {
  background: var(--bg-card);
  border: 1px solid var(--border);
  padding: var(--sp-3);
}

.sourceCard h3 {
  font-family: var(--font-display);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fg-1);
  margin: 0 0 var(--sp-2);
}

.sourceDetail {
  font-size: 11px;
  color: var(--fg-2);
  margin-bottom: 4px;
}

.sourceDetail span { font-family: var(--font-mono); font-size: 11px; }

.cardActions {
  margin-top: var(--sp-2);
  display: flex;
  gap: var(--sp-2);
}

/* Inline badge */
.badge {
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  padding: 2px 7px;
  border-radius: var(--r-pill);
}

.badgeOk   { background: var(--signal-ok-bg);   color: var(--signal-ok-fg); }
.badgeWarn { background: var(--signal-warn-bg);  color: var(--signal-warn-fg); }
.badgeStop { background: var(--signal-stop-bg);  color: var(--signal-stop-fg); }
.badgeMute { background: var(--signal-mute-bg);  color: var(--signal-mute-fg); }
.badgeInfo { background: var(--signal-info-bg);  color: var(--signal-info-fg); }

/* Buttons — matches EMCIP v2 token system */
.btnPrimary {
  font-family: var(--font-display);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  padding: 6px 12px;
  cursor: pointer;
  border: 1px solid var(--border-strong);
  background: linear-gradient(180deg, var(--accent-hover) 0%, var(--accent) 100%);
  color: var(--fg-on-accent);
  border-radius: 0;
}

.btnPrimary:hover { background: var(--accent-hover); }

.btnSecondary {
  font-family: var(--font-display);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  padding: 6px 12px;
  cursor: pointer;
  border: 1px solid var(--border);
  background: transparent;
  color: var(--fg-1);
  border-radius: 0;
}

.btnSecondary:hover { background: var(--accent-soft); border-color: var(--accent); }

.btnDanger {
  font-family: var(--font-display);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  padding: 6px 12px;
  cursor: pointer;
  border: 1px solid rgba(248, 113, 113, 0.35);
  background: transparent;
  color: var(--signal-stop-fg);
  border-radius: 0;
}

/* Modal overlay */
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
}

.modal {
  background: var(--bg-card);
  border: 1px solid var(--border);
  backdrop-filter: blur(16px);
  width: 480px;
  max-width: 95vw;
}

.modalHead {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-sunken);
}

.modalTitle {
  font-family: var(--font-display);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--accent);
}

.modalBody { padding: 18px 16px; }

.modalFoot {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-2);
  padding: 12px 16px;
  border-top: 1px solid var(--border);
}

/* Form */
.field { margin-bottom: var(--sp-3); }

.label {
  display: block;
  font-family: var(--font-display);
  font-size: 9px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fg-2);
  margin-bottom: 4px;
}

.input {
  width: 100%;
  background: var(--bg-input);
  border: 1px solid var(--border);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 12px;
  padding: 7px 10px;
  border-radius: 0;
  outline: none;
}

.input:focus { border-color: var(--accent); }

/* Empty state */
.empty {
  text-align: center;
  padding: var(--sp-6);
  color: var(--fg-3);
  font-style: italic;
  font-size: 12px;
}

/* Run history table columns */
.tblStatus { width: 90px; }
.tblNumbers { width: 80px; text-align: right; }
```

- [ ] **Step 2: Create IntegrationsPage.jsx**

```jsx
import { useState, useEffect, useCallback } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { hasPermission } from '../../auth/permissions'
import { integrationsApi } from '../../api/integrations'
import styles from './IntegrationsPage.module.css'

const VENDORS = [
  { id: 'wikipedia',        name: 'Wikipedia',         requiresKey: false },
  { id: 'arxiv',            name: 'arXiv',             requiresKey: false },
  { id: 'pubmed',           name: 'PubMed',            requiresKey: false },
  { id: 'wikidata',         name: 'Wikidata',          requiresKey: false },
  { id: 'openalex',         name: 'OpenAlex',          requiresKey: false },
  { id: 'semantic-scholar', name: 'Semantic Scholar',  requiresKey: false },
  { id: 'biorxiv',          name: 'bioRxiv / medRxiv', requiresKey: false },
  { id: 'core',             name: 'CORE',              requiresKey: true  },
  { id: 'zenodo',           name: 'Zenodo',            requiresKey: false },
  { id: 'unpaywall',        name: 'Unpaywall',         requiresKey: false },
  { id: 'doaj',             name: 'DOAJ',              requiresKey: false },
  { id: 'exa',              name: 'Exa Search',        requiresKey: true  },
  { id: 'brave',            name: 'Brave Search',      requiresKey: true  },
]

function statusBadgeClass(status, styles) {
  if (!status) return styles.badgeMute
  if (status === 'SUCCESS') return styles.badgeOk
  if (status === 'PARTIAL') return styles.badgeWarn
  if (status === 'FAILURE') return styles.badgeStop
  if (status === 'RUNNING') return styles.badgeInfo
  return styles.badgeMute
}

// ─── Key Edit Modal ───────────────────────────────────────────────────────────

function KeyModal({ vendorName, onSave, onClose }) {
  const [keyValue, setKeyValue] = useState('')
  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={e => e.stopPropagation()}>
        <div className={styles.modalHead}>
          <span className={styles.modalTitle}>— Set API Key —</span>
          <button className={styles.btnSecondary} onClick={onClose}>✕</button>
        </div>
        <div className={styles.modalBody}>
          <div className={styles.field}>
            <label className={styles.label}>{vendorName} API Key</label>
            <input
              className={styles.input}
              type="password"
              autoComplete="off"
              placeholder="Paste your API key"
              value={keyValue}
              onChange={e => setKeyValue(e.target.value)}
            />
          </div>
        </div>
        <div className={styles.modalFoot}>
          <button className={styles.btnSecondary} onClick={onClose}>Cancel</button>
          <button
            className={styles.btnPrimary}
            disabled={!keyValue.trim()}
            onClick={() => onSave(keyValue.trim())}
          >
            Save Key
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Tab: Global Keys (ADMIN only) ───────────────────────────────────────────

function GlobalKeysTab({ api }) {
  const [keys, setKeys] = useState([])
  const [modal, setModal] = useState(null) // { vendorId, vendorName, existingId? }

  const reload = useCallback(() => {
    api.listGlobalKeys().then(setKeys).catch(console.error)
  }, [api])

  useEffect(() => { reload() }, [reload])

  const keyByVendor = Object.fromEntries(keys.map(k => [k.vendorId, k]))

  const handleSave = async (keyValue) => {
    const existing = keyByVendor[modal.vendorId]
    if (existing) {
      await api.updateKey(existing.id, modal.vendorId, keyValue, true)
    } else {
      await api.createKey(modal.vendorId, keyValue)
    }
    setModal(null)
    reload()
  }

  const handleToggle = async (key) => {
    await api.updateKey(key.id, key.vendorId, key.maskedKey, !key.enabled)
    reload()
  }

  const handleDelete = async (key) => {
    if (!window.confirm(`Remove global key for ${key.vendorId}?`)) return
    await api.deleteKey(key.id)
    reload()
  }

  return (
    <>
      <table className={styles.tbl}>
        <thead>
          <tr>
            <th>Vendor</th>
            <th>Key</th>
            <th>Status</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {VENDORS.map(v => {
            const key = keyByVendor[v.id]
            return (
              <tr key={v.id}>
                <td className={styles.name}>{v.name}</td>
                <td className={styles.mono}>
                  {!v.requiresKey
                    ? <span className={styles.muted}>No key required</span>
                    : key
                      ? key.maskedKey
                      : <span className={styles.muted}>Not set</span>
                  }
                </td>
                <td>
                  {key && (
                    <span className={`${styles.badge} ${key.enabled ? styles.badgeOk : styles.badgeMute}`}>
                      {key.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  )}
                </td>
                <td>
                  <div style={{ display: 'flex', gap: 6 }}>
                    {v.requiresKey && (
                      <button
                        className={styles.btnSecondary}
                        onClick={() => setModal({ vendorId: v.id, vendorName: v.name })}
                      >
                        {key ? 'Edit' : 'Set Key'}
                      </button>
                    )}
                    {key && (
                      <>
                        <button className={styles.btnSecondary} onClick={() => handleToggle(key)}>
                          {key.enabled ? 'Disable' : 'Enable'}
                        </button>
                        <button className={styles.btnDanger} onClick={() => handleDelete(key)}>
                          Remove
                        </button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {modal && (
        <KeyModal
          vendorName={modal.vendorName}
          onSave={handleSave}
          onClose={() => setModal(null)}
        />
      )}
    </>
  )
}

// ─── Tab: Sources & Schedule (ADMIN only) ────────────────────────────────────

function SourcesTab({ api }) {
  const [sources, setSources] = useState([])
  const [triggering, setTriggering] = useState({})

  useEffect(() => {
    api.listSources().then(setSources).catch(console.error)
  }, [api])

  const handleTrigger = async (sourceId) => {
    setTriggering(prev => ({ ...prev, [sourceId]: true }))
    try {
      const { runId } = await api.triggerSource(sourceId)
      alert(`Run started: ${runId}`)
    } catch (e) {
      alert('Trigger failed: ' + e.message)
    } finally {
      setTriggering(prev => ({ ...prev, [sourceId]: false }))
      api.listSources().then(setSources).catch(console.error)
    }
  }

  const vendorName = (vendorId) =>
    VENDORS.find(v => v.id === vendorId)?.name ?? vendorId

  return (
    <div className={styles.sourceGrid}>
      {sources.map(src => (
        <div key={src.id} className={styles.sourceCard}>
          <h3>{vendorName(src.vendorId)}</h3>
          <div className={styles.sourceDetail}>
            Schedule: <span>{src.scheduleCron ?? '—'}</span>
          </div>
          <div className={styles.sourceDetail}>
            Last run:{' '}
            <span>
              {src.lastRunAt
                ? new Date(src.lastRunAt).toLocaleString()
                : 'Never'}
            </span>
          </div>
          {src.lastRunStatus && (
            <div className={styles.sourceDetail}>
              Status:{' '}
              <span className={`${styles.badge} ${statusBadgeClass(src.lastRunStatus, styles)}`}>
                {src.lastRunStatus}
              </span>
            </div>
          )}
          <div className={styles.cardActions}>
            <button
              className={styles.btnPrimary}
              disabled={!!triggering[src.id]}
              onClick={() => handleTrigger(src.id)}
            >
              {triggering[src.id] ? 'Starting...' : 'Run Now'}
            </button>
          </div>
        </div>
      ))}
      {sources.length === 0 && (
        <div className={styles.empty}>No enrichment sources configured.</div>
      )}
    </div>
  )
}

// ─── Tab: Run History (ADMIN only) ───────────────────────────────────────────

function RunHistoryTab({ api, sources }) {
  const [runs, setRuns] = useState([])

  useEffect(() => {
    if (sources.length === 0) return
    Promise.all(sources.map(s => api.listRuns(s.id, 0, 5)))
      .then(results => {
        const all = results.flat().sort((a, b) =>
          new Date(b.startedAt) - new Date(a.startedAt)
        )
        setRuns(all)
      })
      .catch(console.error)
  }, [api, sources])

  const vendorName = (sourceId) => {
    const src = sources.find(s => s.id === sourceId)
    return VENDORS.find(v => v.id === src?.vendorId)?.name ?? src?.vendorId ?? sourceId
  }

  const duration = (run) => {
    if (!run.completedAt) return 'Running...'
    const ms = new Date(run.completedAt) - new Date(run.startedAt)
    return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
  }

  return (
    <table className={styles.tbl}>
      <thead>
        <tr>
          <th>Vendor</th>
          <th>Trigger</th>
          <th>Started</th>
          <th>Duration</th>
          <th className={styles.tblStatus}>Status</th>
          <th className={styles.tblNumbers}>Fetched</th>
          <th className={styles.tblNumbers}>Ingested</th>
        </tr>
      </thead>
      <tbody>
        {runs.map(run => (
          <tr key={run.id}>
            <td className={styles.name}>{vendorName(run.sourceId)}</td>
            <td className={styles.mono}>{run.triggerType}</td>
            <td className={styles.mono}>{new Date(run.startedAt).toLocaleString()}</td>
            <td className={styles.mono}>{duration(run)}</td>
            <td>
              <span className={`${styles.badge} ${statusBadgeClass(run.status, styles)}`}>
                {run.status}
              </span>
            </td>
            <td className={`${styles.mono} ${styles.tblNumbers}`}>{run.itemsFetched}</td>
            <td className={`${styles.mono} ${styles.tblNumbers}`}>{run.itemsIngested}</td>
          </tr>
        ))}
        {runs.length === 0 && (
          <tr>
            <td colSpan={7} className={styles.empty}>No runs yet.</td>
          </tr>
        )}
      </tbody>
    </table>
  )
}

// ─── Tab: My API Keys (TENANT_ADMIN) ─────────────────────────────────────────

function MyKeysTab({ api }) {
  const [ownKeys, setOwnKeys] = useState([])
  const [modal, setModal] = useState(null)

  const reload = useCallback(() => {
    api.listOwnKeys().then(setOwnKeys).catch(console.error)
  }, [api])

  useEffect(() => { reload() }, [reload])

  const keyByVendor = Object.fromEntries(ownKeys.map(k => [k.vendorId, k]))

  const handleSave = async (keyValue) => {
    await api.upsertOwnKey(modal.vendorId, keyValue)
    setModal(null)
    reload()
  }

  const handleDelete = async (vendorId) => {
    if (!window.confirm(`Remove your key for ${vendorId}? The system default will be used instead.`)) return
    await api.deleteOwnKey(vendorId)
    reload()
  }

  return (
    <>
      <p style={{ color: 'var(--fg-3)', fontSize: 11, fontStyle: 'italic', marginBottom: 16 }}>
        Override global keys with your own. Leave empty to use the system default.
      </p>
      <table className={styles.tbl}>
        <thead>
          <tr>
            <th>Vendor</th>
            <th>Your Key</th>
            <th>Fallback</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {VENDORS.map(v => {
            const key = keyByVendor[v.id]
            if (!v.requiresKey) {
              return (
                <tr key={v.id}>
                  <td className={`${styles.name} ${styles.muted}`}>{v.name}</td>
                  <td colSpan={3} className={styles.muted}>No key needed — always available</td>
                </tr>
              )
            }
            return (
              <tr key={v.id}>
                <td className={styles.name}>{v.name}</td>
                <td className={styles.mono}>
                  {key ? key.maskedKey : <span className={styles.muted}>Not set</span>}
                </td>
                <td>
                  <span className={`${styles.badge} ${key ? styles.badgeOk : styles.badgeMute}`}>
                    {key ? 'Own key' : 'Using global'}
                  </span>
                </td>
                <td>
                  <div style={{ display: 'flex', gap: 6 }}>
                    <button
                      className={styles.btnPrimary}
                      onClick={() => setModal({ vendorId: v.id, vendorName: v.name })}
                    >
                      {key ? 'Edit' : 'Set Key'}
                    </button>
                    {key && (
                      <button className={styles.btnDanger} onClick={() => handleDelete(v.id)}>
                        Remove
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {modal && (
        <KeyModal
          vendorName={modal.vendorName}
          onSave={handleSave}
          onClose={() => setModal(null)}
        />
      )}
    </>
  )
}

// ─── Main page ────────────────────────────────────────────────────────────────

export function IntegrationsPage() {
  const { role } = useAuth()
  const request = useAuthRequest()
  const api = integrationsApi(request)
  const isAdmin = hasPermission(role, 'INTEGRATIONS_GLOBAL_MANAGE')

  const adminTabs = ['Global Keys', 'Sources & Schedule', 'Run History']
  const [activeTab, setActiveTab] = useState(isAdmin ? 'Global Keys' : 'My API Keys')
  const [sources, setSources] = useState([])

  useEffect(() => {
    if (isAdmin) {
      api.listSources().then(setSources).catch(console.error)
    }
  }, [isAdmin])

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <h2>Integrations</h2>
          <div className={styles.systemId}>⊕ knowledge-engine · enrichment connectors</div>
        </div>
      </div>

      <div className={styles.tabs}>
        {isAdmin
          ? adminTabs.map(tab => (
              <button
                key={tab}
                className={`${styles.tab} ${activeTab === tab ? styles.tabActive : ''}`}
                onClick={() => setActiveTab(tab)}
              >
                {tab}
              </button>
            ))
          : (
            <button className={`${styles.tab} ${styles.tabActive}`}>
              My API Keys
            </button>
          )
        }
      </div>

      {isAdmin && activeTab === 'Global Keys' && <GlobalKeysTab api={api} />}
      {isAdmin && activeTab === 'Sources & Schedule' && <SourcesTab api={api} />}
      {isAdmin && activeTab === 'Run History' && <RunHistoryTab api={api} sources={sources} />}
      {!isAdmin && <MyKeysTab api={api} />}
    </div>
  )
}
```

- [ ] **Step 3: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/IntegrationsPage/ \
        emcip-admin-ui/src/main/frontend/src/api/integrations.js
git commit -m "feat(42): add IntegrationsPage with Global Keys, Sources, Run History, and My Keys tabs"
```

---

## Task 4: Smoke-test in browser

- [ ] **Step 1: Start services**

Ensure `knowledge-engine` and `admin-api` are running locally.

- [ ] **Step 2: Log in as ADMIN**

Navigate to `/integrations`. Verify:
- Three tabs render: Global Keys, Sources & Schedule, Run History
- Global Keys tab shows all 13 vendors; free vendors show "No key required"
- Sources & Schedule tab shows 13 cards, each with a cron expression and "Run Now" button
- Click "Run Now" on Wikipedia — verify alert shows a runId

- [ ] **Step 3: Log in as TENANT_ADMIN**

Navigate to `/integrations`. Verify:
- Only one tab: My API Keys
- Free vendors (Wikipedia, arXiv, etc.) show "No key needed — always available"
- Paid vendors (Core, Exa, Brave) show "Not set" + "Set Key" button
- No schedule or run history visible

- [ ] **Step 4: Final commit**

If any tweaks were needed during smoke testing:

```bash
git add emcip-admin-ui/src/main/frontend/src/
git commit -m "fix(42): smoke-test corrections to IntegrationsPage"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Covered in task |
|---|---|
| Sidebar entry `⊕ INTEGRATIONS` with `INTEGRATIONS_TENANT_MANAGE` guard | Task 1 Step 2 |
| ADMIN: 3 tabs — Global Keys, Sources & Schedule, Run History | Task 3 — `IntegrationsPage.jsx` |
| TENANT_ADMIN: 1 tab — My API Keys | Task 3 — `MyKeysTab` |
| Global Keys: paid vendors show masked key + Edit/Disable | Task 3 — `GlobalKeysTab` |
| Global Keys: free vendors show "No key required" in italic | Task 3 — `GlobalKeysTab` |
| Sources & Schedule: card grid with cron, last-run timestamp, status badge, Run Now | Task 3 — `SourcesTab` |
| Run History: vendor, trigger type, started, duration, status, fetched, ingested | Task 3 — `RunHistoryTab` |
| My API Keys: own key masked, fallback badge (Own key / Using global), Set Key | Task 3 — `MyKeysTab` |
| No emoji anywhere | Confirmed — all labels use text or Unicode glyphs |
| No rounded corners on data surfaces | `border-radius: 0` on all table/card/button elements |
| Display font Cinzel for headings and tab buttons | `font-family: var(--font-display)` on all headers and tabs |
| Semantic tokens only — no inline hex values | All CSS uses `var(--...)` tokens |
| `INTEGRATIONS_GLOBAL_MANAGE` and `INTEGRATIONS_TENANT_MANAGE` in permissions.js | Task 1 Step 1 |

**No placeholders found.**

**Type consistency:** `api.listGlobalKeys()` returns an array of `{ id, vendorId, tenantId, maskedKey, enabled, updatedAt }` which is exactly what `VendorApiKeyResponse` (Plan A.2) emits. `api.listSources()` returns `{ id, vendorId, scheduleCron, lastRunAt, lastRunStatus, ... }` matching `EnrichmentSourceResponse`.
