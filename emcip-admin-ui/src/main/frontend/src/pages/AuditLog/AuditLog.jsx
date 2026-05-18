import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { auditLogApi } from '../../api/auditLog'
import styles from './AuditLog.module.css'

const EVENT_TYPES = ['', 'MESSAGE_RECEIVED', 'MESSAGE_CLASSIFIED', 'POLICY_DECISION', 'MODERATION_ACTION']

export function AuditLog() {
  const { token } = useAuth()
  const api = auditLogApi(makeRequest(token))
  const [events, setEvents] = useState([])
  const [size, setSize] = useState(50)
  const [eventType, setEventType] = useState('')
  const [error, setError] = useState('')

  const load = () => api.list(size, eventType).then(setEvents).catch(e => setError(e.message))
  useEffect(() => { load() }, [size, eventType])

  return (
    <div>
      <div className={styles.header}>
        <h2>Audit Log</h2>
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
          <tr><th>Timestamp</th><th>Event Type</th><th>Entity ID</th><th>Details</th></tr>
        </thead>
        <tbody>
          {events.map((e, i) => (
            <tr key={i}>
              <td className={styles.mono}>{e.timestamp ? new Date(e.timestamp).toLocaleString() : '\u2014'}</td>
              <td>{e.eventType}</td>
              <td className={styles.mono}>{e.entityId ?? '\u2014'}</td>
              <td className={styles.details}>{e.details != null ? (typeof e.details === 'object' ? JSON.stringify(e.details) : e.details) : '\u2014'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
