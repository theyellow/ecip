import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { auditLogApi } from '../../api/auditLog'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import styles from './AuditLog.module.css'

const EVENT_TYPES = ['', 'MESSAGE_RECEIVED', 'MESSAGE_CLASSIFIED', 'POLICY_DECISION', 'MODERATION_ACTION']
const OUTCOME_VARIANT = { OK: 'green', BLOCK: 'red' }

const TIME_PRESETS = [
  { value: '24h', label: 'Last 24 hours' },
  { value: '10m', label: 'Last 10 min' },
  { value: '1h', label: 'Last hour' },
  { value: '8h', label: 'Last 8 hours' },
  { value: '48h', label: 'Last 48 hours' },
  { value: '72h', label: 'Last 72 hours' },
  { value: 'custom', label: 'Custom range\u2026' },
  { value: '', label: 'All time' },
]

const PRESET_MS = {
  '10m': 10 * 60 * 1000,
  '1h': 60 * 60 * 1000,
  '8h': 8 * 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '48h': 48 * 60 * 60 * 1000,
  '72h': 72 * 60 * 60 * 1000,
}

function presetToRange(preset) {
  const ms = PRESET_MS[preset]
  if (ms) {
    return { from: new Date(Date.now() - ms).toISOString(), to: null }
  }
  return { from: null, to: null }
}

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

export function AuditLog() {
  const api = auditLogApi(useAuthRequest())
  const [events, setEvents] = useState([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState(null)

  const [filters, setFilters] = useState({
    page: 0,
    size: 50,
    eventType: '',
    actionFilter: '',
    timePreset: '24h',
    customFrom: '',
    customTo: '',
  })

  const setFilter = (key, value) =>
    setFilters(f => ({ ...f, [key]: value, ...(key !== 'page' ? { page: 0 } : {}) }))

  useEffect(() => {
    const { page, size, eventType, timePreset, customFrom, customTo } = filters
    const computedRange =
      timePreset === 'custom'
        ? {
            from: customFrom ? new Date(customFrom).toISOString() : null,
            to: customTo ? new Date(customTo).toISOString() : null,
          }
        : presetToRange(timePreset)

    setLoading(true)
    api
      .list(page, size, eventType, computedRange.from, computedRange.to)
      .then(data => {
        setEvents(data?.items ?? [])
        setTotal(data?.total ?? 0)
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [filters])

  const displayEvents = filters.actionFilter
    ? events.filter(e => e.action === filters.actionFilter)
    : events

  const totalPages = Math.max(1, Math.ceil(total / filters.size))

  return (
    <>
      <div className={styles.pageHeader}>
        <div>
          <h2>Audit Log</h2>
          <div className={styles.systemId}>{'\u25CE'} audit-service {'\u00b7'} {total} events</div>
        </div>
        <div className={styles.filters}>
          <select value={filters.eventType} onChange={e => setFilter('eventType', e.target.value)} className={styles.select}>
            {EVENT_TYPES.map(t => <option key={t} value={t}>{t || 'All types'}</option>)}
          </select>
          <select value={String(filters.size)} onChange={e => setFilter('size', Number(e.target.value))} className={styles.select}>
            {[10, 25, 50, 100, 200].map(n => <option key={n} value={String(n)}>{n}</option>)}
          </select>
        </div>
      </div>

      <div className={styles.filterRow}>
        <select value={filters.timePreset} onChange={e => setFilter('timePreset', e.target.value)} className={styles.select}>
          {TIME_PRESETS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        {filters.timePreset === 'custom' && <>
          <input type="datetime-local" className={styles.filterInput} value={filters.customFrom} onChange={e => setFilter('customFrom', e.target.value)} />
          <input type="datetime-local" className={styles.filterInput} value={filters.customTo} onChange={e => setFilter('customTo', e.target.value)} />
        </>}
        <select value={filters.actionFilter} onChange={e => setFilter('actionFilter', e.target.value)} className={styles.select}>
          <option value="">All actions</option>
          <option value="CLASSIFY">CLASSIFY</option>
          <option value="POLICY_DECISION">POLICY_DECISION</option>
          <option value="MODERATION_ACTION">MODERATION_ACTION</option>
          <option value="SEND_MESSAGE">SEND_MESSAGE</option>
        </select>
      </div>

      {error && (
        <p role="alert" className={styles.alertBanner}>{error}</p>
      )}

      <div className={styles.tableWrapper}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Event Type</th>
              <th>Action</th>
              <th>Resource</th>
              <th>Outcome</th>
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--fg-3)', padding: 'var(--sp-5)' }}>Loading{'\u2026'}</td></tr>
            )}
            {!loading && displayEvents.length === 0 && !error && (
              <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--fg-3)', padding: 'var(--sp-5)' }}>No audit events found</td></tr>
            )}
            {!loading && displayEvents.map((e, i) => (
              <tr key={e.eventId ?? i} className={styles.clickableRow} onClick={() => setSelected(e)}>
                <td className={styles.mono}>{e.createdAt ? new Date(e.createdAt).toLocaleString() : '\u2014'}</td>
                <td>{e.eventType ?? '\u2014'}</td>
                <td>{e.action ?? '\u2014'}</td>
                <td className={styles.mono}>{e.resourceId ? e.resourceId.slice(0, 8) + '\u2026' : '\u2014'}</td>
                <td>{e.outcome ? <Badge variant={OUTCOME_VARIANT[e.outcome] ?? 'gray'}>{e.outcome}</Badge> : '\u2014'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className={styles.pagination}>
        <Button variant="secondary" disabled={filters.page === 0} onClick={() => setFilters(f => ({ ...f, page: f.page - 1 }))}>
          {'\u2190'} Prev
        </Button>
        <span>Page {filters.page + 1} of {totalPages} {'\u00a0\u00b7\u00a0'} {total} total</span>
        <Button variant="secondary" disabled={filters.page + 1 >= totalPages} onClick={() => setFilters(f => ({ ...f, page: f.page + 1 }))}>
          Next {'\u2192'}
        </Button>
      </div>

      {selected && <DetailsModal event={selected} onClose={() => setSelected(null)} />}
    </>
  )
}
