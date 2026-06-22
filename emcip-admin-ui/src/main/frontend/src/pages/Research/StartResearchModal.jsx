import { useState } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { Modal } from '../../components/Modal/Modal'
import { SegmentedControl } from '../../components/SegmentedControl/SegmentedControl'
import { researchApi } from '../../api/research'

const TEMPLATE_OPTIONS = [
  { value: 'TOPIC', label: 'Topic' },
  { value: 'PERSON', label: 'Person' },
  { value: 'FACT_CHECK', label: 'Fact Check' },
]

export function StartResearchModal({ onClose, onStarted }) {
  const { currentTenant } = useAuth()
  const request = useAuthRequest()

  const [question, setQuestion] = useState('')
  const [template, setTemplate] = useState('TOPIC')
  const [webSearch, setWebSearch] = useState(false)
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [maxIterations, setMaxIterations] = useState(10)
  const [maxLlmCalls, setMaxLlmCalls] = useState(20)
  const [costLimit, setCostLimit] = useState(1.0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit() {
    if (!question.trim()) {
      setError('Question is required.')
      return
    }
    setError('')
    setLoading(true)
    try {
      const body = {
        question: question.trim(),
        reportTemplate: template,
        webSearchEnabled: webSearch,
        maxIterations: Number(maxIterations) || 10,
        maxLlmCalls: Number(maxLlmCalls) || 20,
        costLimitUsd: Number(costLimit) || 1.0,
      }
      if (currentTenant?.id) {
        body.tenantId = currentTenant.id
      }
      const session = await researchApi(request).startSession(body)
      onStarted(session)
    } catch (e) {
      setError(e?.body?.message ?? 'Failed to start research session.')
      setLoading(false)
    }
  }

  return (
    <Modal
      title="START RESEARCH SESSION"
      onClose={onClose}
      onSubmit={handleSubmit}
      submitLabel={loading ? 'Running\u2026' : 'Run Research'}
    >
      <label
        style={{
          display: 'block',
          marginBottom: 'var(--sp-1)',
          fontFamily: 'var(--font-mono)',
          fontSize: 11,
          color: 'var(--fg-2)',
        }}
      >
        QUESTION *
      </label>
      <textarea
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
        rows={4}
        placeholder="What do you want to research? e.g. 'Is John Smith coordinating disinformation campaigns?'"
        disabled={loading}
        style={{
          width: '100%',
          boxSizing: 'border-box',
          padding: 'var(--sp-2) var(--sp-3)',
          background: 'var(--bg-input)',
          border: '1px solid var(--border)',
          color: 'var(--fg-1)',
          fontFamily: 'var(--font-body)',
          fontSize: 14,
          lineHeight: 1.5,
          resize: 'vertical',
        }}
      />

      <div style={{ marginTop: 'var(--sp-4)' }}>
        <div
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            color: 'var(--fg-2)',
            marginBottom: 'var(--sp-2)',
          }}
        >
          REPORT TEMPLATE
        </div>
        <SegmentedControl options={TEMPLATE_OPTIONS} value={template} onChange={setTemplate} />
      </div>

      <label
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 'var(--sp-2)',
          marginTop: 'var(--sp-4)',
          cursor: 'pointer',
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
          color: 'var(--fg-2)',
        }}
      >
        <input
          type="checkbox"
          checked={webSearch}
          onChange={(e) => setWebSearch(e.target.checked)}
          disabled={loading}
          style={{ width: 16, height: 16, accentColor: 'var(--accent)', cursor: 'pointer' }}
        />
        Enable web search (SearXNG / Brave fallback)
      </label>

      <div style={{ marginTop: 'var(--sp-4)' }}>
        <button
          type="button"
          onClick={() => setShowAdvanced((v) => !v)}
          style={{
            background: 'none',
            border: 'none',
            color: 'var(--accent)',
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            cursor: 'pointer',
            padding: 0,
            letterSpacing: '0.08em',
          }}
        >
          {showAdvanced ? '\u25be' : '\u25b8'} ADVANCED SETTINGS
        </button>

        {showAdvanced && (
          <div
            style={{
              marginTop: 'var(--sp-3)',
              display: 'grid',
              gridTemplateColumns: '1fr 1fr 1fr',
              gap: 'var(--sp-3)',
            }}
          >
            <div>
              <label
                style={{
                  fontFamily: 'var(--font-mono)',
                  fontSize: 11,
                  color: 'var(--fg-2)',
                  display: 'block',
                  marginBottom: 4,
                }}
              >
                MAX ITERATIONS
              </label>
              <input
                type="number"
                min={1}
                max={50}
                value={maxIterations}
                onChange={(e) => setMaxIterations(e.target.value)}
                disabled={loading}
                style={{
                  width: '100%',
                  padding: '6px 8px',
                  background: 'var(--bg-input)',
                  border: '1px solid var(--border)',
                  color: 'var(--fg-1)',
                  fontFamily: 'var(--font-mono)',
                  fontSize: 13,
                  boxSizing: 'border-box',
                }}
              />
            </div>
            <div>
              <label
                style={{
                  fontFamily: 'var(--font-mono)',
                  fontSize: 11,
                  color: 'var(--fg-2)',
                  display: 'block',
                  marginBottom: 4,
                }}
              >
                MAX LLM CALLS
              </label>
              <input
                type="number"
                min={1}
                max={100}
                value={maxLlmCalls}
                onChange={(e) => setMaxLlmCalls(e.target.value)}
                disabled={loading}
                style={{
                  width: '100%',
                  padding: '6px 8px',
                  background: 'var(--bg-input)',
                  border: '1px solid var(--border)',
                  color: 'var(--fg-1)',
                  fontFamily: 'var(--font-mono)',
                  fontSize: 13,
                  boxSizing: 'border-box',
                }}
              />
            </div>
            <div>
              <label
                style={{
                  fontFamily: 'var(--font-mono)',
                  fontSize: 11,
                  color: 'var(--fg-2)',
                  display: 'block',
                  marginBottom: 4,
                }}
              >
                COST LIMIT (USD)
              </label>
              <input
                type="number"
                min={0.01}
                step={0.1}
                value={costLimit}
                onChange={(e) => setCostLimit(e.target.value)}
                disabled={loading}
                style={{
                  width: '100%',
                  padding: '6px 8px',
                  background: 'var(--bg-input)',
                  border: '1px solid var(--border)',
                  color: 'var(--fg-1)',
                  fontFamily: 'var(--font-mono)',
                  fontSize: 13,
                  boxSizing: 'border-box',
                }}
              />
            </div>
          </div>
        )}
      </div>

      {loading && (
        <p
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 12,
            color: 'var(--accent)',
            marginTop: 'var(--sp-3)',
          }}
        >
          \u25c8 Running research\u2026 this may take up to 30 seconds.
        </p>
      )}

      {error && (
        <p
          style={{
            color: 'var(--signal-stop-fg)',
            fontFamily: 'var(--font-mono)',
            fontSize: 12,
            marginTop: 'var(--sp-2)',
          }}
        >
          {error}
        </p>
      )}
    </Modal>
  )
}
