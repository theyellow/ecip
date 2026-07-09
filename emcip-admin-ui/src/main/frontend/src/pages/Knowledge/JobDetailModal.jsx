import { useEffect, useState } from 'react'
import { Modal } from '../../components/Modal/Modal'
import { Badge } from '../../components/Badge/Badge'
import styles from './JobDetailModal.module.css'

const STATUS_VARIANT = {
  COMPLETED: 'green',
  RUNNING: 'blue',
  QUEUED: 'gray',
  FAILED: 'red',
  FLAGGED_INJECTION_RISK: 'yellow',
}

export function JobDetailModal({ api, jobId, tenants, onClose, onSearchEntity }) {
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    setLoading(true)
    api
      .jobDetails(jobId)
      .then(setDetail)
      .catch(e => setError(e.message || 'Failed to load details'))
      .finally(() => setLoading(false))
  }, [api, jobId])

  const tenantName = detail?.job?.tenantId
    ? (tenants.find(t => t.id === detail.job.tenantId)?.name ?? detail.job.tenantId)
    : 'Global'

  const title = detail?.job?.sourceRef
    ? (detail.job.sourceRef.length > 60
        ? detail.job.sourceRef.substring(0, 60) + '\u2026'
        : detail.job.sourceRef)
    : 'Job Details'

  return (
    <Modal title={title} onClose={onClose}>
      {loading && <p className={styles.loading}>Loading details\u2026</p>}
      {error && <p className={styles.error}>{error}</p>}
      {detail && (
        <div className={styles.content}>
          {/* Job Info */}
          <div className={styles.sectionLabel}>
            <span>\u2014 JOB INFO \u2014</span>
          </div>
          <div className={styles.infoGrid}>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>Source</span>
              <span className={styles.infoValue}>{detail.job.sourceRef}</span>
            </div>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>Type</span>
              <span className={styles.infoValue}>{detail.job.sourceType}</span>
            </div>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>Tenant</span>
              <span className={styles.infoValue}>{tenantName}</span>
            </div>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>Status</span>
              <Badge variant={STATUS_VARIANT[detail.job.status] ?? 'gray'}>
                {detail.job.status}
              </Badge>
            </div>
            {detail.job.contentHash && (
              <div className={styles.infoRow}>
                <span className={styles.infoLabel}>Content Hash</span>
                <span className={styles.mono}>{detail.job.contentHash}</span>
              </div>
            )}
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>Created</span>
              <span className={styles.mono}>
                {detail.job.createdAt
                  ? new Date(detail.job.createdAt).toLocaleString()
                  : '\u2014'}
              </span>
            </div>
          </div>

          {/* Error message for FAILED / FLAGGED */}
          {detail.job.errorMessage && (
            <div
              className={
                detail.job.status === 'FLAGGED_INJECTION_RISK'
                  ? styles.warningBlock
                  : styles.errorBlock
              }
            >
              {detail.job.errorMessage}
            </div>
          )}

          {/* Chunks */}
          <div className={styles.sectionLabel}>
            <span>\u2014 CHUNKS ({detail.totalChunks}) \u2014</span>
          </div>
          {detail.chunks.length === 0 ? (
            <p className={styles.empty}>No chunks.</p>
          ) : (
            <div className={styles.chunkList}>
              {detail.chunks.map(c => (
                <div key={c.id} className={styles.chunkCard}>
                  <div className={styles.chunkMeta}>
                    <span className={styles.mono}>#{c.chunkIndex}</span>
                    <span className={styles.mono}>
                      {c.entityCount} entities \u00b7 {c.relationshipCount} rels
                    </span>
                  </div>
                  <div className={styles.chunkPreview}>{c.contentPreview}</div>
                </div>
              ))}
            </div>
          )}

          {/* Entities */}
          <div className={styles.sectionLabel}>
            <span>\u2014 ENTITIES ({detail.totalEntities}) \u2014</span>
          </div>
          {detail.entities.length === 0 ? (
            <p className={styles.empty}>No entities extracted.</p>
          ) : (
            <div className={styles.entityChips}>
              {detail.entities.map(e => (
                <button
                  key={e.nodeId}
                  type="button"
                  className={styles.entityChip}
                  onClick={() => {
                    onSearchEntity?.(e.label)
                    onClose()
                  }}
                >
                  {e.label}{' '}
                  <Badge variant="gray">{e.conceptType}</Badge>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </Modal>
  )
}
