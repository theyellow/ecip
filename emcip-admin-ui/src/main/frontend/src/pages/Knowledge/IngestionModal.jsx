import { useCallback, useEffect, useState } from 'react'
import { Modal } from '../../components/Modal/Modal'
import { Button } from '../../components/Button/Button'
import { SegmentedControl } from '../../components/SegmentedControl/SegmentedControl'
import { useToast } from '../../components/Toast/useToast'
import styles from './IngestionModal.module.css'

const WARM_UP_TIMEOUT_MS = 15000

export function IngestionModal({
  api,
  tenants,
  onClose,
  onJobCreated,
  replaceJobId = null,
  initialSourceRef = null,
  initialTenantId = null,
}) {
  const [mode, setMode] = useState('url')
  const [url, setUrl] = useState(initialSourceRef ?? '')
  const [file, setFile] = useState(null)
  const [tenantId, setTenantId] = useState(initialTenantId ?? '')
  const [submitting, setSubmitting] = useState(false)
  const [warmUpState, setWarmUpState] = useState('loading') // loading | ready | failed
  const [warmUpLatency, setWarmUpLatency] = useState(null)
  const { addToast } = useToast()

  // Warm up models on mount
  useEffect(() => {
    let cancelled = false;
    const timeout = setTimeout(() => {
      cancelled = true;
      setWarmUpState('failed');
      addToast('warning', 'Model warm-up timed out — ingestion may be slow');
    }, WARM_UP_TIMEOUT_MS);

    api
      .warmUp(['EMBED', 'EXTRACT'])
      .then(data => {
        if (cancelled) return;
        clearTimeout(timeout);
        const results = data.results || {};
        const allReady = Object.values(results).every(r => r.ready);
        const maxLatency = Math.max(
          ...Object.values(results).map(r => r.latencyMs || 0)
        );
        if (allReady) {
          setWarmUpState('ready');
          setWarmUpLatency(maxLatency);
        } else {
          setWarmUpState('failed');
          addToast('warning', 'Model warm-up failed — ingestion may be slow');
        }
      })
      .catch(() => {
        if (cancelled) return;
        clearTimeout(timeout);
        setWarmUpState('failed');
        addToast('warning', 'Model warm-up failed — ingestion may be slow');
      });

    return () => {
      cancelled = true;
      clearTimeout(timeout);
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const canSubmit =
    warmUpState !== 'loading' &&
    !submitting &&
    (mode === 'url' ? url.trim() : file)

  const handleSubmit = useCallback(async () => {
    setSubmitting(true)
    try {
      const effectiveTenantId = tenantId || null
      if (replaceJobId) {
        // Re-upload: delete old data first, then ingest normally
        try {
          await api.deleteJob(replaceJobId)
        } catch {
          // Old job may already be deleted
        }
      }
      if (mode === 'url') {
        await api.ingestUrl(url, effectiveTenantId)
      } else {
        await api.ingestUpload(file, effectiveTenantId)
      }
      const sourceRef = mode === 'url' ? url : file.name
      addToast('info', replaceJobId ? `Re-ingestion started: ${sourceRef}` : `Document submitted: ${sourceRef}`)
      onJobCreated()
      onClose()
    } catch (err) {
      // Handle 409 dedup
      if (err.message?.includes('409') || err.status === 409) {
        addToast('info', `Already ingested: ${mode === 'url' ? url : file?.name}. Use re-ingest to update.`)
        onClose()
        return
      }
      addToast('error', `Submission failed: ${err.message || 'Unknown error'}`)
      setSubmitting(false)
    }
  }, [mode, url, file, tenantId, api, addToast, onJobCreated, onClose, replaceJobId])

  return (
    <Modal title={replaceJobId ? 'Re-ingest Document' : 'Add Document'} onClose={onClose}>
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
          <option value="">Global (all tenants)</option>
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
          {submitting ? 'Submitting...' : replaceJobId ? 'Re-ingest' : 'Submit'}
        </Button>
      </div>
    </Modal>
  )
}
