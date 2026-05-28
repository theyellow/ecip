import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { auditLogApi } from '../../api/auditLog'
import { Badge } from '../../components/Badge/Badge'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import styles from './AuditLog.module.css'

const EVENT_TYPES = ['', 'MESSAGE_RECEIVED', 'MESSAGE_CLASSIFIED', 'POLICY_DECISION', 'MODERATION_ACTION']
const OUTCOME_VARIANT = { OK: 'green', BLOCK: 'red' }

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
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}

function parseDetails(raw) {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) } catch { return raw }
}

function detailsPreview(e) {
  if (e.details == null) return null
  const raw = typeof e.details === 'object' ? JSON.stringify(e.details) : String(e.details)
  return raw.length > 80 ? raw.slice(0, 80) + '\u2026' : raw
}

function DetailsModal({ event, onClose }) {
  const parsedDetails = parseDetails(event.details)
  const prettyDetails = parsedDetails != null ? JSON.stringify(parsedDetails, null, 2) : null
  const rawEvent = JSON.stringify(event, null, 2)

  return (
    <Modal title="Audit Event Details" onClose={onClose}>
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
          <SectionLabel aside={<CopyButton text={prettyDetails} />}>Details</SectionLabel>
          <div className={styles.jsonBlock}>
            <pre>{prettyDetails}</pre>
          </div>
        </>
      )}

      <SectionLabel aside={<CopyButton text={rawEvent} />}>Raw Event</SectionLabel>
      <div className={styles.jsonBlock}>
        <pre>{rawEvent}</pre>
      </div>
    </Modal>
  )
}

const COLUMNS = [
  { key: 'createdAt', label: 'Timestamp', mono: true, width: 170, render: v => v ? new Date(v).toLocaleString() : '\u2014' },
  { key: 'eventType', label: 'Event Type' },
  { key: 'sourceService', label: 'Source', mono: true, render: v => v ?? '\u2014' },
  { key: 'action', label: 'Action', render: v => v ?? '\u2014' },
  { key: 'resourceId', label: 'Resource', mono: true, render: v => v ?? '\u2014' },
  { key: 'outcome', label: 'Outcome', width: 100, render: v => v ? <Badge variant={OUTCOME_VARIANT[v] ?? 'gray'}>{v}</Badge> : '\u2014' },
]

export function AuditLog() {
  const api = auditLogApi(useAuthRequest())
  const [events, setEvents] = useState([])
  const [total, setTotal] = useState(0)
  const [page] = useState(0)
  const [size, setSize] = useState(50)
  const [eventType, setEventType] = useState('')
  const [error, setError] = useState('')
  const [selected, setSelected] = useState(null)

  const load = () => {
    api
      .list(page, size, eventType)
      .then(data => {
        setEvents(data?.items ?? [])
        setTotal(data?.total ?? 0)
      })
      .catch(e => setError(e.message))
  }
  useEffect(() => { load() }, [size, eventType])

  const detailsColumn = {
    key: 'details',
    label: 'Details',
    render: (v, row) => {
      const preview = detailsPreview(row)
      return preview != null
        ? <span className={styles.detailsLink} onClick={e => { e.stopPropagation(); setSelected(row) }}>{preview}</span>
        : '\u2014'
    },
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Audit Log"
        systemId={`\u25CE audit-service \u00b7 ${total} events total`}
        columns={[...COLUMNS, detailsColumn]}
        rows={events}
        rowKey={(r, i) => i}
        onEdit={setSelected}
        filters={[
          {
            value: eventType,
            onChange: e => setEventType(e.target.value),
            options: EVENT_TYPES.map(t => ({ value: t, label: t || 'All types' })),
          },
          {
            value: String(size),
            onChange: e => setSize(Number(e.target.value)),
            options: [25, 50, 100, 200].map(n => ({ value: String(n), label: String(n) })),
          },
        ]}
        emptyText="No audit events found"
      />

      {selected && <DetailsModal event={selected} onClose={() => setSelected(null)} />}
    </>
  )
}
