import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { auditLogApi } from '../../api/auditLog'
import styles from './AuditLog.module.css'

const EVENT_TYPES = ['', 'MESSAGE_RECEIVED', 'MESSAGE_CLASSIFIED', 'POLICY_DECISION', 'MODERATION_ACTION']

function CopyButton({ text }) {
  const [copied, setCopied] = useState(false)
  const handle = () => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }
  return (
    <button className={`${styles.copyBtn}${copied ? ' ' + styles.copied : ''}`} onClick={handle}>
      {copied ? 'Copied!' : 'Copy'}
    </button>
  )
}

function parseDetails(raw) {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) } catch { return raw }
}

function DetailsModal({ event, onClose }) {
  const parsedDetails = parseDetails(event.details)
  const prettyDetails = parsedDetails != null ? JSON.stringify(parsedDetails, null, 2) : null
  const rawEvent = JSON.stringify(event, null, 2)

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={e => e.stopPropagation()}>
        <div className={styles.modalHeader}>
          <h3>Audit Event Details</h3>
          <div className={styles.modalActions}>
            <button className={styles.closeBtn} onClick={onClose} aria-label="Close">&times;</button>
          </div>
        </div>
        <div className={styles.modalBody}>
          <div className={styles.metaGrid}>
            <span className={styles.metaLabel}>Timestamp</span>
            <span className={styles.metaValue}>{event.createdAt ? new Date(event.createdAt).toLocaleString() : '\u2014'}</span>
            <span className={styles.metaLabel}>Event Type</span>
            <span className={styles.metaValue}>{event.eventType ?? '\u2014'}</span>
            <span className={styles.metaLabel}>Source</span>
            <span className={styles.metaValue}>{event.sourceService ?? '\u2014'}</span>
            <span className={styles.metaLabel}>Action</span>
            <span className={styles.metaValue}>{event.action ?? '\u2014'}</span>
            <span className={styles.metaLabel}>Resource</span>
            <span className={styles.metaValue}>{event.resourceId ?? '\u2014'}</span>
            <span className={styles.metaLabel}>Outcome</span>
            <span className={styles.metaValue}>{event.outcome ?? '\u2014'}</span>
          </div>

          {prettyDetails != null && (
            <>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <span className={styles.sectionLabel}>Details</span>
                <CopyButton text={prettyDetails} />
              </div>
              <div className={styles.jsonBlock}>
                <pre>{prettyDetails}</pre>
              </div>
            </>
          )}

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '1rem 0 0.5rem' }}>
            <span className={styles.sectionLabel}>Raw Event</span>
            <CopyButton text={rawEvent} />
          </div>
          <div className={styles.jsonBlock}>
            <pre>{rawEvent}</pre>
          </div>
        </div>
      </div>
    </div>
  )
}

export function AuditLog() {
  const api = auditLogApi(useAuthRequest())
  const [events, setEvents] = useState([])
  const [total, setTotal] = useState(0)
  const [page] = useState(0)
  const [size, setSize] = useState(50)
  const [eventType, setEventType] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState(null)

  const load = () => {
    setLoading(true)
    api
      .list(page, size, eventType)
      .then(data => {
        setEvents(data?.items ?? [])
        setTotal(data?.total ?? 0)
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }
  useEffect(() => { load() }, [size, eventType])

  const detailsPreview = e => {
    if (e.details == null) return null
    const raw = typeof e.details === 'object' ? JSON.stringify(e.details) : String(e.details)
    return raw.length > 80 ? raw.slice(0, 80) + '\u2026' : raw
  }

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
          {loading && (
            <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: '2rem' }}>Loading…</td></tr>
          )}
          {!loading && events.length === 0 && !error && (
            <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: '2rem' }}>No audit events in the last 24 hours</td></tr>
          )}
          {events.map((e, i) => {
            const preview = detailsPreview(e)
            return (
              <tr key={i}>
                <td className={styles.mono}>{e.createdAt ? new Date(e.createdAt).toLocaleString() : '\u2014'}</td>
                <td>{e.eventType}</td>
                <td className={styles.mono}>{e.sourceService ?? '\u2014'}</td>
                <td>{e.action ?? '\u2014'}</td>
                <td className={styles.mono}>{e.resourceId ?? '\u2014'}</td>
                <td>{e.outcome ?? '\u2014'}</td>
                <td>
                  {preview != null
                    ? <span className={styles.detailsLink} onClick={() => setSelected(e)}>{preview}</span>
                    : <span className={styles.details}>\u2014</span>
                  }
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {selected && <DetailsModal event={selected} onClose={() => setSelected(null)} />}
    </div>
  )
}
