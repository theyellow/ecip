import { useState, useEffect, useCallback, useMemo } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { hasPermission } from '../../auth/permissions'
import { integrationsApi } from '../../api/integrations'
import styles from './IntegrationsPage.module.css'

const VENDORS = [
  { id: 'wikipedia', name: 'Wikipedia', requiresKey: false },
  { id: 'arxiv', name: 'arXiv', requiresKey: false },
  { id: 'pubmed', name: 'PubMed', requiresKey: false },
  { id: 'wikidata', name: 'Wikidata', requiresKey: false },
  { id: 'openalex', name: 'OpenAlex', requiresKey: false },
  { id: 'semantic-scholar', name: 'Semantic Scholar', requiresKey: false },
  { id: 'biorxiv', name: 'bioRxiv / medRxiv', requiresKey: false },
  { id: 'core', name: 'CORE', requiresKey: true },
  { id: 'zenodo', name: 'Zenodo', requiresKey: false },
  { id: 'unpaywall', name: 'Unpaywall', requiresKey: false },
  { id: 'doaj', name: 'DOAJ', requiresKey: false },
  { id: 'exa', name: 'Exa Search', requiresKey: true },
  { id: 'brave', name: 'Brave Search', requiresKey: true },
]

function statusBadgeClass(status, s) {
  if (!status) return s.badgeMute
  if (status === 'SUCCESS') return s.badgeOk
  if (status === 'PARTIAL') return s.badgeWarn
  if (status === 'FAILURE') return s.badgeStop
  if (status === 'RUNNING') return s.badgeInfo
  return s.badgeMute
}

// --- Key Edit Modal ---

