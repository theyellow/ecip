import React, { useEffect, useState } from 'react'
import styles from './RuleHistoryTab.module.css'

export function RuleHistoryTab({ ruleId, api }) {
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)
  const [openDiff, setOpenDiff] = useState(null)

  useEffect(() => {
    if (!ruleId) { setLoading(false); return }
    api.getHistory(ruleId)
      .then(h => setHistory(Array.isArray(h) ? h : []))
      .catch(() => setHistory([]))
      .finally(() => setLoading(false))
  }, [ruleId])

  if (loading) return <p className={styles.muted}>Loading\u2026</p>
  if (history.length === 0) return <p className={styles.empty}>No history recorded yet.</p>

  return (
    <div className={styles.root}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>Version</th>
            <th>Edited By</th>
            <th>When</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {history.map((h, i) => (
            <React.Fragment key={h.ruleVersion ?? i}>
              <tr>
                <td className={styles.mono}>v{h.ruleVersion} {'\u2192'} v{(h.ruleVersion ?? 0) + 1}</td>
                <td className={styles.mono}>{h.editedBy ?? '\u2014'}</td>
                <td className={styles.muted}>{h.editedAt ? new Date(h.editedAt).toLocaleString() : '\u2014'}</td>
                <td>
                  <button className={styles.diffToggle}
                    onClick={() => setOpenDiff(openDiff === i ? null : i)}>
                    {openDiff === i ? 'Hide diff' : 'View diff'}
                  </button>
                </td>
              </tr>
              {openDiff === i && (
                <tr>
                  <td colSpan={4}>
                    <div className={styles.diff}>
                      <div className={styles.diffLabel}>— Snapshot at v{h.ruleVersion} —</div>
                      <pre className={styles.diffPre}>{JSON.stringify(h.snapshot, null, 2)}</pre>
                    </div>
                  </td>
                </tr>
              )}
            </React.Fragment>
          ))}
        </tbody>
      </table>
    </div>
  )
}
