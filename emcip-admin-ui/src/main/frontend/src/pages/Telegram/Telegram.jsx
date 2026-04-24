import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { telegramApi } from '../../api/telegram'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import styles from './Telegram.module.css'

const STATUS_VARIANT = { CONNECTED: 'green', PENDING: 'yellow', DISCONNECTED: 'red' }

export function Telegram() {
  const { token } = useAuth()
  const api = telegramApi(makeRequest(token))

  const [status, setStatus] = useState({ status: 'DISCONNECTED', message: '', phoneNumber: '' })
  const [config, setConfig] = useState({ phoneNumber: '', apiId: '', apiHash: '', sessionStringSet: false })
  const [sessionInput, setSessionInput] = useState('')
  const [showSession, setShowSession] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [error, setError] = useState('')

  const loadStatus = () =>
    api.getStatus().then(setStatus).catch(e => setError(e.message))

  const loadConfig = () =>
    api.getConfig().then(setConfig).catch(e => setError(e.message))

  useEffect(() => {
    loadStatus()
    loadConfig()
  }, [])

  const handleSave = async () => {
    setFeedback('')
    setError('')
    try {
      const payload = {
        phoneNumber: config.phoneNumber,
        apiId: config.apiId ? parseInt(config.apiId, 10) : undefined,
        apiHash: config.apiHash,
      }
      if (sessionInput.trim()) payload.sessionString = sessionInput.trim()
      await api.saveConfig(payload)
      setFeedback('Saved')
      setSessionInput('')
      loadConfig()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleReconnect = async () => {
    setFeedback('')
    setError('')
    try {
      const res = await api.reconnect()
      setFeedback(res.accepted ? 'Reconnect triggered' : `Failed: ${res.reason}`)
      setTimeout(loadStatus, 1500)
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className={styles.page}>
      <h2>Telegram</h2>

      {/* Connection Status */}
      <div className={styles.card}>
        <h3 className={styles.cardTitle}>Connection Status</h3>
        <div className={styles.statusRow}>
          <Badge variant={STATUS_VARIANT[status.status] ?? 'gray'}>{status.status}</Badge>
          {status.phoneNumber && <span className={styles.message}>{status.phoneNumber}</span>}
          <span className={styles.message}>{status.message}</span>
          <Button variant="secondary" onClick={handleReconnect}>Reconnect</Button>
        </div>
      </div>

      {/* Credentials */}
      <div className={styles.card}>
        <h3 className={styles.cardTitle}>Credentials</h3>
        {error && <p className={styles.error} role="alert">{error}</p>}
        <div className={styles.form}>
          <div>
            <label className={styles.label}>Phone Number</label>
            <input
              type="text"
              className={styles.input}
              value={config.phoneNumber}
              onChange={e => setConfig(c => ({ ...c, phoneNumber: e.target.value }))}
              placeholder="+49123456789"
            />
          </div>
          <div>
            <label className={styles.label}>API ID</label>
            <input
              type="number"
              className={styles.input}
              value={config.apiId}
              onChange={e => setConfig(c => ({ ...c, apiId: e.target.value }))}
            />
          </div>
          <div>
            <label className={styles.label}>API Hash</label>
            <input
              type="text"
              className={`${styles.input} ${styles.mono}`}
              value={config.apiHash}
              onChange={e => setConfig(c => ({ ...c, apiHash: e.target.value }))}
            />
          </div>
          <div>
            <button
              type="button"
              className={styles.sessionToggle}
              onClick={() => setShowSession(s => !s)}
            >
              {showSession ? '▲ Hide' : '▼ Session String'}{config.sessionStringSet ? ' (set)' : ' (not set)'}
            </button>
            {showSession && (
              <textarea
                className={`${styles.input} ${styles.mono}`}
                rows={4}
                value={sessionInput}
                onChange={e => setSessionInput(e.target.value)}
                placeholder="Paste new session string here to update..."
              />
            )}
          </div>

          <div className={styles.actions}>
            <Button onClick={handleSave}>Save</Button>
          </div>
          {feedback && <p className={styles.feedback}>{feedback}</p>}

          <div className={styles.disabledSection}>
            <p className={styles.disabledLabel}>Live auth flow (coming in next phase)</p>
            <div className={styles.actions}>
              <Button disabled title="Coming in next phase">Request Auth Code</Button>
              <Button disabled title="Coming in next phase">Submit Code</Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
