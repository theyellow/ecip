import { Badge } from '../../components/Badge/Badge'
import styles from './ComparisonView.module.css'

const STATUS_VARIANT = {
  COMPLETED: 'green',
  RUNNING: 'blue',
  CREATED: 'gray',
  PAUSED: 'yellow',
  FAILED: 'red',
}

const TEMPLATE_LABEL = {
  TOPIC: 'Topic',
  PERSON: 'Person',
  FACT_CHECK: 'Fact Check',
}

function SessionCard({ session, label }) {
  return (
    <div className={styles.card}>
      <div className={styles.cardLabel}>{label}</div>

      <p className={styles.question}>{session.question}</p>

      <div className={styles.badges}>
        <Badge variant={STATUS_VARIANT[session.status] ?? 'gray'}>{session.status}</Badge>
        {session.reportTemplate && (
          <Badge variant="gray">
            {TEMPLATE_LABEL[session.reportTemplate] ?? session.reportTemplate}
          </Badge>
        )}
        {session.reportId && <Badge variant="green">Report &#x2713;</Badge>}
      </div>

      <div className={styles.statsGrid}>
        <div className={styles.stat}>
          <div className={styles.statLabel}>Iterations</div>
          <div className={styles.statValue}>
            {session.iterationsUsed} / {session.maxIterations}
          </div>
        </div>
        <div className={styles.stat}>
          <div className={styles.statLabel}>LLM Calls</div>
          <div className={styles.statValue}>
            {session.llmCallsUsed} / {session.maxLlmCalls}
          </div>
        </div>
        <div className={styles.stat}>
          <div className={styles.statLabel}>Cost Used</div>
          <div className={styles.statValue}>${(session.costUsedUsd ?? 0).toFixed(3)}</div>
        </div>
        <div className={styles.stat}>
          <div className={styles.statLabel}>Evidence</div>
          <div className={styles.statValue}>{session.evidence?.length ?? '\u2014'}</div>
        </div>
        <div className={styles.stat} style={{ gridColumn: '1 / -1' }}>
          <div className={styles.statLabel}>Created</div>
          <div className={styles.statValue} style={{ fontSize: 12 }}>
            {session.createdAt ? new Date(session.createdAt).toLocaleString() : '\u2014'}
          </div>
        </div>
      </div>
    </div>
  )
}

export function ComparisonView({ sessionA, sessionB, onClose }) {
  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.dialog} onClick={(e) => e.stopPropagation()}>
        <div className={styles.header}>
          <span className={styles.headerTitle}>Session Comparison</span>
          <button className={styles.closeBtn} onClick={onClose} aria-label="Close">
            &#xd7;
          </button>
        </div>

        <div className={styles.grid}>
          <SessionCard session={sessionA} label="Session A" />
          <SessionCard session={sessionB} label="Session B" />
        </div>
      </div>
    </div>
  )
}
