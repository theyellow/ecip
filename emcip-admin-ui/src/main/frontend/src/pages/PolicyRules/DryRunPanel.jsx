import { useState } from 'react'
import { Button } from '../../components/Button/Button'
import styles from './DryRunPanel.module.css'

const DEFAULT_CTX = {
  intent: '',
  confidence: 0.8,
  language: 'en',
  threadLength: 3,
  groupSize: 100,
  messageLength: 45,
  senderAccountAgeDays: 999,
  senderFlaggedCount: 0,
  senderFlagWindowDays: 90,
}

export function DryRunPanel({ rule, api }) {
  const [open, setOpen] = useState(false)
  const [ctx, setCtx] = useState(DEFAULT_CTX)
  const [result, setResult] = useState(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')

  const set = (k, v) => setCtx(c => ({ ...c, [k]: v }))
  const num = (k, e) => set(k, parseFloat(e.target.value) || 0)

  const run = async () => {
    setRunning(true); setError(''); setResult(null)
    try {
      const r = await api.dryRun(rule, ctx)
      setResult(r)
    } catch (e) { setError(e.message) }
    finally { setRunning(false) }
  }

  return (
    <div className={styles.panel}>
      <div className={styles.header} onClick={() => setOpen(o => !o)}>
        <span className={styles.headerLabel}>{'\u26A1'} Test This Rule (Dry Run)</span>
        <span className={styles.toggle}>{open ? '\u25BE' : '\u25B8'}</span>
      </div>
      {open && (
        <div className={styles.body}>
          <div className={styles.grid}>
            <div className={styles.field}>
              <label className={styles.label}>Intent</label>
              <input className={styles.input} value={ctx.intent} onChange={e => set('intent', e.target.value)} placeholder="SPAM" />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Confidence (0–1)</label>
              <input type="number" className={styles.input} step="0.01" min="0" max="1" value={ctx.confidence} onChange={e => num('confidence', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Language</label>
              <input className={styles.input} value={ctx.language} onChange={e => set('language', e.target.value)} placeholder="en" />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Thread Length</label>
              <input type="number" className={styles.input} value={ctx.threadLength} onChange={e => num('threadLength', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Group Size</label>
              <input type="number" className={styles.input} value={ctx.groupSize} onChange={e => num('groupSize', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Message Length (chars)</label>
              <input type="number" className={styles.input} value={ctx.messageLength} onChange={e => num('messageLength', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Sender Account Age (days)</label>
              <input type="number" className={styles.input} value={ctx.senderAccountAgeDays} onChange={e => num('senderAccountAgeDays', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Sender Flagged Count</label>
              <input type="number" className={styles.input} value={ctx.senderFlaggedCount} onChange={e => num('senderFlaggedCount', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Flagged Window (days)</label>
              <input type="number" className={styles.input} value={ctx.senderFlagWindowDays} onChange={e => num('senderFlagWindowDays', e)} />
            </div>
          </div>
          <Button variant="secondary" onClick={run} disabled={running}>
            {running ? 'Running\u2026' : 'Run Dry Run'}
          </Button>
          {error && <p style={{ color: 'var(--signal-stop-fg)', fontFamily: 'var(--font-mono)', fontSize: '12px', marginTop: 'var(--sp-2)' }}>{error}</p>}
          {result && (
            <div className={styles.result}>
              <div className={styles.resultHeader}>
                <span className={`${styles.match} ${result.matched ? styles.matchYes : styles.matchNo}`}>
                  {result.matched ? 'MATCH' : 'NO MATCH'}
                </span>
                {result.matched && <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{'\u2192'} Group {result.matchedGroupIndex + 1}</span>}
              </div>
              {result.groupResults.map((g, i) => (
                <div key={i} className={styles.group}>
                  <div className={`${styles.groupLabel} ${g.matched ? styles.groupPass : styles.groupFail}`}>
                    {g.matched ? '\u2713' : '\u2717'} Group {i + 1}
                  </div>
                  {g.conditionResults.map((c, j) => (
                    <div key={j} className={`${styles.condRow} ${c.passed ? styles.condPass : styles.condFail}`}>
                      {c.type}: {c.detail}
                    </div>
                  ))}
                </div>
              ))}
              {result.matched && (
                <div className={`${styles.action} ${styles.actionMatch}`}>{'\u2192'} {result.action}</div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
