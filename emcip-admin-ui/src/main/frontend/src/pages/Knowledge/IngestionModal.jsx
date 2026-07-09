import { useCallback, useEffect, useState } from 'react'
import { Modal } from '../../components/Modal/Modal'
import { Button } from '../../components/Button/Button'
import { SegmentedControl } from '../../components/SegmentedControl/SegmentedControl'
import { useToast } from '../../components/Toast/useToast'
import styles from './IngestionModal.module.css'

const WARM_UP_TIMEOUT_MS = 15000

export function IngestionModal({ api, tenants, onClose, onJobCreated }) {
  const [mode, setMode] = useState('url')
  const [url, setUrl] = useState('')
  const [file, setFile] = useState(null)
  const [tenantId, setTenantId] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [warmUpState, setWarmUpState] = useState('loading') // loading | ready | failed
  const [warmUpLatency, setWarmUpLatency] = useState(null)
  const { addToast } = useToast()

  // Warm up models on mount
  useEffect(() => {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), WARM_UP_TIMEOUT_MS)

    api
      .warmUp(['EMBED', 'EXTRACT'])
      .then(data => {
        const results = data.results || {}
        const allReady = Object.values(results).every(r => r.ready)
        const maxLatency = Math.max(...Object.values(results).map(r => r.latencyMs || 0))
        if (allReady) {
          setWarmUpState('ready')
          setWarmUpLatency(maxLatency)
        } else {
          setWarmUpState('failed')
          addToast('warning', 'Model warm-up failed — ingestion may be slow')
        }
      })
      .catch(() => {
        setWarmUpState('failed')
        addToast('warning', 'Model warm-up failed — ingestion may be slow')
      })
      .finally(() => clearTimeout(timeout))

    return () => {
      controller.abort()
      clearTimeout(timeout)
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const canSubmit =
    warmUpState !== 'loading' &&
    !submitting &&
    (mode === 'url' ? url.trim() : file) &&
    tenantId

  const handleSubmit = useCallback(async () => {
    setSubmitting(true)
    try {
      if (mode === 'url') {
        await api.ingestUrl(url, tenantId)
      } else {
        await api.ingestUpload(file, tenantId)
      }
      const sourceRef = mode === 'url' ? url : file.name
      addToast('info', `Document submitted: ${sourceRef}`)
      onJobCreated()
      onClose()
    } catch (err) {
      addToast('error', `Submission failed: ${err.message || 'Unknown error'}`)
      setSubmitting(false)
    }
  }, [mode, url, file, tenantId, api, addToast, onJobCreated, onClose])

  return (
    <Modal title="Add Document" onClose={onClose}>
      <div className={styles.form}>
        <SegmentedControl
          value={mode}
          onChange={setMode}
          options={[
            { value: 'url', label: 'URL' },
            { value: 'file', label: 'File' },
          ]}
        />

        {mode === 'url' ? (
          <input
            className={styles.input}
            type="url"
            placeholder="https://example.com/document.pdf"
            value={url}
            onChange={e => setUrl(e.target.value)}
          />
        ) : (
          <input
            className={styles.input}
            type="file"
            accept=".pdf,.txt,.html,.docx,.doc,.odt,.rtf"
            onChange={e => setFile(e.target.files?.[0] || null)}
          />
        )}

        <select
          className={styles.input}
          value={tenantId}
          onChange={e => setTenantId(e.target.value)}
        >
          <option value="">Select tenant...</option>
          {(tenants ?? []).map(t => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>

        <div className={styles.warmUpStatus}>
          {warmUpState === 'loading' && (
            <span className={styles.warmUpLoading}>Preparing models...</span>
          )}
          {warmUpState === 'ready' && (
            <span className={styles.warmUpReady}>Models ready ({warmUpLatency}ms)</span>
          )}
        </div>
      </div>

      <div className={styles.footer}>
        <Button variant="secondary" onClick={onClose}>
          Cancel
        </Button>
        <Button variant="primary" onClick={handleSubmit} disabled={!canSubmit}>
          {submitting ? 'Submitting...' : 'Submit'}
        </Button>
      </div>
    </Modal>
  )
}
