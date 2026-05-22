import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { auditLogApi } from '../../api/auditLog'
import styles from './AuditLog.module.css'

const EVENT_TYPES = ['', 'MESSAGE_RECEIVED', 'MESSAGE_CLASSIFIED', 'POLICY_DECISION', 'MODERATION_ACTION']

export function AuditLog() {
  const api = auditLogApi(useAuthRequest())
  const [events, setEvents] = useState([])
  const [total, setTotal] = useState(0)
  const [page] = useState(0)
  const [size, setSize] = useState(50)
  const [eventType, setEventType] = useState('')
  const [error, setError] = useState('')

  const load = () =>
    api
      .list(page, size, eventType)
      .then(data => {
        setEvents(data?.items ?? [])
        setTotal(data?.total ?? 0)
      })
      .catch(e => setError(e.message))
  useEffect(() => { load() }, [size, eventType])

  return (
    <div>
      <div className={styles.header}>
        <h2>Audit Log {total > 0 && <small style={{ fontWeight: 'normal', color: 'var(--text-muted)' }}>({total} total)</small>}</h2>
        <div className={styles.filters}>
          <select value={eventType} onChange={e => setEventType(e.target.value)} className={styles.select}>
            {EVENT_TYPES.map(t => <option key={t} value={t}>{t || 'All types'}</option>)}
          </select>
          <select value={size} onChange={e => setSize(Number(e.target.value))} className={styles.select}>
            {[25, 50, 100, 200].map(n => <option key={n}>{n}</option>)}
          </select>
        </div>
      </div>
      {error && <p className={styles.error}>{error}</p>}
      <table className={styles.table}>
        <thead>
          <tr><th>Timestamp</th><th>Event Type</th><th>Source</th><th>Action</th><th>Resource</th><th>Outcome</th><th>Details</th></tr>
        </thead>
        <tbody>
          {events.map((e, i) => (
            <tr key={i}>
              <td className={styles.mono}>{e.createdAt ? new Date(e.createdAt).toLocaleString() : '\u2014'}</td>
              <td>{e.eventType}</td>
              <td className={styles.mono}>{e.sourceService ?? '\u2014'}</td>
              <td>{e.action ?? '\u2014'}</td>
              <td className={styles.mono}>{e.resourceId ?? '\u2014'}</td>
              <td>{e.outcome ?? '\u2014'}</td>
              <td className={styles.details}>{e.details != null ? (typeof e.details === 'object' ? JSON.stringify(e.details) : e.details) : '\u2014'}</td>
            </tr>
          ))}
          {events.length === 0 && !error && (
            <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: '2rem' }}>No audit events in the last 24 hours</td></tr>
          )}
        </tbody>
      </table>
    </div>
  )
}
