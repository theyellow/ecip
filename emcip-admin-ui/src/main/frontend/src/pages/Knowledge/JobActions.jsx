import styles from './KnowledgePage.module.css'

/**
 * Row actions for an ingestion job. Global jobs can be changed only by a platform ADMIN
 * (KNOW-F1); for everyone else the actions are replaced by a label saying why.
 */
export function JobActions({ row, canChange, onDelete, onReingest }) {
  if (!canChange) {
    return (
      <span className={styles.actionBtns} title="Global — managed by a platform admin">
        Global
      </span>
    )
  }
  return (
    <span className={styles.actionBtns} onClick={e => e.stopPropagation()}>
      <button type="button" className={styles.actionBtn} title="Delete" onClick={() => onDelete(row)}>
        {'✕'}
      </button>
      {(row.rawStatus === 'COMPLETED' || row.rawStatus === 'FAILED') && (
        <button
          type="button"
          className={styles.actionBtn}
          title="Re-ingest"
          onClick={() => onReingest(row)}
        >
          {'↻'}
        </button>
      )}
    </span>
  )
}
