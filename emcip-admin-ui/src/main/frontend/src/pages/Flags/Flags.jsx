import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { flagsApi } from '../../api/flags'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import { SegmentedControl } from '../../components/SegmentedControl/SegmentedControl'
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

  const [showAnalysis, setShowAnalysis] = useState(false)
  const [analysing, setAnalysing] = useState(false)
  const [analysisResult, setAnalysisResult] = useState(null)
  const [analysisCopied, setAnalysisCopied] = useState(false)

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

  const handleAnalyse = async () => {
    setAnalysing(true)
    setAnalysisResult(null)
    try {
      const result = await api.analyse(flag.id)
      setAnalysisResult(result)
    } catch (e) {
      setAnalysisResult({ success: false, analysis: e.message || 'Analysis failed', model: null })
    } finally {
      setAnalysing(false)
    }
  }

  const copyAnalysis = () => {
    if (!analysisResult?.analysis) return
    navigator.clipboard.writeText(analysisResult.analysis).then(() => {
      setAnalysisCopied(true)
      setTimeout(() => setAnalysisCopied(false), 1500)
    })
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
    <Modal title="Flag Detail" onClose={onClose}>
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
        <p role="alert" style={{
          color: 'var(--signal-stop-fg)',
          background: 'rgba(248,113,113,0.08)',
          border: '1px solid rgba(248,113,113,0.25)',
          padding: '8px 12px',
          fontFamily: 'var(--font-mono)',
          fontSize: '12px',
        }}>{error}</p>
      )}

      <div className={styles.replyHeader} onClick={() => setShowReply(s => !s)}>
        <SectionLabel aside={showReply ? '\u25BE' : '\u25B8'}>Reply</SectionLabel>
      </div>

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
            <SegmentedControl
              options={[{ value: 'GROUP', label: 'Group' }, { value: 'DM', label: 'DM' }]}
              value={replyTarget}
              onChange={setReplyTarget}
            />
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
              {replySending ? 'Sending\u2026' : 'Send'}
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

          {replyError && (
            <p role="alert" style={{
              color: 'var(--signal-stop-fg)',
              background: 'rgba(248,113,113,0.08)',
              border: '1px solid rgba(248,113,113,0.25)',
              padding: '8px 12px',
              fontFamily: 'var(--font-mono)',
              fontSize: '12px',
            }}>{replyError}</p>
          )}
        </div>
      )}

      <div className={styles.replyHeader} onClick={() => setShowAnalysis(s => !s)}>
        <SectionLabel aside={showAnalysis ? '\u25BE' : '\u25B8'}>AI Analysis</SectionLabel>
      </div>

      {showAnalysis && (
        <div className={styles.replySection}>
          <div className={styles.replyActions}>
            <Button variant="secondary" onClick={handleAnalyse} disabled={analysing}>
              {analysing ? 'Analysing\u2026' : analysisResult ? 'Re-analyse' : 'Analyse'}
            </Button>
            {analysisResult?.success && (
              <button
                className={styles.copyAnalysisBtn}
                onClick={copyAnalysis}
              >
                {analysisCopied ? 'Copied' : 'Copy'}
              </button>
            )}
          </div>
          {analysisResult && (
            <div className={analysisResult.success ? styles.analysisBlock : styles.analysisError}>
              {analysisResult.model && (
                <p className={styles.analysisModel}>{analysisResult.model}</p>
              )}
              <p className={styles.analysisText}>{analysisResult.analysis}</p>
            </div>
          )}
        </div>
      )}
    </Modal>
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
    <>
      <div className={styles.pageHeader}>
        <div>
          <h2>Flags</h2>
          <div className={styles.systemId}>{'\u2691'} policy-engine {'\u00b7'} {total} flags</div>
        </div>
        <div className={styles.filters}>
          <select value={decision} onChange={e => setDecision(e.target.value)} className={styles.select}>
            {DECISIONS.map(d => <option key={d} value={d}>{d || 'All decisions'}</option>)}
          </select>
          <select value={size} onChange={e => setSize(Number(e.target.value))} className={styles.select}>
            {[25, 50, 100, 200].map(n => <option key={n}>{n}</option>)}
          </select>
        </div>
      </div>

      {error && (
        <p role="alert" style={{
          color: 'var(--signal-stop-fg)',
          background: 'rgba(248,113,113,0.08)',
          border: '1px solid rgba(248,113,113,0.25)',
          padding: '8px 12px',
          fontFamily: 'var(--font-mono)',
          fontSize: '12px',
          marginBottom: 'var(--sp-3)',
        }}>{error}</p>
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
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {loading && (
            <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--fg-3)', padding: 'var(--sp-5)' }}>Loading{'\u2026'}</td></tr>
          )}
          {!loading && flags.length === 0 && !error && (
            <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--fg-3)', padding: 'var(--sp-5)' }}>No flags yet</td></tr>
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
