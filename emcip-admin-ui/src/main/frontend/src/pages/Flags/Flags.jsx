import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { flagsApi } from '../../api/flags'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import styles from './Flags.module.css'

const DECISIONS = ['', 'FLAG', 'WARN', 'MUTE', 'BAN', 'DELETE', 'ESCALATE']

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
  try {
    return JSON.parse(raw)
  } catch {
    return {}
  }
}

export function Flags() {
  const { token } = useAuth()
  const api = flagsApi(makeRequest(token))
  const [flags, setFlags] = useState([])
  const [size, setSize] = useState(50)
  const [decision, setDecision] = useState('')
  const [error, setError] = useState('')

  const load = () =>
    api.list(size, decision).then(setFlags).catch(e => setError(e.message))

  useEffect(() => { load() }, [size, decision])

  const markReviewed = async flag => {
    try {
      await api.updateStatus(flag.id, 'REVIEWED')
      setFlags(prev => prev.map(f => f.id === flag.id ? { ...f, signalStatus: 'REVIEWED' } : f))
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div>
      <div className={styles.header}>
        <h2>Flags</h2>
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
            <th></th>
          </tr>
        </thead>
        <tbody>
          {flags.map(f => {
            const meta = parseMeta(f.metadata)
            return (
              <tr key={f.id}>
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
                <td className={styles.actions}>
                  {f.signalStatus !== 'REVIEWED' && f.signalStatus !== 'ACTIONED' && (
                    <Button variant="secondary" onClick={() => markReviewed(f)}>Mark reviewed</Button>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
