import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthRequest } from '../../auth/AuthContext'
import { intentSignalConfigApi } from '../../api/intentSignalConfig'
import { Button } from '../../components/Button/Button'
import styles from './IntentSignalConfig.module.css'

const DEFAULTS = {
  foreignScriptRatio: 0.6,
  cyrillicRatio: 0.6,
  lookalikeSuspicion: 3,
  zeroWidthAbuse: 2,
  capsRatio: 0.7,
  toxicityWords: [],
  description: '',
}

const FIELDS = [
  {
    key: 'foreignScriptRatio',
    label: 'Foreign Script Ratio',
    type: 'number',
    step: 0.05,
    min: 0,
    max: 1,
    tooltip:
      'Fraction of non-Latin characters above which SCRIPT_FOREIGN intent fires (0.0\u20131.0)',
  },
  {
    key: 'cyrillicRatio',
    label: 'Cyrillic Ratio',
    type: 'number',
    step: 0.05,
    min: 0,
    max: 1,
    tooltip:
      'Fraction of Cyrillic characters above which cyrillicRatio signal is reported (0.0\u20131.0)',
  },
  {
    key: 'lookalikeSuspicion',
    label: 'Lookalike Suspicion',
    type: 'number',
    step: 1,
    min: 0,
    tooltip:
      'Minimum count of words containing mixed Cyrillic/Greek lookalike + Latin characters to trigger LOOKALIKE_ABUSE',
  },
  {
    key: 'zeroWidthAbuse',
    label: 'Zero-Width Abuse Threshold',
    type: 'number',
    step: 1,
    min: 0,
    tooltip:
      'Minimum count of zero-width or RTL-override characters to trigger FORMAT_ABUSE',
  },
  {
    key: 'capsRatio',
    label: 'Caps Ratio',
    type: 'number',
    step: 0.05,
    min: 0,
    max: 1,
    tooltip:
      'Fraction of uppercase alphabetic characters above which CAPS_HEAVY intent fires (0.0\u20131.0)',
  },
]

export function IntentSignalConfig() {
  const api = intentSignalConfigApi(useAuthRequest())
  const navigate = useNavigate()
  const [form, setForm] = useState(DEFAULTS)
  const [chipInput, setChipInput] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [saving, setSaving] = useState(false)
  const chipInputRef = useRef(null)

  useEffect(() => {
    api
      .get()
      .then(data => setForm({ ...DEFAULTS, ...data }))
      .catch(() => setForm(DEFAULTS))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  const addChip = () => {
    const word = chipInput.trim().replace(/,+$/, '').trim()
    if (!word) return
    if (!form.toxicityWords.includes(word)) {
      set('toxicityWords', [...form.toxicityWords, word])
    }
    setChipInput('')
  }

  const handleChipKey = e => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      addChip()
    } else if (e.key === 'Backspace' && chipInput === '' && form.toxicityWords.length > 0) {
      set('toxicityWords', form.toxicityWords.slice(0, -1))
    }
  }

  const removeChip = word => {
    set('toxicityWords', form.toxicityWords.filter(w => w !== word))
  }

  const handleSave = async () => {
    setError('')
    setSuccess('')
    setSaving(true)
    try {
      await api.upsert(form)
      setSuccess('Configuration saved.')
    } catch (e) {
      setError(e.message ?? 'Save failed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className={styles.page}>
      {error && (
        <p className={styles.alert} role="alert">
          {error}
        </p>
      )}
      {success && (
        <p className={styles.success} role="status">
          {success}
        </p>
      )}

      <div className={styles.header}>
        <div className={styles.headerLeft}>
          <h2 className={styles.title}>Intent Signal Config</h2>
          <div className={styles.systemId}>
            &#x2726; intent-classifier &middot; signal detection thresholds
          </div>
        </div>
        <div className={styles.backBtn}>
          <Button variant="secondary" onClick={() => navigate('/intent-rules')}>
            &larr; Intent Rules
          </Button>
        </div>
      </div>

      <div className={styles.card}>
        <div className={styles.sectionLabel}>&mdash; Thresholds &mdash;</div>
        <div className={styles.grid}>
          {FIELDS.map(field => (
            <div key={field.key} className={styles.field}>
              <label className={styles.fieldLabel} htmlFor={field.key}>
                {field.label}
              </label>
              <input
                id={field.key}
                type={field.type}
                className={styles.input}
                value={form[field.key]}
                step={field.step}
                min={field.min}
                max={field.max}
                onChange={e => {
                  const raw = e.target.value
                  const parsed =
                    field.step === 1 ? parseInt(raw, 10) : parseFloat(raw)
                  set(field.key, isNaN(parsed) ? 0 : parsed)
                }}
              />
              <p className={styles.tooltip}>{field.tooltip}</p>
            </div>
          ))}
        </div>
      </div>

      <div className={styles.card}>
        <div className={styles.sectionLabel}>&mdash; Toxicity Words &mdash;</div>
        <div className={styles.fieldFull}>
          <label className={styles.fieldLabel}>
            Toxicity Words
          </label>
          <div
            className={styles.chipRow}
            onClick={() => chipInputRef.current?.focus()}
          >
            {form.toxicityWords.map(word => (
              <span key={word} className={styles.chip}>
                {word}
                <button
                  type="button"
                  className={styles.chipRemove}
                  onClick={e => {
                    e.stopPropagation()
                    removeChip(word)
                  }}
                  aria-label={`Remove ${word}`}
                >
                  &#x2715;
                </button>
              </span>
            ))}
            <input
              ref={chipInputRef}
              type="text"
              className={styles.chipInput}
              value={chipInput}
              placeholder={form.toxicityWords.length === 0 ? 'Type a word, press Enter or comma to add\u2026' : ''}
              onChange={e => setChipInput(e.target.value)}
              onKeyDown={handleChipKey}
              onBlur={addChip}
            />
          </div>
          <p className={styles.tooltip}>
            Words that raise the toxicity signal score. Type a word and press Enter or comma to add; click &times; to remove.
          </p>
        </div>
      </div>

      <div className={styles.card}>
        <div className={styles.sectionLabel}>&mdash; Description &mdash;</div>
        <div className={styles.fieldFull}>
          <label className={styles.fieldLabel} htmlFor="description">
            Description
          </label>
          <textarea
            id="description"
            className={`${styles.input} ${styles.textarea}`}
            value={form.description}
            onChange={e => set('description', e.target.value)}
            rows={3}
            placeholder="Optional description of this configuration set"
          />
        </div>
      </div>

      <div className={styles.actions}>
        <Button variant="primary" onClick={handleSave} disabled={saving}>
          {saving ? 'Saving\u2026' : 'Save'}
        </Button>
      </div>
    </div>
  )
}
