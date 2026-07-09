import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { useToast } from '../../components/Toast/useToast'
import { knowledgeApi } from '../../api/knowledge'
import { tenantsApi } from '../../api/tenants'
import { IngestionModal } from './IngestionModal'
import styles from './KnowledgePage.module.css'

const STATUS_VARIANT = {
  COMPLETED: 'green',
  RUNNING: 'blue',
  QUEUED: 'gray',
  FAILED: 'red',
}

const JOB_COLUMNS = [
  { key: 'sourceType', label: 'Type', width: '80px' },
  { key: 'sourceRef', label: 'Source' },
  { key: 'tenantId', label: 'Tenant', width: '160px', mono: true },
  {
    key: 'status',
    label: 'Status',
    width: '110px',
    render: (_, row) => (
      <Badge variant={STATUS_VARIANT[row.status] ?? 'gray'}>{row.status}</Badge>
    ),
  },
  { key: 'chunkCount', label: 'Chunks', width: '80px', mono: true },
  { key: 'createdAt', label: 'Created', width: '180px', mono: true },
]

const SEARCH_TYPES = ['VECTOR', 'GRAPH', 'HYBRID']

export function Knowledge() {
  const { token } = useAuth()
  const request = useAuthRequest()
  const { addToast } = useToast()
  const [activeTab, setActiveTab] = useState('search')
  const prevJobStatuses = useRef(null) // null = first load (seed only, no toasts)
  const toastedTransitions = useRef(new Set())
  const hasActiveJobsRef = useRef(false)

  // — Tenants (shared) —
  const [tenants, setTenants] = useState([])

  // — Ingestion Jobs tab state —
  const [jobs, setJobs] = useState([])
  const [totalPages, setTotalPages] = useState(0)
  const [page, setPage] = useState(0)
  const [showModal, setShowModal] = useState(false)
  const [jobsLoading, setJobsLoading] = useState(true)

  // — Search tab state —
  const [query, setQuery] = useState('')
  const [searchType, setSearchType] = useState('HYBRID')
  const [searchTenantId, setSearchTenantId] = useState('')
  const [results, setResults] = useState(null) // null = not searched yet
  const [searchLoading, setSearchLoading] = useState(false)
  const [searchError, setSearchError] = useState('')
  const [expandedNodeId, setExpandedNodeId] = useState(null)
  const [neighbors, setNeighbors] = useState([])

  const rawFetch = useCallback(
    (path, options = {}) => {
      const API_BASE = import.meta.env.VITE_API_BASE ?? ''
      return fetch(`${API_BASE}${path}`, {
        ...options,
        headers: { Authorization: `Bearer ${token}`, ...options.headers },
      }).then(res => {
        if (!res.ok) return Promise.reject(new Error(`${res.status} ${res.statusText}`))
        return res.json()
      })
    },
    [token]
  )

  const api = useMemo(() => knowledgeApi(request, rawFetch), [request, rawFetch])

  useEffect(() => {
    tenantsApi(request).list().then(setTenants).catch(() => {})
  }, [request])

  // Load ingestion jobs
  const loadJobs = useCallback(async () => {
    setJobsLoading(true)
    try {
      const data = await api.jobs(page, 20)

      // Detect status transitions and fire toasts
      // First load (prev === null): seed statuses only, don't toast stale results
      const prev = prevJobStatuses.current
      if (prev !== null) {
        for (const job of data?.content ?? []) {
          const oldStatus = prev[job.id]
          if (oldStatus && oldStatus !== job.status) {
            const transitionKey = `${job.id}:${job.status}`
            if (toastedTransitions.current.has(transitionKey)) continue
            toastedTransitions.current.add(transitionKey)
            if (job.status === 'COMPLETED') {
              addToast('success', `Ingestion complete: ${job.sourceRef} — ${job.chunkCount || 0} chunks`)
            } else if (job.status === 'FAILED') {
              addToast('error', `Ingestion failed: ${job.sourceRef} — ${job.errorMessage || 'Unknown error'}`)
            }
          }
        }
      }

      // Update tracked statuses
      const newStatuses = {}
      for (const job of data?.content ?? []) {
        newStatuses[job.id] = job.status
      }
      prevJobStatuses.current = newStatuses

      const mapped = (data?.content ?? []).map(j => ({
        ...j,
        rawStatus: j.status,
        tenantId: j.tenantId
          ? (tenants.find(t => t.id === j.tenantId)?.name ?? j.tenantId)
          : 'Global',
        createdAt: j.createdAt ? new Date(j.createdAt).toLocaleString() : '\u2014',
      }))
      hasActiveJobsRef.current = mapped.some(j => j.rawStatus === 'QUEUED' || j.rawStatus === 'RUNNING')
      setJobs(mapped)
      setTotalPages(data?.totalPages ?? 0)
    } catch {
      // keep stale data visible
    } finally {
      setJobsLoading(false)
    }
  }, [page, tenants, api, addToast])

  useEffect(() => {
    if (activeTab === 'jobs') loadJobs()
  }, [activeTab, loadJobs])

  // Auto-poll when any visible job is QUEUED or RUNNING
  useEffect(() => {
    if (activeTab !== 'jobs') return

    const interval = setInterval(() => {
      if (hasActiveJobsRef.current) loadJobs()
    }, 5000)
    return () => clearInterval(interval)
  }, [activeTab, loadJobs])

  // Search
  async function handleSearch() {
    if (!query.trim()) return
    setSearchLoading(true)
    setSearchError('')
    setResults(null)
    setExpandedNodeId(null)
    setNeighbors([])
    try {
      const data = await api.search(
        query.trim(),
        searchType,
        searchTenantId || null,
        null,
        20
      )
      setResults(data)
    } catch (e) {
      setSearchError(e.message || 'Search failed.')
    } finally {
      setSearchLoading(false)
    }
  }

  // Entity click — expand/collapse neighbors
  async function handleEntityClick(node) {
    if (expandedNodeId === node.id) {
      setExpandedNodeId(null)
      setNeighbors([])
      return
    }
    setExpandedNodeId(node.id)
    setNeighbors([])
    try {
      const data = await api.graphNeighbors(node.id, null, 1)
      setNeighbors(Array.isArray(data) ? data : [])
    } catch {
      setNeighbors([])
    }
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter') handleSearch()
  }

  const graphResults = results?.graphResults ?? []
  const documentResults = results?.documentResults ?? []

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h2 className={styles.title}>KNOWLEDGE BASE</h2>
          <div className={styles.subtitle}>◆ knowledge-engine · port 9088</div>
        </div>
        {activeTab === 'jobs' && (
          <Button variant="primary" onClick={() => setShowModal(true)}>
            Add Document
          </Button>
        )}
      </div>

      {/* Tab switcher */}
      <div className={styles.tabRow}>
        <button
          type="button"
          className={`${styles.tab}${activeTab === 'search' ? ` ${styles.tabActive}` : ''}`}
          onClick={() => setActiveTab('search')}
        >
          Search
        </button>
        <button
          type="button"
          className={`${styles.tab}${activeTab === 'jobs' ? ` ${styles.tabActive}` : ''}`}
          onClick={() => setActiveTab('jobs')}
        >
          Ingestion Jobs
        </button>
      </div>

      {/* ── Search tab ── */}
      {activeTab === 'search' && (
        <div>
          {/* Search bar */}
          <div className={styles.searchBar}>
            <input
              className={styles.searchInput}
              type="text"
              placeholder="Search the knowledge base…"
              value={query}
              onChange={e => setQuery(e.target.value)}
              onKeyDown={handleKeyDown}
            />
            <div className={styles.typeSelector}>
              {SEARCH_TYPES.map(t => (
                <button
                  key={t}
                  type="button"
                  className={`${styles.typeSeg}${searchType === t ? ` ${styles.typeSegActive}` : ''}`}
                  onClick={() => setSearchType(t)}
                >
                  {t}
                </button>
              ))}
            </div>
            <select
              className={styles.tenantFilter}
              value={searchTenantId}
              onChange={e => setSearchTenantId(e.target.value)}
            >
              <option value="">All tenants</option>
              {tenants.map(t => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
            <Button
              variant="primary"
              onClick={handleSearch}
              disabled={!query.trim() || searchLoading}
            >
              Search
            </Button>
          </div>

          {/* States */}
          {!results && !searchLoading && !searchError && (
            <p className={styles.emptyState}>Enter a query to search the knowledge base.</p>
          )}
          {searchLoading && <p className={styles.emptyState}>Searching…</p>}
          {searchError && <p className={styles.searchError}>{searchError}</p>}
          {results &&
            !searchLoading &&
            graphResults.length === 0 &&
            documentResults.length === 0 && (
              <p className={styles.emptyState}>
                No results found. Try a different query or search type.
              </p>
            )}

          {/* Results grid */}
          {results && (graphResults.length > 0 || documentResults.length > 0) && (
            <>
              <div className={styles.resultsGrid}>
                {/* Entities column */}
                <div>
                  <div className={styles.colLabel}>— ENTITIES ({graphResults.length}) —</div>
                  {graphResults.length === 0 && (
                    <p className={styles.emptyCol}>No entity results.</p>
                  )}
                  {graphResults.map(r => (
                    <div
                      key={r.node.id}
                      className={`${styles.entityCard}${expandedNodeId === r.node.id ? ` ${styles.entityCardActive}` : ''}`}
                      onClick={() => handleEntityClick(r.node)}
                      role="button"
                      tabIndex={0}
                      onKeyDown={e => e.key === 'Enter' && handleEntityClick(r.node)}
                    >
                      <div className={styles.cardMeta}>
                        <span className={styles.conceptBadge}>{r.node.conceptType}</span>
                        <span
                          className={`${styles.scoreTag}${r.score >= 0.85 ? ` ${styles.scoreHigh}` : ''}`}
                        >
                          {r.score.toFixed(2)}
                        </span>
                      </div>
                      <div className={styles.entityLabel}>{r.node.label}</div>
                      {(r.connections?.length ?? 0) > 0 && (
                        <div className={styles.entityConnections}>
                          {(r.connections?.slice(0, 3) ?? []).map(c => (
                            <div key={c.id} className={styles.connectionLine}>
                              → {c.conceptType} · {c.label}
                            </div>
                          ))}
                          {(r.connections?.length ?? 0) > 3 && (
                            <div className={styles.connectionLine}>
                              +{r.connections.length - 3} more
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  ))}
                </div>

                {/* Passages column */}
                <div>
                  <div className={styles.colLabel}>
                    — PASSAGES ({documentResults.length}) —
                  </div>
                  {documentResults.length === 0 && (
                    <p className={styles.emptyCol}>No passage results.</p>
                  )}
                  {documentResults.map((r, i) => (
                    <div key={r.document?.id ?? i} className={styles.passageCard}>
                      <div className={styles.cardMeta}>
                        <span className={styles.passageSource}>
                          {r.document?.sourceType ?? ''} · {r.document?.sourceRef ?? ''}
                        </span>
                        <span
                          className={`${styles.scoreTag}${r.similarity >= 0.85 ? ` ${styles.scoreHigh}` : ''}`}
                        >
                          {r.similarity.toFixed(2)}
                        </span>
                      </div>
                      <div className={styles.passageContent}>{r.document?.content ?? ''}</div>
                      {r.document?.createdAt && (
                        <div className={styles.passageDate}>
                          {new Date(r.document.createdAt).toLocaleDateString()}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </div>

              {/* Neighbor expansion panel */}
              {expandedNodeId && (
                <div className={styles.neighborPanel}>
                  <div className={styles.neighborLabel}>
                    —{' '}
                    {graphResults.find(r => r.node.id === expandedNodeId)?.node.label ?? ''} ·
                    NEIGHBORS —
                  </div>
                  {neighbors.length === 0 ? (
                    <span className={styles.emptyCol}>No neighbors found.</span>
                  ) : (
                    <div className={styles.neighborChips}>
                      {neighbors.map(n => (
                        <span key={n.id} className={styles.neighborChip}>
                          {n.label}{' '}
                          <span className={styles.neighborType}>{n.conceptType}</span>
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* ── Ingestion Jobs tab ── */}
      {activeTab === 'jobs' && (
        <>
          <DataTable
            columns={JOB_COLUMNS}
            rows={jobs}
            emptyText={jobsLoading ? 'Loading…' : 'No ingestion jobs yet. Submit a URL or file.'}
          />

          {totalPages > 1 && (
            <div className={styles.pagination}>
              <button
                className={styles.pageBtn}
                disabled={page === 0}
                onClick={() => setPage(p => p - 1)}
              >
                ◂ Prev
              </button>
              <span className={styles.pageInfo}>
                {page + 1} / {totalPages}
              </span>
              <button
                className={styles.pageBtn}
                disabled={page >= totalPages - 1}
                onClick={() => setPage(p => p + 1)}
              >
                Next ▸
              </button>
            </div>
          )}

          {showModal && (
            <IngestionModal
              api={api}
              tenants={tenants}
              onClose={() => setShowModal(false)}
              onJobCreated={loadJobs}
            />
          )}
        </>
      )}
    </div>
  )
}
