import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { costsApi } from '../../api/costs'
import styles from './Costs.module.css'

const TIME_PRESETS = [
  { value: 'today', label: 'Today' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
  { value: 'thismonth', label: 'This month' },
  { value: 'lastmonth', label: 'Last month' },
  { value: 'custom', label: 'Custom range\u2026' },
]

function presetToRange(preset) {
  const now = new Date()
  if (preset === 'today') {
    const start = new Date(now)
    start.setHours(0, 0, 0, 0)
    return { from: start.toISOString(), to: now.toISOString() }
  }
  if (preset === '7d') return { from: new Date(now - 7 * 86400000).toISOString(), to: now.toISOString() }
  if (preset === '30d') return { from: new Date(now - 30 * 86400000).toISOString(), to: now.toISOString() }
  if (preset === 'thismonth') {
    return { from: new Date(now.getFullYear(), now.getMonth(), 1).toISOString(), to: now.toISOString() }
  }
  if (preset === 'lastmonth') {
    return {
      from: new Date(now.getFullYear(), now.getMonth() - 1, 1).toISOString(),
      to: new Date(now.getFullYear(), now.getMonth(), 1).toISOString(),
    }
  }
  return { from: null, to: null }
}

function formatTokens(n) {
  if (n == null) return '\u2014'
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K'
  return String(n)
}

function formatCost(n) {
  if (n == null) return '\u2014'
  return '$' + n.toFixed(4)
}

export function Costs() {
  const api = costsApi(useAuthRequest())
  const [timePreset, setTimePreset] = useState('30d')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const [totals, setTotals] = useState(null)
  const [byModel, setByModel] = useState([])
  const [byDay, setByDay] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    const range =
      timePreset === 'custom'
        ? {
            from: customFrom ? new Date(customFrom).toISOString() : null,
            to: customTo ? new Date(customTo).toISOString() : null,
          }
        : presetToRange(timePreset)

    if (!range.from || !range.to) return

    setLoading(true)
    setError('')
    Promise.all([
      api.totals(range.from, range.to),
      api.byModel(range.from, range.to),
      api.byDay(range.from, range.to),
    ])
      .then(([t, m, d]) => {
        setTotals(t)
        setByModel(m ?? [])
        setByDay(d ?? [])
      })
      .catch(e => setError(e.message || 'Failed to load cost data'))
      .finally(() => setLoading(false))
  }, [timePreset, customFrom, customTo])

  const maxCalls = byDay.length > 0 ? Math.max(...byDay.map(d => d.callCount)) : 0

  return (
    <>
      <div className={styles.pageHeader}>
        <div>
          <h2>LLM Costs</h2>
          <div className={styles.systemId}>{'\u2726'} llm-orchestrator {'\u00b7'} model_cost_logs</div>
        </div>
        <div className={styles.filters}>
          <select
            value={timePreset}
            onChange={e => setTimePreset(e.target.value)}
            className={styles.select}
          >
            {TIME_PRESETS.map(o => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
          {timePreset === 'custom' && (
            <>
              <input
                type="datetime-local"
                className={styles.filterInput}
                value={customFrom}
                onChange={e => setCustomFrom(e.target.value)}
              />
              <input
                type="datetime-local"
                className={styles.filterInput}
                value={customTo}
                onChange={e => setCustomTo(e.target.value)}
              />
            </>
          )}
        </div>
      </div>

      {error && <p role="alert" className={styles.alertBanner}>{error}</p>}

      {loading && <p className={styles.mono} style={{ textAlign: 'center', padding: 'var(--sp-5)' }}>Loading{'\u2026'}</p>}

      {!loading && totals && (
        <>
          <div className={styles.summaryRow}>
            <div className={styles.summaryCard}>
              <span className={styles.summaryValue}>${(totals.totalCostUsd ?? 0).toFixed(2)}</span>
              <span className={styles.summaryLabel}>Total Cost</span>
            </div>
            <div className={styles.summaryCard}>
              <span className={styles.summaryValue}>{formatTokens(totals.totalTokens)}</span>
              <span className={styles.summaryLabel}>Total Tokens</span>
            </div>
            <div className={styles.summaryCard}>
              <span className={styles.summaryValue}>
                {totals.successCount ?? 0} / {totals.failureCount ?? 0}
              </span>
              <span className={styles.summaryLabel}>Calls (ok / fail)</span>
            </div>
            <div className={styles.summaryCard}>
              <span className={styles.summaryValue}>{Math.round(totals.avgLatencyMs ?? 0)}ms</span>
              <span className={styles.summaryLabel}>Avg Latency</span>
            </div>
          </div>

          <div className={styles.chartSection}>
            <div className={styles.sectionLabel}>Calls per day</div>
            {byDay.length === 0 ? (
              <div className={styles.chartEmpty}>No data for this period</div>
            ) : (
              <>
                <div className={styles.chartContainer}>
                  {byDay.map((d, i) => (
                    <div key={i} className={styles.chartBarWrap}>
                      <div
                        className={styles.chartBar}
                        style={{ height: maxCalls > 0 ? `${(d.callCount / maxCalls) * 100}%` : '2px' }}
                        title={`${d.date}: ${d.callCount} calls, ${formatCost(d.totalCostUsd)}, ${formatTokens(d.totalTokens)} tokens`}
                      />
                    </div>
                  ))}
                </div>
                <div style={{ display: 'flex', gap: '2px' }}>
                  {byDay.map((d, i) => (
                    <div key={i} className={styles.chartLabel} style={{ flex: 1 }}>
                      {d.date?.slice(5)}
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>

          <div className={styles.sectionLabel}>By model</div>
          <div className={styles.tableWrapper}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>Model</th>
                  <th className={styles.right}>Calls</th>
                  <th className={styles.right}>Input Tokens</th>
                  <th className={styles.right}>Output Tokens</th>
                  <th className={styles.right}>Total Tokens</th>
                  <th className={styles.right}>Cost</th>
                  <th className={styles.right}>Avg Latency</th>
                </tr>
              </thead>
              <tbody>
                {byModel.length === 0 && (
                  <tr>
                    <td colSpan={7} style={{ textAlign: 'center', color: 'var(--fg-3)', padding: 'var(--sp-5)', fontStyle: 'italic' }}>
                      No LLM calls recorded for this period
                    </td>
                  </tr>
                )}
                {byModel.map((m, i) => (
                  <tr key={i}>
                    <td>{m.modelName}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{m.callCount}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{formatTokens(m.inputTokens)}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{formatTokens(m.outputTokens)}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{formatTokens(m.totalTokens)}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{formatCost(m.totalCostUsd)}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{Math.round(m.avgLatencyMs)}ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  )
}
