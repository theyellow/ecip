import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { flagsApi } from '../../api/flags'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import styles from './Flags.module.css'

const DECISIONS = ['', 'FLAG', 'WARN', 'MUTE', 'BAN', 'DELETE', 'ESCALATE']
const STATUSES = ['NEW', 'REVIEWED', 'ACTIONED']

const DECISION_VARIANT = {
  FLAG: 'blue',
  WARN: 'yellow',
  MUTE: 'yellow',
  BAN: 'red',
  DELETE: 'red',
  ESCALATE: 'gray',
}

const STATUS_VARIANT = { NEW: 'blue', REVIEWED: 'gray', ACTIONED: 'green' }

function parseMeta(raw) {
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(raw)
  } catch {
    return {}
  }
}

function FlagDetailModal({ flag, onClose, onStatusChange }) {
  const meta = parseMeta(flag.metadata)
  const [status, setStatus] = useState(flag.signalStatus ?? 'NEW')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const handleStatusChange = async newStatus => {
    setSaving(true)
    setError('')
    try {
      await onStatusChange(flag.id, newStatus)
      setStatus(newStatus)
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={e => e.stopPropagation()}>
        <div className={styles.modalHeader}>
          <h3>Flag Detail</h3>
          <button className={styles.closeBtn} onClick={onClose} aria-label="Close">&times;</button>
        </div>

        <div className={styles.modalBody}>
          <div className={styles.detailGrid}>
            <span className={styles.label}>Decision</span>
            <span><Badge variant={DECISION_VARIANT[flag.decision] ?? 'gray'}>{flag.decision}</Badge></span>

            <span className={styles.label}>Status</span>
            <span className={styles.statusRow}>
              <Badge variant={STATUS_VARIANT[status] ?? 'gray'}>{status}</Badge>
              <span className={styles.statusButtons}>
                {STATUSES.filter(s => s !== status).map(s => (
                  <Button key={s} variant="secondary" disabled={saving} onClick={() => handleStatusChange(s)}>
                    {s.charAt(0) + s.slice(1).toLowerCase()}
                  </Button>
                ))}
              </span>
            </span>

            <span className={styles.label}>Timestamp</span>
            <span className={styles.mono}>{flag.timestamp ? new Date(flag.timestamp).toLocaleString() : '\u2014'}</span>

            <span className={styles.label}>Intent</span>
            <span><Badge variant="gray">{flag.originalIntent}</Badge></span>

            <span className={styles.label}>Confidence</span>
            <span className={styles.mono}>{flag.confidence != null ? (flag.confidence * 100).toFixed(1) + '%' : '\u2014'}</span>

            <span className={styles.label}>Reason</span>
            <span>{flag.reason || '\u2014'}</span>

            <span className={styles.label}>Message</span>
            <span className={styles.messageText}>{meta.messageText || '\u2014'}</span>

            {meta.chatId && <>
              <span className={styles.label}>Chat ID</span>
              <span className={styles.mono}>{meta.chatId}</span>
            </>}

            {meta.senderId && <>
              <span className={styles.label}>Sender ID</span>
              <span className={styles.mono}>{meta.senderId}</span>
            </>}

            <span className={styles.label}>Policy ID</span>
            <span className={styles.mono}>{flag.policyId || '\u2014'}</span>

            <span className={styles.label}>Event ID</span>
            <span className={styles.mono}>{flag.id}</span>
          </div>

          {error && <p className={styles.error} role="alert">{error}</p>}
        </div>
      </div>
    </div>
  )
}

export function Flags() {
  const api = flagsApi(useAuthRequest())
  const [flags, setFlags] = useState([])
  const [total, setTotal] = useState(0)
  const [page] = useState(0)
  const [size, setSize] = useState(50)
  const [decision, setDecision] = useState('')
  const [error, setError] = useState('')
  const [selected, setSelected] = useState(null)

  const load = () =>
    api
      .list(page, size, decision)
      .then(data => {
        setFlags(data?.items ?? [])
        setTotal(data?.total ?? 0)
      })
      .catch(e => setError(e.message))

  useEffect(() => { load() }, [size, decision])

  const updateStatus = async (id, status) => {
    await api.updateStatus(id, status)
    setFlags(prev => prev.map(f => f.id === id ? { ...f, signalStatus: status } : f))
    setSelected(prev => prev?.id === id ? { ...prev, signalStatus: status } : prev)
  }

  return (
    <div>
      <div className={styles.header}>
        <h2>Flags {total > 0 && <small style={{ fontWeight: 'normal', color: 'var(--text-muted)' }}>({total} total)</small>}</h2>
        <div className={styles.filters}>
          <select value={decision} onChange={e => setDecision(e.target.value)} className={styles.select}>
            {DECISIONS.map(d => <option key={d} value={d}>{d || 'All decisions'}</option>)}
          </select>
          <select value={size} onChange={e => setSize(Number(e.target.value))} className={styles.select}>
            {[25, 50, 100, 200].map(n => <option key={n}>{n}</option>)}
          </select>
        </div>
      </div>
      {error && <p className={styles.error} role="alert">{error}</p>}
      <table className={styles.table}>
        <thead>
          <tr>
            <th>Timestamp</th>
            <th>Decision</th>
            <th>Intent</th>
            <th>Confidence</th>
            <th>Message</th>
            <th>Reason</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {flags.map(f => {
            const meta = parseMeta(f.metadata)
            return (
              <tr key={f.id} className={styles.clickableRow} onClick={() => setSelected(f)}>
                <td className={styles.mono}>
                  {f.timestamp ? new Date(f.timestamp).toLocaleString() : '\u2014'}
                </td>
                <td><Badge variant={DECISION_VARIANT[f.decision] ?? 'gray'}>{f.decision}</Badge></td>
                <td><Badge variant="gray">{f.originalIntent}</Badge></td>
                <td className={styles.mono}>
                  {f.confidence != null ? (f.confidence * 100).toFixed(0) + '%' : '\u2014'}
                </td>
                <td className={styles.message} title={meta.messageText}>
                  {meta.messageText ?? '\u2014'}
                </td>
                <td>{f.reason ?? '\u2014'}</td>
                <td>
                  <Badge variant={STATUS_VARIANT[f.signalStatus] ?? 'gray'}>
                    {f.signalStatus ?? 'NEW'}
                  </Badge>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {selected && (
        <FlagDetailModal
          flag={selected}
          onClose={() => setSelected(null)}
          onStatusChange={updateStatus}
        />
      )}
    </div>
  )
}
