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

const STRATEGY_LABEL = {
  TOPIC_EXPLORATION: 'Topic',
  PERSON_ANALYSIS: 'Person',
  OPINION_MAPPING: 'Opinion',
  COMPARISON: 'Compare',
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
        const updated = await researchApi(request).getSession(id)
        setSession(updated)
        if (!POLLING_STATUSES.has(updated.status)) {
          stopPolling()
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
    api
      .getSession(id)
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
          \u2190 Back to Research
        </button>
        <p style={{ color: 'var(--signal-stop-fg)' }}>{error}</p>
      </div>
    )
  }

  if (!session) {
    return (
      <div className="page">
        <button className={styles.backLink} onClick={() => navigate('/research')}>
          \u2190 Back to Research
        </button>
        <p style={{ color: 'var(--fg-3)', fontFamily: 'var(--font-mono)', fontSize: 13 }}>
          Loading\u2026
        </p>
      </div>
    )
  }

  const evidenceCount = session.evidence?.length ?? 0

  return (
    <div className="page">
      <button className={styles.backLink} onClick={() => navigate('/research')}>
        \u2190 Back to Research
      </button>

      <div className="page-header" style={{ marginBottom: 'var(--sp-2)' }}>
        <div>
          <h2>SESSION DETAIL</h2>
          <div className="system-id">\u2318 knowledge-engine \u00b7 internal</div>
        </div>
      </div>

      <p className={styles.question}>{session.question}</p>

      <div className={styles.metaRow}>
        <Badge variant={STATUS_VARIANT[session.status] ?? 'gray'}>{session.status}</Badge>
        {session.reportTemplate && (
          <Badge variant="gray">
            {TEMPLATE_LABEL[session.reportTemplate] ?? session.reportTemplate}
          </Badge>
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
          <span className={styles.pollingIndicator}>\u25c8 live</span>
        )}
      </div>

      {session.status === 'RUNNING' && (
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

      {session.errorMessage && <div className={styles.errorBox}>{session.errorMessage}</div>}

      <div className={styles.tabBar}>
        {[
          { key: 'overview', label: 'Overview' },
          { key: 'evidence', label: `Evidence (${evidenceCount})` },
          { key: 'report', label: 'Report' },
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

      {activeTab === 'overview' && (
        <div className={styles.metaGrid}>
          <span className={styles.metaLabel}>Session ID</span>
          <span
            className={styles.metaValue}
            style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
          >
            {session.id}
          </span>

          <span className={styles.metaLabel}>Tenant ID</span>
          <span
            className={styles.metaValue}
            style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
          >
            {session.tenantId ?? '\u2014'}
          </span>

          <span className={styles.metaLabel}>Status</span>
          <span className={styles.metaValue}>{session.status}</span>

          <span className={styles.metaLabel}>Template</span>
          <span className={styles.metaValue}>
            {TEMPLATE_LABEL[session.reportTemplate] ?? session.reportTemplate ?? '\u2014'}
          </span>

          <span className={styles.metaLabel}>Web Search</span>
          <span className={styles.metaValue}>
            {session.webSearchEnabled ? 'Enabled' : 'Disabled'}
          </span>

          <span className={styles.metaLabel}>Iterations</span>
          <span className={styles.metaValue}>
            {session.iterationsUsed} used of {session.maxIterations} max
          </span>

          <span className={styles.metaLabel}>LLM Calls</span>
          <span className={styles.metaValue}>
            {session.llmCallsUsed} used of {session.maxLlmCalls} max
          </span>

          <span className={styles.metaLabel}>Cost Used</span>
          <span className={styles.metaValue}>
            ${(session.costUsedUsd ?? 0).toFixed(4)} of ${(session.costLimitUsd ?? 1).toFixed(2)}{' '}
            limit
          </span>

          <span className={styles.metaLabel}>Created</span>
          <span
            className={styles.metaValue}
            style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
          >
            {session.createdAt ? new Date(session.createdAt).toLocaleString() : '\u2014'}
          </span>

          <span className={styles.metaLabel}>Updated</span>
          <span
            className={styles.metaValue}
            style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
          >
            {session.updatedAt ? new Date(session.updatedAt).toLocaleString() : '\u2014'}
          </span>

          <span className={styles.metaLabel}>Report ID</span>
          <span
            className={styles.metaValue}
            style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
          >
            {session.reportId ?? '\u2014'}
          </span>
        </div>
      )}

      {activeTab === 'evidence' &&
        (evidenceCount === 0 ? (
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
              {session.evidence.map((e) => (
                <tr key={e.id}>
                  <td
                    style={{
                      fontFamily: 'var(--font-mono)',
                      fontSize: 11,
                      color: 'var(--fg-3)',
                    }}
                  >
                    {e.iteration + 1}
                  </td>
                  <td
                    style={{
                      fontSize: 12,
                      color: 'var(--fg-2)',
                      maxWidth: 180,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {e.subQuestion}
                  </td>
                  <td>
                    <Badge variant="gray">
                      {STRATEGY_LABEL[e.queryStrategy] ?? e.queryStrategy}
                    </Badge>
                  </td>
                  <td className={styles.findingCell}>{e.finding}</td>
                  <td>
                    <Badge variant={e.sourceType === 'WEB_SEARCH' ? 'blue' : 'gray'}>
                      {e.sourceType === 'WEB_SEARCH' ? 'WEB' : 'KB'}
                    </Badge>
                  </td>
                  <td className={styles.sourceRefCell} title={e.sourceRef}>
                    {e.sourceRef}
                  </td>
                  <td className={styles.confScore}>{(e.confidenceScore * 100).toFixed(0)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}

      {activeTab === 'report' &&
        (session.status !== 'COMPLETED' ? (
          <p className={styles.noReport}>Run research to completion to generate a report.</p>
        ) : loadingReport ? (
          <p className={styles.noReport}>Loading report\u2026</p>
        ) : report ? (
          <ReportViewer report={report} />
        ) : (
          <p className={styles.noReport}>No report available for this session.</p>
        ))}
    </div>
  )
}
