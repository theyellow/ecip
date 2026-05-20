import { useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { simulateApi } from '../../api/simulate'
import { Button } from '../../components/Button/Button'
import styles from './Simulate.module.css'

export function Simulate() {
  const api = simulateApi(useAuthRequest())
  const [form, setForm] = useState({ chatId: '', senderId: 'sim-user', senderType: 'USER', text: '' })
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  const publish = async () => {
    if (!form.chatId || !form.text) { setError('Chat ID and Message Text are required'); return }
    setError(''); setResult(null); setLoading(true)
    try {
      const res = await api.publish({ ...form, chatId: parseInt(form.chatId, 10) })
      setResult(res)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.container}>
      <h2>Simulate Event</h2>
      <p className={styles.subtitle}>Publish a test message into the processing pipeline.</p>
      <div className={styles.card}>
        <div className={styles.field}>
          <label htmlFor="chatId">Chat ID *</label>
          <input id="chatId" type="number" value={form.chatId}
            onChange={e => set('chatId', e.target.value)} className={styles.input} />
        </div>
        <div className={styles.field}>
          <label htmlFor="senderId">Sender ID</label>
          <input id="senderId" type="text" value={form.senderId}
            onChange={e => set('senderId', e.target.value)} className={styles.input} />
        </div>
        <div className={styles.field}>
          <label htmlFor="senderType">Sender Type</label>
          <select id="senderType" value={form.senderType}
            onChange={e => set('senderType', e.target.value)} className={styles.input}>
            {['USER', 'BOT', 'ADMIN'].map(t => <option key={t}>{t}</option>)}
          </select>
        </div>
        <div className={styles.field}>
          <label htmlFor="text">Message Text *</label>
          <textarea id="text" value={form.text} onChange={e => set('text', e.target.value)}
            className={styles.input} rows={4} />
        </div>
        {error && <p className={styles.error} role="alert">{error}</p>}
        <Button onClick={publish} disabled={loading}>
          {loading ? 'Publishing\u2026' : '\u25b6 Publish Message'}
        </Button>
        {result && (
          <div className={styles.result}>
            <p className={styles.success}>Published successfully</p>
            <pre>{JSON.stringify(result, null, 2)}</pre>
          </div>
        )}
      </div>
      <div className={styles.pipeline}>
        <h3>Pipeline Flow</h3>
        <ol>
          <li><code>telegram.raw.messages</code> \u2192 LLM Orchestrator classifies intent</li>
          <li><code>messages.classified</code> \u2192 Policy Engine evaluates rules</li>
          <li><code>policies.decisions</code> \u2192 Moderation Service applies action</li>
          <li>All steps recorded in Audit Log</li>
        </ol>
      </div>
    </div>
  )
}
