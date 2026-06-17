import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { resolutionReviewApi } from '../../api/resolutionReview'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog/ConfirmDialog'
import styles from './ResolutionQueue.module.css'

const STATUS_OPTIONS = ['PENDING', 'MERGED', 'DISMISSED', '']
const STATUS_LABELS = { PENDING: 'Pending', MERGED: 'Merged', DISMISSED: 'Dismissed', '': 'All' }
const STATUS_VARIANT = { PENDING: 'yellow', MERGED: 'green', DISMISSED: 'gray' }

const PAGE_SIZES = [10, 20, 50]

function scoreClass(score, styles) {
  return score >= 0.80 && score < 0.92 ? styles.scoreWarn : styles.scoreNormal
}

export function ResolutionQueue() {
  const api = resolutionReviewApi(useAuthRequest())

  const [flags, setFlags] = useState([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [pendingAction, setPendingAction] = useState(null) // { id, action: 'merge'|'dismiss', candidateLabel, similarLabel }
  const [busyIds, setBusyIds] = useState(new Set())

  const [filters, setFilters] = useState({ page: 0, size: 20, status: 'PENDING', conceptType: '' })

  const setFilter = (key, value) =>
    setFilters(f => ({ ...f, [key]: value, ...(key !== 'page' ? { page: 0 } : {}) }))

  const load = () => {
    setLoading(true)
    setError('')
    api
      .list(filters.page, filters.size, filters.status, filters.conceptType)
      .then(data => {
        setFlags(data?.content ?? [])
        setTotal(data?.totalElements ?? 0)
      })
      .catch(e => setError(e.message ?? 'Failed to load flags'))
      .finally(() => setLoading(false))
  }

  useEffect(load, [filters])

  const handleConfirm = () => {
    if (!pendingAction) return
    const { id, action } = pendingAction
    setPendingAction(null)
    setBusyIds(s => new Set(s).add(id))
    const call = action === 'merge' ? api.merge(id) : api.dismiss(id)
    call
      .then(load)
      .catch(e => setError(e.message ?? `Failed to ${action} flag`))
      .finally(() => setBusyIds(s => { const n = new Set(s); n.delete(id); return n }))
  }

  const totalPages = Math.ceil(total / filters.size)

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h2>RESOLUTION QUEUE</h2>
          <div className="system-id">&#x2297; knowledge-engine · 9088 · entity deduplication</div>
        </div>
      </div>

      {/* Filters */}
      <div className="filter-row">
        <select
          className="filter-select"
          value={filters.status}
          onChange={e => setFilter('status', e.target.value)}
        >
          {STATUS_OPTIONS.map(s => (
            <option key={s} value={s}>{STATUS_LABELS[s]}</option>
          ))}
        </select>

        <select
          className="filter-select"
          value={filters.size}
          onChange={e => setFilter('size', Number(e.target.value))}
        >
          {PAGE_SIZES.map(n => (
            <option key={n} value={n}>{n} / page</option>
          ))}
        </select>
      </div>

      {error && <div className={styles.error}>{error}</div>}

      {/* Table */}
      <table className="tbl">
        <thead>
          <tr>
            <th>Created</th>
            <th>Candidate</th>
            <th>Similar To</th>
            <th>Type</th>
            <th>Score</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {!loading && flags.length === 0 && (
            <tr>
              <td colSpan={7} className={styles.emptyState}>
                No resolution flags found.
              </td>
            </tr>
          )}
          {flags.map(flag => {
            const busy = busyIds.has(flag.id)
            const canAct = flag.status === 'PENDING' && !busy
            return (
              <tr key={flag.id}>
                <td className={styles.createdAt}>
                  {flag.createdAt ? new Date(flag.createdAt).toLocaleString() : '—'}
                </td>
                <td>{flag.candidateLabel}</td>
                <td>{flag.similarLabel}</td>
                <td><Badge variant="blue">{flag.conceptType}</Badge></td>
                <td>
                  <span className={scoreClass(flag.similarityScore, styles)}>
                    {flag.similarityScore?.toFixed(3)}
                  </span>
                </td>
                <td>
                  <Badge variant={STATUS_VARIANT[flag.status] ?? 'gray'}>
                    {flag.status}
                  </Badge>
                </td>
                <td>
                  <div className={styles.actions}>
                    <Button
                      variant="primary"
                      disabled={!canAct}
                      onClick={() => setPendingAction({
                        id: flag.id,
                        action: 'merge',
                        candidateLabel: flag.candidateLabel,
                        similarLabel: flag.similarLabel,
                      })}
                    >
                      Merge
                    </Button>
                    <Button
                      variant="secondary"
                      disabled={!canAct}
                      onClick={() => setPendingAction({
                        id: flag.id,
                        action: 'dismiss',
                        candidateLabel: flag.candidateLabel,
                        similarLabel: flag.similarLabel,
                      })}
                    >
                      Dismiss
                    </Button>
                  </div>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="pagination">
          <Button
            variant="secondary"
            disabled={filters.page === 0}
            onClick={() => setFilter('page', filters.page - 1)}
          >
            &#x25C2; Prev
          </Button>
          <span className="pagination-info">
            Page {filters.page + 1} of {totalPages} — {total} flags
          </span>
          <Button
            variant="secondary"
            disabled={filters.page >= totalPages - 1}
            onClick={() => setFilter('page', filters.page + 1)}
          >
            Next &#x25B8;
          </Button>
        </div>
      )}

      {/* Confirmation dialog */}
      {pendingAction && (
        <ConfirmDialog
          title={pendingAction.action === 'merge' ? 'Merge Entity' : 'Dismiss Flag'}
          message={
            pendingAction.action === 'merge'
              ? `Merge "${pendingAction.candidateLabel}" into "${pendingAction.similarLabel}"? This will delete the candidate node and reroute all its graph relationships. This cannot be undone.`
              : `Dismiss this resolution flag for "${pendingAction.candidateLabel}"? The candidate node will be kept as a separate entity.`
          }
          confirmLabel={pendingAction.action === 'merge' ? 'Merge' : 'Dismiss'}
          onConfirm={handleConfirm}
          onClose={() => setPendingAction(null)}
        />
      )}
    </div>
  )
}
