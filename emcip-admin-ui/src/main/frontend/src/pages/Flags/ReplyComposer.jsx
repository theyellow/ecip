import { useState } from 'react'
import { Button } from '../../components/Button/Button'
import { SegmentedControl } from '../../components/SegmentedControl/SegmentedControl'
import styles from './ReplyComposer.module.css'

const MODES = [
  { value: 'GROUP', label: 'Group' },
  { value: 'QUOTE', label: 'Quote' },
  { value: 'DM', label: 'DM' },
  { value: 'NOTE', label: 'Note' },
]

const MODE_HINTS = {
  GROUP: 'Posts in the group chat',
  QUOTE: 'Replies to the original message in the group',
  DM: 'Sends a direct message to the sender',
  NOTE: 'Internal note — not sent to Telegram',
}

const TEMPLATES = [
  'Thank you for reporting.',
  'No action needed.',
  'This has been reviewed.',
  'Warning issued.',
]

const MAX_LENGTH = 4096

export function ReplyComposer({ flagId, api, onActioned }) {
  const [text, setText] = useState('')
  const [mode, setMode] = useState('GROUP')
  const [replyToOriginal, setReplyToOriginal] = useState(true)
  const [prefixModerator, setPrefixModerator] = useState(false)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [accounts, setAccounts] = useState(null)
  const [selectedAccountId, setSelectedAccountId] = useState(null)
  const [promptActioned, setPromptActioned] = useState(false)

  const isNote = mode === 'NOTE'

  const handleSend = async () => {
    setSending(true)
    setError('')
    setSuccess(false)
    try {
      const target = mode === 'QUOTE' ? 'GROUP' : mode
      const forceReplyToOriginal = mode === 'QUOTE' ? true : replyToOriginal
      await api.reply(flagId, {
        text,
        target,
        replyToOriginal: forceReplyToOriginal,
        prefixModerator: isNote ? false : prefixModerator,
        accountId: selectedAccountId,
      })
      setSuccess(true)
      setPromptActioned(true)
      setText('')
    } catch (e) {
      if (e.status === 409 && e.body?.accounts) {
        setAccounts(e.body.accounts)
        setError('Multiple accounts watch this chat — select one below.')
      } else {
        setError(e.message || 'Failed to send reply')
      }
    } finally {
      setSending(false)
    }
  }

  const handleDiscard = () => {
    setText('')
    setMode('GROUP')
    setReplyToOriginal(true)
    setPrefixModerator(false)
    setError('')
    setSuccess(false)
    setAccounts(null)
    setSelectedAccountId(null)
    setPromptActioned(false)
  }

  const handleMarkActioned = async () => {
    try {
      await onActioned()
      setPromptActioned(false)
    } catch (e) {
      setError(e.message)
    }
  }

  const modeLabel = MODES.find(m => m.value === mode)?.label ?? mode

  return (
    <div className={styles.composer}>
      <SegmentedControl options={MODES} value={mode} onChange={setMode} />

      <p className={styles.modeHint}>{MODE_HINTS[mode]}</p>

      <div className={styles.chipRow}>
        {TEMPLATES.map(t => (
          <button key={t} type="button" className={styles.chip} onClick={() => setText(t)}>
            {t}
          </button>
        ))}
        <button type="button" className={styles.chipGhost} onClick={() => setText('')}>
          Clear
        </button>
      </div>

      <textarea
        className={styles.textarea}
        placeholder={isNote ? 'Write an internal note…' : 'Type your response…'}
        value={text}
        onChange={e => setText(e.target.value)}
        maxLength={MAX_LENGTH}
      />

      {mode !== 'NOTE' && mode !== 'QUOTE' && (
        <div className={styles.options}>
          <label>
            <input type="checkbox" checked={replyToOriginal} onChange={e => setReplyToOriginal(e.target.checked)} />
            Reply to original
          </label>
          <label>
            <input type="checkbox" checked={prefixModerator} onChange={e => setPrefixModerator(e.target.checked)} />
            Prefix [Moderator]
          </label>
        </div>
      )}

      {mode === 'QUOTE' && (
        <div className={styles.options}>
          <label>
            <input type="checkbox" checked={prefixModerator} onChange={e => setPrefixModerator(e.target.checked)} />
            Prefix [Moderator]
          </label>
        </div>
      )}

      {accounts && !isNote && (
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

      <div className={styles.footer}>
        <span className={styles.charCounter}>
          {text.length} / {MAX_LENGTH} {'\u00b7'} {modeLabel}
        </span>
        <div className={styles.footerActions}>
          {promptActioned ? (
            <div className={styles.actionedPrompt}>
              <span className={styles.success}>{isNote ? 'Saved!' : 'Sent!'} Mark as actioned?</span>
              <Button variant="secondary" onClick={handleMarkActioned}>Yes</Button>
              <Button variant="secondary" onClick={() => setPromptActioned(false)}>No</Button>
            </div>
          ) : (
            <>
              <Button variant="secondary" onClick={handleDiscard}>Discard</Button>
              <Button onClick={handleSend} disabled={sending || !text.trim()}>
                {sending ? (isNote ? 'Saving…' : 'Sending…') : (isNote ? 'Save note' : 'Send reply')}
              </Button>
            </>
          )}
        </div>
      </div>

      {error && (
        <p role="alert" className={styles.alertBanner}>{error}</p>
      )}
    </div>
  )
}
