import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { flagsApi } from '../../api/flags'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import { ReplyComposer } from './ReplyComposer'
import styles from './Flags.module.css'

const DECISIONS = ['', 'ALLOW', 'BLOCK', 'FLAG', 'RESPOND', 'ESCALATE', 'REVIEW', 'EXECUTE']
const STATUSES = ['NEW', 'REVIEWED', 'ACTIONED']
const CONFIDENCE_OPTIONS = [
  { value: '', label: 'Any confidence' },
  { value: '0.5', label: '\u2265 50%' },
  { value: '0.7', label: '\u2265 70%' },
  { value: '0.9', label: '\u2265 90%' },
]
const TIME_PRESETS = [
  { value: '', label: 'All time' },
  { value: 'today', label: 'Today' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
  { value: 'thismonth', label: 'This month' },
  { value: 'lastmonth', label: 'Last month' },
  { value: 'custom', label: 'Custom range\u2026' },
]

const DECISION_VARIANT = {
  ALLOW: 'green',
  FLAG: 'blue',
  BLOCK: 'red',
  RESPOND: 'gray',
  ESCALATE: 'yellow',
  REVIEW: 'yellow',
  EXECUTE: 'red',
}

const STATUS_VARIANT = { NEW: 'blue', REVIEWED: 'gray', ACTIONED: 'green' }

function presetToRange(preset) {
  const now = new Date()
  if (preset === 'today') {
    const start = new Date(now)
    start.setHours(0, 0, 0, 0)
    return { from: start.toISOString(), to: null }
  }
  if (preset === '7d') return { from: new Date(now - 7 * 86400000).toISOString(), to: null }
  if (preset === '30d') return { from: new Date(now - 30 * 86400000).toISOString(), to: null }
  if (preset === 'thismonth') {
    return { from: new Date(now.getFullYear(), now.getMonth(), 1).toISOString(), to: null }
  }
  if (preset === 'lastmonth') {
    return {
      from: new Date(now.getFullYear(), now.getMonth() - 1, 1).toISOString(),
      to: new Date(now.getFullYear(), now.getMonth(), 1).toISOString(),
    }
  }
  return { from: null, to: null }
}

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

  const [showResearch, setShowResearch] = useState(false)
  const [chatMessages, setChatMessages] = useState([])
  const [chatInput, setChatInput] = useState('')
  const [chatLoading, setChatLoading] = useState(false)
  const [chatError, setChatError] = useState(null)

  const [showReply, setShowReply] = useState(false)

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

  const buildFirstMessage = () => {
    const parts = [`Analyse this moderation flag:`]
    parts.push(`- Intent: ${flag.originalIntent || 'unknown'}`)
    parts.push(`- Decision: ${flag.decision || 'unknown'}`)
    parts.push(`- Confidence: ${flag.confidence != null ? (flag.confidence * 100).toFixed(1) + '%' : 'unknown'}`)
    parts.push(`- Reason: ${flag.reason || 'none'}`)
    if (meta.messageText) parts.push(`- Message: ${meta.messageText}`)
    parts.push('', 'Is the decision appropriate? Explain briefly and suggest any better action if relevant.')
    return parts.join('\n')
  }

  const sendChat = async (newMessages) => {
    setChatMessages(newMessages)
    setChatLoading(true)
    setChatError(null)
    try {
      const res = await api.chat(flag.id, newMessages)
      setChatMessages(prev => [...prev, { role: 'assistant', content: res.content, model: res.model }])
    } catch (e) {
      setChatError(e.message || 'Chat failed')
    } finally {
      setChatLoading(false)
    }
  }

  const handleAnalyse = () => {
    const userMsg = { role: 'user', content: buildFirstMessage() }
    sendChat([userMsg])
  }

  const handleChatSend = () => {
    if (!chatInput.trim()) return
    const userMsg = { role: 'user', content: chatInput.trim() }
    setChatInput('')
    sendChat([...chatMessages, userMsg])
  }

  const handleChatKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleChatSend()
    }
  }

  const copyMessage = (content) => {
    navigator.clipboard.writeText(content)
  }

  const clearChat = () => {
    setChatMessages([])
    setChatError(null)
    setChatInput('')
  }

  return (
    <Modal title="Decision Detail" onClose={onClose}>
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

      {error && (
        <p role="alert" className={styles.alertBanner}>{error}</p>
      )}

      <div className={styles.replyHeader} onClick={() => setShowReply(s => !s)}>
        <SectionLabel aside={showReply ? '\u25BE' : '\u25B8'}>Reply</SectionLabel>
      </div>

      {showReply && (
        <ReplyComposer
          flagId={flag.id}
          api={api}
          onActioned={() => onStatusChange(flag.id, 'ACTIONED')}
        />
      )}

      <div className={styles.replyHeader} onClick={() => setShowResearch(s => !s)}>
        <SectionLabel aside={showResearch ? '\u25BE' : '\u25B8'}>AI Research</SectionLabel>
      </div>

      {showResearch && (
        <div className={styles.replySection}>
          {chatMessages.length === 0 && (
            <div className={styles.replyActions}>
              <Button variant="secondary" onClick={handleAnalyse} disabled={chatLoading}>
                Analyse
              </Button>
            </div>
          )}

          {chatMessages.length > 0 && (
            <div className={styles.chatMessages}>
              {chatMessages.map((msg, i) => (
                <div
                  key={i}
                  className={`${styles.chatMessage} ${msg.role === 'user' ? styles.chatMessageUser : styles.chatMessageAssistant}`}
                >
                  <div className={styles.chatMessageMeta}>
                    <span className={styles.chatMessageRole}>{msg.role === 'user' ? 'You' : 'Assistant'}</span>
                    {msg.role === 'assistant' && msg.model && (
                      <span className={styles.analysisModel}>{msg.model}</span>
                    )}
                    {msg.role === 'assistant' && (
                      <button className={styles.copyAnalysisBtn} onClick={() => copyMessage(msg.content)}>
                        Copy
                      </button>
                    )}
                  </div>
                  <div className={styles.chatMessageContent}>{msg.content}</div>
                </div>
              ))}
              {chatLoading && <div className={styles.chatThinking}>Thinking{'\u2026'}</div>}
            </div>
          )}

          {chatError && <p role="alert" className={styles.alertBanner}>{chatError}</p>}

          {chatMessages.length > 0 && (
            <>
              <div className={styles.chatInputRow}>
                <textarea
                  className={styles.chatInput}
                  placeholder="Ask a follow-up question..."
                  value={chatInput}
                  onChange={e => setChatInput(e.target.value)}
                  onKeyDown={handleChatKeyDown}
                  disabled={chatLoading}
                  rows={2}
                />
                <Button onClick={handleChatSend} disabled={chatLoading || !chatInput.trim()}>
                  Send
                </Button>
              </div>
              <div className={styles.replyActions}>
                <Button variant="secondary" onClick={clearChat} disabled={chatLoading}>
                  Clear
                </Button>
              </div>
            </>
          )}
        </div>
      )}
    </Modal>
  )
}

