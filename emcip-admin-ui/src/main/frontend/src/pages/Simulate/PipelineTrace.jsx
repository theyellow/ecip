import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import styles from './PipelineTrace.module.css'

const STAGE_META = [
  { key: 'PUBLISH',    label: 'PUBLISH',           service: 'admin-api',          topic: 'telegram.raw.messages' },
  { key: 'CLASSIFIER', label: 'INTENT CLASSIFIER', service: 'intent-classifier',  topic: 'messages.classified' },
  { key: 'POLICY',     label: 'POLICY ENGINE',     service: 'policy-engine',      topic: 'policies.decisions' },
  { key: 'MODERATION', label: 'MODERATION SERVICE',service: 'moderation-service', topic: 'moderation.flags' },
]

function dotColor(stageKey, data) {
  if (!data) return 'var(--border-strong)'
  if (stageKey === 'PUBLISH') return 'var(--signal-ok-fg)'
  if (stageKey === 'CLASSIFIER') return 'var(--accent)'
  if (stageKey === 'POLICY') {
    const d = (data.decision || '').toUpperCase()
    if (d === 'BLOCK' || d === 'MODERATE') return 'var(--signal-stop-fg)'
    if (d === 'REACT' || d === 'SUMMARIZE') return 'var(--signal-info-fg)'
    return 'var(--signal-mute-fg)'
  }
  if (stageKey === 'MODERATION') {
    const s = (data.severity || '').toUpperCase()
    if (s === 'HIGH') return 'var(--signal-stop-fg)'
    if (s === 'MEDIUM') return 'var(--signal-warn-fg)'
    return 'var(--signal-ok-fg)'
  }
  return 'var(--fg-3)'
}

function stageDataLines(stageKey, data) {
  if (!data) return null
  if (stageKey === 'PUBLISH') {
    return [`eventId: ${data.eventId}`]
  }
  if (stageKey === 'CLASSIFIER') {
    const pct = data.confidence != null ? `${Math.round(data.confidence * 100)}%` : ''
    const rules = Array.isArray(data.matchedRules) && data.matchedRules.length
      ? data.matchedRules.join(', ')
      : null
    return [
      [data.intent, pct].filter(Boolean).join(' \u00b7 '),
      rules ? `rules: ${rules}` : null,
    ].filter(Boolean)
  }
  if (stageKey === 'POLICY') {
    const actions = Array.isArray(data.actions) ? data.actions.join(', ') : data.actions || ''
    return [
      [data.decision, actions].filter(Boolean).join(' \u00b7 '),
      data.policyId ? `policy: ${data.policyId}` : null,
      data.reason ? `reason: ${data.reason}` : null,
    ].filter(Boolean)
  }
  if (stageKey === 'MODERATION') {
    return [
      [data.flagType, data.severity].filter(Boolean).join(' \u00b7 '),
      data.reason ? `reason: ${data.reason}` : null,
    ].filter(Boolean)
  }
  return null
}

function findStageData(stages, key) {
  if (!stages) return null
  const found = stages.find(s => s.stage === key)
  return found ? found.data : null
}

export function PipelineTrace({ result, loading }) {
  return (
    <div className={styles.panel}>
      <SectionLabel>Pipeline Trace</SectionLabel>

      <div className={styles.legend}>
        <span className={styles.legendItem}>
          <span className={styles.legendDot} style={{ background: 'var(--border-strong)' }} />
          waiting
        </span>
        <span className={styles.legendItem}>
          <span className={styles.legendDot} style={{ background: 'var(--accent)' }} />
          processing
        </span>
        <span className={styles.legendItem}>
          <span className={styles.legendDot} style={{ background: 'var(--signal-ok-fg)' }} />
          done
        </span>
        <span className={styles.legendItem}>
          <span className={styles.legendDot} style={{ background: 'var(--signal-stop-fg)' }} />
          blocked
        </span>
      </div>

      {loading && <p className={styles.waiting}>{'\u25b6'} waiting for pipeline\u2026</p>}
      <div className={styles.stages}>
        {STAGE_META.map(meta => {
          const data = result ? findStageData(result.stages, meta.key) : null
          const timedOut = result && result.partial && !data
          const color = dotColor(meta.key, data)
          const lines = stageDataLines(meta.key, data)
          return (
            <div key={meta.key} className={styles.stage}>
              <div className={styles.stageHead}>
                <span
                  className={styles.dot}
                  style={{ background: color }}
                  aria-hidden="true"
                />
                <span
                  className={styles.stageName}
                  style={{ color: data || timedOut ? 'var(--fg-1)' : 'var(--fg-3)' }}
                >
                  {meta.label}
                </span>
              </div>
              <span className={styles.stageSource}>
                {meta.service} {'\u00b7'} {meta.topic}
              </span>
              {timedOut && (
                <span className={styles.timedOut}>\u2014 timed out \u2014</span>
              )}
              {lines && (
                <div className={styles.stageData}>
                  {lines.map((line, i) => <span key={i}>{line}</span>)}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
