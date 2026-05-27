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

function FlagDetailModal({ flag, onClose, onStatusChange, api }) {
  const meta = parseMeta(flag.metadata)
  const [status, setStatus] = useState(flag.signalStatus ?? 'NEW')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const [showReply, setShowReply] = useState(false)
  const [replyText, setReplyText] = useState('')
  const [replyTarget, setReplyTarget] = useState('GROUP')
  const [replyToOriginal, setReplyToOriginal] = useState(true)
  const [prefixModerator, setPrefixModerator] = useState(false)
  const [replySending, setReplySending] = useState(false)
  const [replyError, setReplyError] = useState('')
  const [replySuccess, setReplySuccess] = useState(false)
  const [accounts, setAccounts] = useState(null)
  const [selectedAccountId, setSelectedAccountId] = useState(null)
  const [promptActioned, setPromptActioned] = useState(false)

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

  const handleReply = async () => {
    setReplySending(true)
    setReplyError('')
    setReplySuccess(false)
    try {
      await api.reply(flag.id, {
        text: replyText,
        target: replyTarget,
        replyToOriginal,
        prefixModerator,
        accountId: selectedAccountId,
      })
      setReplySuccess(true)
      setPromptActioned(true)
      setReplyText('')
    } catch (e) {
      if (e.status === 409 && e.body?.accounts) {
        setAccounts(e.body.accounts)
        setReplyError('Multiple accounts watch this chat \u2014 select one below.')
      } else {
        setReplyError(e.message || 'Failed to send reply')
      }
    } finally {
      setReplySending(false)
    }
  }

  const handleMarkActioned = async () => {
    try {
      await onStatusChange(flag.id, 'ACTIONED')
      setStatus('ACTIONED')
      setPromptActioned(false)
    } catch (e) {
      setReplyError(e.message)
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

          <button className={styles.replyToggle} onClick={() => setShowReply(s => !s)}>
            {showReply ? '\u25BE Reply' : '\u25B8 Reply'}
          </button>

          {showReply && (
            <div className={styles.replySection}>
              <textarea
                className={styles.replyTextarea}
                placeholder="Type your response..."
                value={replyText}
                onChange={e => setReplyText(e.target.value)}
                maxLength={4096}
              />

              <div className={styles.replyOptions}>
                <div className={styles.targetToggle}>
                  <button
                    className={`${styles.targetBtn}${replyTarget === 'GROUP' ? ' ' + styles.active : ''}`}
                    onClick={() => setReplyTarget('GROUP')}
                  >Group</button>
                  <button
                    className={`${styles.targetBtn}${replyTarget === 'DM' ? ' ' + styles.active : ''}`}
                    onClick={() => setReplyTarget('DM')}
                  >DM</button>
                </div>
                <label>
                  <input type="checkbox" checked={replyToOriginal} onChange={e => setReplyToOriginal(e.target.checked)} />
                  Reply to original
                </label>
                <label>
                  <input type="checkbox" checked={prefixModerator} onChange={e => setPrefixModerator(e.target.checked)} />
                  Prefix [Moderator]
                </label>
              </div>

              {accounts && (
                <select
                  className={styles.accountSelect}
                  value={selectedAccountId ?? ''}
                  onChange={e => setSelectedAccountId(e.target.value || null)}
                >
                  <option value="">Select account...</option>
                  {accounts.map(a => (
                    <option key={a.id} value={a.id}>{a.displayName} ({a.phoneNumber})</option>
                  ))}
                </select>
              )}

              <div className={styles.replyActions}>
                <Button onClick={handleReply} disabled={replySending || !replyText.trim()}>
                  {replySending ? 'Sending...' : 'Send'}
                </Button>
                {replySuccess && !promptActioned && (
                  <span className={styles.replySuccess}>Sent!</span>
                )}
                {promptActioned && (
                  <>
                    <span className={styles.replySuccess}>Sent! Mark as actioned?</span>
                    <Button variant="secondary" onClick={handleMarkActioned}>Yes</Button>
                    <Button variant="secondary" onClick={() => setPromptActioned(false)}>No</Button>
                  </>
                )}
              </div>

              {replyError && <p className={styles.replyError}>{replyError}</p>}
            </div>
          )}
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
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState(null)

  const load = () => {
    setLoading(true)
    api
      .list(page, size, decision)
      .then(data => {
        setFlags(data?.items ?? [])
        setTotal(data?.total ?? 0)
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }

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
          {loading && (
            <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: '2rem' }}>Loading…</td></tr>
          )}
          {!loading && flags.length === 0 && !error && (
            <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: '2rem' }}>No flags yet</td></tr>
          )}
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
          api={api}
        />
      )}
    </div>
  )
}