function KeyModal({ vendorName, onSave, onClose }) {
  const [keyValue, setKeyValue] = useState('')
  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        <div className={styles.modalHead}>
          <span className={styles.modalTitle}>Set API Key</span>
          <button className={styles.btnSecondary} onClick={onClose}>
            ✕
          </button>
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
              onChange={(e) => setKeyValue(e.target.value)}
            />
          </div>
        </div>
        <div className={styles.modalFoot}>
          <button className={styles.btnSecondary} onClick={onClose}>
            Cancel
          </button>
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

// --- Tab: Global Keys (ADMIN only) ---

function GlobalKeysTab({ api }) {
  const [keys, setKeys] = useState([])
  const [modal, setModal] = useState(null)

  const reload = useCallback(() => {
    api.listGlobalKeys().then(setKeys).catch(console.error)
  }, [api])

  useEffect(() => {
    reload()
  }, [reload])

  const keyByVendor = Object.fromEntries(keys.map((k) => [k.vendorId, k]))

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
          {VENDORS.map((v) => {
            const key = keyByVendor[v.id]
            return (
              <tr key={v.id}>
                <td className={styles.name}>{v.name}</td>
                <td className={styles.mono}>
                  {!v.requiresKey ? (
                    <span className={styles.muted}>No key required</span>
                  ) : key ? (
                    key.maskedKey
                  ) : (
                    <span className={styles.muted}>Not set</span>
                  )}
                </td>
                <td>
                  {key && (
                    <span
                      className={`${styles.badge} ${key.enabled ? styles.badgeOk : styles.badgeMute}`}
                    >
                      {key.enabled ? 'ENABLED' : 'DISABLED'}
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

// --- Tab: Sources & Schedule (ADMIN only) ---

function SourcesTab({ api }) {
  const [sources, setSources] = useState([])
  const [triggering, setTriggering] = useState({})

  useEffect(() => {
    api.listSources().then(setSources).catch(console.error)
  }, [api])

  const handleTrigger = async (sourceId) => {
    setTriggering((prev) => ({ ...prev, [sourceId]: true }))
    try {
      const { runId } = await api.triggerSource(sourceId)
      alert(`Run started: ${runId}`)
    } catch (e) {
      alert('Trigger failed: ' + e.message)
    } finally {
      setTriggering((prev) => ({ ...prev, [sourceId]: false }))
      api.listSources().then(setSources).catch(console.error)
    }
  }

  const vendorName = (vendorId) => VENDORS.find((v) => v.id === vendorId)?.name ?? vendorId

  return (
    <div className={styles.sourceGrid}>
      {sources.map((src) => (
        <div key={src.id} className={styles.sourceCard}>
          <h3>{vendorName(src.vendorId)}</h3>
          <div className={styles.sourceDetail}>
            Schedule: <span>{src.scheduleCron ?? '—'}</span>
          </div>
          <div className={styles.sourceDetail}>
            Last run:{' '}
            <span>{src.lastRunAt ? new Date(src.lastRunAt).toLocaleString() : 'Never'}</span>
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

// --- Tab: Run History (ADMIN only) ---

function RunHistoryTab({ api, sources }) {
  const [runs, setRuns] = useState([])

  useEffect(() => {
    if (sources.length === 0) return
    Promise.all(sources.map((s) => api.listRuns(s.id, 0, 5)))
      .then((results) => {
        const all = results.flat().sort((a, b) => new Date(b.startedAt) - new Date(a.startedAt))
        setRuns(all)
      })
      .catch(console.error)
  }, [api, sources])

  const vendorName = (sourceId) => {
    const src = sources.find((s) => s.id === sourceId)
    return VENDORS.find((v) => v.id === src?.vendorId)?.name ?? src?.vendorId ?? sourceId
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
        {runs.map((run) => (
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
            <td colSpan={7} className={styles.empty}>
              No runs yet.
            </td>
          </tr>
        )}
      </tbody>
    </table>
  )
}

// --- Tab: My API Keys (TENANT_ADMIN) ---

function MyKeysTab({ api }) {
  const [ownKeys, setOwnKeys] = useState([])
  const [modal, setModal] = useState(null)

  const reload = useCallback(() => {
    api.listOwnKeys().then(setOwnKeys).catch(console.error)
  }, [api])

  useEffect(() => {
    reload()
  }, [reload])

  const keyByVendor = Object.fromEntries(ownKeys.map((k) => [k.vendorId, k]))

  const handleSave = async (keyValue) => {
    await api.upsertOwnKey(modal.vendorId, keyValue)
    setModal(null)
    reload()
  }

  const handleDelete = async (vendorId) => {
    if (
      !window.confirm(
        `Remove your key for ${vendorId}? The system default will be used instead.`
      )
    )
      return
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
          {VENDORS.map((v) => {
            const key = keyByVendor[v.id]
            if (!v.requiresKey) {
              return (
                <tr key={v.id}>
                  <td className={`${styles.name} ${styles.muted}`}>{v.name}</td>
                  <td colSpan={3} className={styles.muted}>
                    No key needed — always available
                  </td>
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
                    {key ? 'OWN KEY' : 'USING GLOBAL'}
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

// --- Main page ---

export function IntegrationsPage() {
  const { role } = useAuth()
  const request = useAuthRequest()
  const api = useMemo(() => integrationsApi(request), [request])
  const isAdmin = hasPermission(role, 'INTEGRATIONS_GLOBAL_MANAGE')

  const adminTabs = ['Global Keys', 'Sources & Schedule', 'Run History']
  const [activeTab, setActiveTab] = useState(isAdmin ? 'Global Keys' : 'My API Keys')
  const [sources, setSources] = useState([])

  useEffect(() => {
    if (isAdmin) {
      api.listSources().then(setSources).catch(console.error)
    }
  }, [isAdmin, api])

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <h2>Integrations</h2>
          <div className={styles.systemId}>
            &#x2295; knowledge-engine &middot; enrichment connectors
          </div>
        </div>
      </div>

      <div className={styles.tabs}>
        {isAdmin ? (
          adminTabs.map((tab) => (
            <button
              key={tab}
              className={`${styles.tab} ${activeTab === tab ? styles.tabActive : ''}`}
              onClick={() => setActiveTab(tab)}
            >
              {tab}
            </button>
          ))
        ) : (
          <button className={`${styles.tab} ${styles.tabActive}`}>My API Keys</button>
        )}
      </div>

      {isAdmin && activeTab === 'Global Keys' && <GlobalKeysTab api={api} />}
      {isAdmin && activeTab === 'Sources & Schedule' && <SourcesTab api={api} />}
      {isAdmin && activeTab === 'Run History' && (
        <RunHistoryTab api={api} sources={sources} />
      )}
      {!isAdmin && <MyKeysTab api={api} />}
    </div>
  )
}