export function Decisions() {
  const api = flagsApi(useAuthRequest())
  const [flags, setFlags] = useState([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState(null)

  const [filters, setFilters] = useState({
    page: 0,
    size: 50,
    decision: '',
    intent: '',
    minConfidence: '',
    timePreset: '',
    customFrom: '',
    customTo: '',
  })

  // Helper: update a filter and reset page to 0 atomically (except when navigating pages directly)
  const setFilter = (key, value) =>
    setFilters(f => ({ ...f, [key]: value, ...(key !== 'page' ? { page: 0 } : {}) }))

  useEffect(() => {
    const { page, size, decision, intent, minConfidence, timePreset, customFrom, customTo } = filters
    const computedRange =
      timePreset === 'custom'
        ? {
            from: customFrom ? new Date(customFrom).toISOString() : null,
            to: customTo ? new Date(customTo).toISOString() : null,
          }
        : presetToRange(timePreset)

    setLoading(true)
    api
      .list(
        page,
        size,
        decision,
        intent,
        computedRange.from,
        computedRange.to,
        minConfidence ? Number(minConfidence) : null,
      )
      .then(data => {
        setFlags(data?.items ?? [])
        setTotal(data?.total ?? 0)
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [filters])

  const updateStatus = async (id, status) => {
    await api.updateStatus(id, status)
    setFlags(prev => prev.map(f => f.id === id ? { ...f, signalStatus: status } : f))
    setSelected(prev => prev?.id === id ? { ...prev, signalStatus: status } : prev)
  }

  const totalPages = Math.max(1, Math.ceil(total / filters.size))

  return (
    <>
      <div className={styles.pageHeader}>
        <div>
          <h2>Decisions</h2>
          <div className={styles.systemId}>{'\u2691'} policy-engine {'\u00b7'} {total} decisions</div>
        </div>
        <div className={styles.filters}>
          <select value={filters.decision} onChange={e => setFilter('decision', e.target.value)} className={styles.select}>
            {DECISIONS.map(d => <option key={d} value={d}>{d || 'All decisions'}</option>)}
          </select>
          <select value={filters.size} onChange={e => setFilter('size', Number(e.target.value))} className={styles.select}>
            {[25, 50, 100, 200].map(n => <option key={n}>{n}</option>)}
          </select>
        </div>
      </div>

      <div className={styles.filterRow}>
        <input
          type="text"
          className={styles.filterInput}
          placeholder="Intent (e.g. SPAM)"
          value={filters.intent}
          onChange={e => setFilter('intent', e.target.value)}
        />
        <select value={filters.minConfidence} onChange={e => setFilter('minConfidence', e.target.value)} className={styles.select}>
          {CONFIDENCE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <select value={filters.timePreset} onChange={e => setFilter('timePreset', e.target.value)} className={styles.select}>
          {TIME_PRESETS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        {filters.timePreset === 'custom' && <>
          <input type="datetime-local" className={styles.filterInput} value={filters.customFrom} onChange={e => setFilter('customFrom', e.target.value)} />
          <input type="datetime-local" className={styles.filterInput} value={filters.customTo} onChange={e => setFilter('customTo', e.target.value)} />
        </>}
      </div>

      {error && (
        <p role="alert" className={styles.alertBanner}>{error}</p>
      )}

      <div className={styles.tableWrapper}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Decision</th>
              <th>Intent</th>
              <th>Confidence</th>
              <th>Message</th>
              <th>Reason</th>
              <th className={styles.stickyCol}>Status</th>
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--fg-3)', padding: 'var(--sp-5)' }}>Loading{'\u2026'}</td></tr>
            )}
            {!loading && flags.length === 0 && !error && (
              <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--fg-3)', padding: 'var(--sp-5)' }}>No decisions yet</td></tr>
            )}
            {flags.map(f => {
              const meta = parseMeta(f.metadata)
              return (
                <tr key={f.id} className={styles.clickableRow} onClick={() => setSelected(f)}>
                  <td className={styles.mono}>{f.timestamp ? new Date(f.timestamp).toLocaleString() : '\u2014'}</td>
                  <td><Badge variant={DECISION_VARIANT[f.decision] ?? 'gray'}>{f.decision}</Badge></td>
                  <td><Badge variant="gray">{f.originalIntent}</Badge></td>
                  <td className={styles.mono}>{f.confidence != null ? (f.confidence * 100).toFixed(0) + '%' : '\u2014'}</td>
                  <td className={styles.message} title={meta.messageText}>{meta.messageText ?? '\u2014'}</td>
                  <td>{f.reason ?? '\u2014'}</td>
                  <td className={styles.stickyCol}><Badge variant={STATUS_VARIANT[f.signalStatus] ?? 'gray'}>{f.signalStatus ?? 'NEW'}</Badge></td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <div className={styles.pagination}>
        <Button variant="secondary" disabled={filters.page === 0} onClick={() => setFilters(f => ({ ...f, page: f.page - 1 }))}>{'\u2190'} Prev</Button>
        <span>Page {filters.page + 1} of {totalPages} {'\u00a0\u00b7\u00a0'} {total} total</span>
        <Button variant="secondary" disabled={filters.page + 1 >= totalPages} onClick={() => setFilters(f => ({ ...f, page: f.page + 1 }))}>Next {'\u2192'}</Button>
      </div>

      {selected && (
        <FlagDetailModal
          flag={selected}
          onClose={() => setSelected(null)}
          onStatusChange={updateStatus}
          api={api}
        />
      )}
    </>
  )
}
