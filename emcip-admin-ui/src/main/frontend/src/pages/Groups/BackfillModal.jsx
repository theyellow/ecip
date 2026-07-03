import { useEffect, useRef, useState } from 'react'
import { Modal } from '../../components/Modal/Modal'
import { Button } from '../../components/Button/Button'
import styles from './BackfillModal.module.css'

const PRESETS = [
  { label: 'Last 7 days', days: 7 },
  { label: 'Last 30 days', days: 30 },
  { label: 'Last 3 months', days: 90 },
  { label: 'Last 6 months', days: 180 },
  { label: 'Last year', days: 365 },
]

function presetToFromDate(days) {
  const d = new Date()
  d.setDate(d.getDate() - days)
  d.setHours(0, 0, 0, 0)
  return d.toISOString()
}

export function BackfillModal({ group, onClose, api }) {
  const [watchers, setWatchers] = useState([])
  const [selectedAccountId, setSelectedAccountId] = useState('')
  const [selectedPreset, setSelectedPreset] = useState(null)
  const [customDate, setCustomDate] = useState('')
  const [phase, setPhase] = useState('config')
  const [backfillId, setBackfillId] = useState(null)
  const [processed, setProcessed] = useState(0)
  const [errorMsg, setErrorMsg] = useState('')

  useEffect(() => {
    api
      .watchers(group.telegramChatId)
      .then(setWatchers)
      .catch(() => setWatchers([]))
  }, [group.telegramChatId])

  const pollCountRef = useRef(0)
  const MAX_POLLS = 900 // 30 minutes at 2s intervals

  useEffect(() => {
    if (phase !== 'polling' || !backfillId) return

    pollCountRef.current = 0

    const interval = setInterval(async () => {
      pollCountRef.current += 1
      if (pollCountRef.current > MAX_POLLS) {
        clearInterval(interval)
        setErrorMsg('Timed out waiting for backfill to complete.')
        setPhase('error')
        return
      }
      try {
        const s = await api.backfillStatus(group.telegramChatId, backfillId)
        setProcessed(s.processed ?? 0)
        if (s.status === 'COMPLETED') {
          setPhase('done')
        } else if (s.status === 'FAILED') {
          setErrorMsg(s.errorMessage || 'Backfill failed.')
          setPhase('error')
        }
      } catch (e) {
        setErrorMsg(e.message || 'Failed to fetch status.')
        setPhase('error')
      }
    }, 2000)

    return () => clearInterval(interval)
  }, [phase, backfillId, group.telegramChatId])

  const resolvedFromDate =
    selectedPreset != null
      ? presetToFromDate(selectedPreset)
      : customDate
        ? new Date(customDate).toISOString()
        : null

  const canSubmit =
    watchers.length > 0 &&
    selectedAccountId !== '' &&
    resolvedFromDate != null &&
    phase === 'config'

  async function handleSubmit() {
    try {
      const body = {
        accountId: selectedAccountId,
        fromDate: resolvedFromDate,
      }
      if (group.tenantId) {
        body.tenantId = group.tenantId
      }
      const result = await api.backfill(group.telegramChatId, body)
      setBackfillId(result.backfillId)
      setPhase('polling')
    } catch (e) {
      setErrorMsg(e.message || 'Failed to start backfill.')
      setPhase('error')
    }
  }

  // During polling, prevent accidental close — pass a no-op so Modal's Escape
  // handler and overlay click don't throw on undefined.
  const handleClose = phase === 'polling' ? () => {} : onClose

  return (
    <Modal title={`Backfill \u00b7 ${group.name}`} onClose={handleClose}>
      {phase === 'config' && (
        <>
          {watchers.length === 0 ? (
            <p className={styles.emptyWatchers}>
              No watcher accounts are connected to this group.
            </p>
          ) : (
            <div className={styles.field}>
              <label>Watcher Account</label>
              <select
                className={styles.select}
                value={selectedAccountId}
                onChange={e => setSelectedAccountId(e.target.value)}
              >
                <option value="">Select account\u2026</option>
                {watchers.map(w => (
                  <option key={w.accountId} value={w.accountId}>
                    {w.displayName || w.phoneNumber}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className={styles.field}>
            <label>Date Range</label>
            <div className={styles.chipRow}>
              {PRESETS.map(p => (
                <button
                  key={p.days}
                  type="button"
                  className={`${styles.chip}${selectedPreset === p.days ? ` ${styles.chipActive}` : ''}`}
                  onClick={() => {
                    setSelectedPreset(p.days)
                    setCustomDate('')
                  }}
                >
                  {p.label}
                </button>
              ))}
            </div>
            <input
              type="date"
              className={styles.dateInput}
              value={customDate}
              onChange={e => {
                setCustomDate(e.target.value)
                setSelectedPreset(null)
              }}
              aria-label="Custom start date"
            />
          </div>

          <div className={styles.footer}>
            <Button variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button variant="primary" disabled={!canSubmit} onClick={handleSubmit}>
              Start Backfill
            </Button>
          </div>
        </>
      )}

      {phase === 'polling' && (
        <div className={styles.status}>
          <div className={styles.spinner} aria-hidden="true" />
          <span>
            Processing\u2026 {processed} messages ingested
          </span>
        </div>
      )}

      {phase === 'done' && (
        <>
          <p className={styles.done}>
            Done \u2014 {processed} messages ingested.
          </p>
          <div className={styles.footer}>
            <Button variant="secondary" onClick={onClose}>
              Close
            </Button>
          </div>
        </>
      )}

      {phase === 'error' && (
        <>
          <p className={styles.error}>{errorMsg}</p>
          <div className={styles.footer}>
            <Button variant="secondary" onClick={onClose}>
              Close
            </Button>
          </div>
        </>
      )}
    </Modal>
  )
}
