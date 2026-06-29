import { useCallback, useEffect, useMemo, useState } from 'react'
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
  RUNNING: 'blue',
  CREATED: 'gray',
  PAUSED: 'yellow',
  FAILED: 'red',
}

const TEMPLATE_LABEL = {
  TOPIC: 'Topic',
  PERSON: 'Person',
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

  const filtered = statusFilter ? sessions.filter((s) => s.status === statusFilter) : sessions

  const compareList = sessions.filter((s) => compareIds.has(s.id))

  const COLUMNS = useMemo(
    () => [
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
        render: (val) => (val ? new Date(val).toLocaleString() : '—'),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [compareIds]
  )

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h2>DEEP RESEARCH</h2>
          <div className="system-id">⌘ knowledge-engine · internal</div>
        </div>
        <div className={styles.headerActions}>
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
        emptyText={loading ? 'Loading sessions…' : 'No research sessions yet. Start one to begin.'}
      />

      {showModal && (
        <StartResearchModal onClose={() => setShowModal(false)} onStarted={handleSessionStarted} />
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
