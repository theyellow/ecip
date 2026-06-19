import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
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

const COLUMNS = [
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

export function Knowledge() {
  const { token } = useAuth()
  const request = useAuthRequest()
  const [jobs, setJobs] = useState([])
  const [totalPages, setTotalPages] = useState(0)
  const [page, setPage] = useState(0)
  const [showModal, setShowModal] = useState(false)
  const [loading, setLoading] = useState(true)
  const [tenants, setTenants] = useState([])

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
    tenantsApi(request)
      .list()
      .then(setTenants)
      .catch(() => {})
  }, [request])

  const loadJobs = useCallback(async () => {
    setLoading(true)
    try {
      const data = await api.jobs(page, 20)
      setJobs(
        (data?.content ?? []).map(j => ({
          ...j,
          tenantId: j.tenantId
            ? (tenants.find(t => t.id === j.tenantId)?.name ?? j.tenantId)
            : 'Global',
          createdAt: j.createdAt ? new Date(j.createdAt).toLocaleString() : '\u2014',
        }))
      )
      setTotalPages(data?.totalPages ?? 0)
    } catch {
      setJobs([])
    } finally {
      setLoading(false)
    }
  }, [page, tenants, api])

  useEffect(() => {
    loadJobs()
  }, [loadJobs])

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h2 className={styles.title}>KNOWLEDGE BASE</h2>
          <div className={styles.subtitle}>◆ knowledge-engine · port 9088</div>
        </div>
        <Button variant="primary" onClick={() => setShowModal(true)}>
          Add Document
        </Button>
      </div>

      <DataTable
        columns={COLUMNS}
        rows={jobs}
        emptyText={loading ? 'Loading...' : 'No ingestion jobs yet. Submit a URL or file.'}
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
    </div>
  )
}
