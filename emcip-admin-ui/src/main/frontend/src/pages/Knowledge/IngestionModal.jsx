import { useEffect, useRef, useState } from 'react'
import { Modal } from '../../components/Modal/Modal'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import styles from './IngestionModal.module.css'

export function IngestionModal({ api, tenants, onClose, onJobCreated }) {
  const [mode, setMode] = useState('url') // 'url' | 'file'
  const [url, setUrl] = useState('')
  const [file, setFile] = useState(null)
  const [tenantId, setTenantId] = useState('')
  const [phase, setPhase] = useState('config') // 'config' | 'polling' | 'done' | 'error'
  const [jobId, setJobId] = useState(null)
  const [jobStatus, setJobStatus] = useState(null)
  const [errorMsg, setErrorMsg] = useState('')
  const pollRef = useRef(null)

  useEffect(() => {
    if (phase !== 'polling' || !jobId) return

    pollRef.current = setInterval(async () => {
      try {
        const s = await api.status(jobId)
        setJobStatus(s)
        if (s.status === 'COMPLETED') {
          setPhase('done')
        } else if (s.status === 'FAILED') {
          setErrorMsg(s.errorMessage || 'Ingestion failed.')
          setPhase('error')
        }
      } catch (e) {
        setErrorMsg(e.message || 'Failed to fetch status.')
        setPhase('error')
      }
    }, 2000)

    return () => clearInterval(pollRef.current)
  }, [phase, jobId])

  async function handleSubmit() {
    try {
      const tid = tenantId || null
      let result
      if (mode === 'url') {
        result = await api.ingestUrl(url, tid)
      } else {
        result = await api.ingestUpload(file, tid)
      }
      setJobId(result.jobId)
      if (onJobCreated) onJobCreated()
      setPhase('polling')
    } catch (e) {
      setErrorMsg(e.message || 'Failed to submit.')
      setPhase('error')
    }
  }

  const canSubmit =
    phase === 'config' &&
    (mode === 'url' ? url.trim().startsWith('http') : file != null)

  const handleClose = phase === 'polling' ? () => {} : onClose

  return (
    <Modal title="ADD DOCUMENT" onClose={handleClose}>
      {phase === 'config' && (
        <>
          <div className={styles.segRow}>
            <button
              type="button"
              className={`${styles.seg}${mode === 'url' ? ` ${styles.segActive}` : ''}`}
              onClick={() => setMode('url')}
            >
              URL
            </button>
            <button
              type="button"
              className={`${styles.seg}${mode === 'file' ? ` ${styles.segActive}` : ''}`}
              onClick={() => setMode('file')}
            >
              File Upload
            </button>
          </div>

          {mode === 'url' ? (
            <div className={styles.field}>
              <label className={styles.label}>URL</label>
              <input
                className={styles.input}
                type="url"
                placeholder="https://example.com/article"
                value={url}
                onChange={e => setUrl(e.target.value)}
              />
            </div>
          ) : (
            <div className={styles.field}>
              <label className={styles.label}>File</label>
              <input
                className={styles.input}
                type="file"
                accept=".txt,.html,.pdf,.docx"
                onChange={e => setFile(e.target.files[0] ?? null)}
              />
              {file && <span className={styles.filename}>{file.name}</span>}
            </div>
          )}

          <div className={styles.field}>
            <label className={styles.label}>Tenant</label>
            <select
              className={styles.select}
              value={tenantId}
              onChange={e => setTenantId(e.target.value)}
            >
              <option value="">Global (shared)</option>
              {(tenants ?? []).map(t => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          </div>

          <div className={styles.footer}>
            <Button variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button variant="primary" disabled={!canSubmit} onClick={handleSubmit}>
              Submit
            </Button>
          </div>
        </>
      )}

      {phase === 'polling' && (
        <div className={styles.status}>
          <div className={styles.spinner} aria-hidden="true" />
          <div>
            <div>Processing...</div>
            <div className={styles.sourceRef}>{mode === 'url' ? url : file?.name}</div>
          </div>
        </div>
      )}

      {phase === 'done' && (
        <>
          <div className={styles.doneRow}>
            <Badge variant="green">COMPLETED</Badge>
            <span className={styles.done}>{jobStatus?.chunkCount ?? 0} chunks extracted.</span>
          </div>
          <div className={styles.footer}>
            <Button variant="secondary" onClick={onClose}>
              Close
            </Button>
          </div>
        </>
      )}

      {phase === 'error' && (
        <>
          <p className={styles.error}>{errorMsg}</p>
          <div className={styles.footer}>
            <Button
              variant="secondary"
              onClick={() => {
                setPhase('config')
                setErrorMsg('')
              }}
            >
              Retry
            </Button>
            <Button variant="secondary" onClick={onClose}>
              Close
            </Button>
          </div>
        </>
      )}
    </Modal>
  )
}
