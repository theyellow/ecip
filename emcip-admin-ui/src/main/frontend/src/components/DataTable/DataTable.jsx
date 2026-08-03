import { useState } from 'react'
import { Button } from '../Button/Button'
import { ConfirmDialog } from '../ConfirmDialog/ConfirmDialog'
import styles from './DataTable.module.css'

export function DataTable({
  title,
  systemId,
  addLabel,
  onAdd,
  columns,
  rows,
  rowKey = r => r.id,
  onEdit,
  onDelete,
  deleteMessage,
  filters,
  emptyText = 'No records',
}) {
  const [confirming, setConfirming] = useState(null)

  return (
    <div className={styles.wrapper}>
      <div className={styles.pageHeader}>
        <div>
          <h2>{title}</h2>
          {systemId && <div className={styles.systemId}>{systemId}</div>}
        </div>
        <div className={styles.controls}>
          {filters?.map((f, i) => (
            // index key is stable here: filters is a static per-page prop array
            <select key={i} className={styles.filter} value={f.value} onChange={f.onChange}>
              {f.options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          ))}
          {addLabel && onAdd && <Button onClick={onAdd}>{addLabel}</Button>}
        </div>
      </div>

      <table className={styles.table}>
        <thead>
          <tr>
            {columns.map(c => (
              <th key={c.key} style={c.width ? { width: c.width } : undefined}>{c.label}</th>
            ))}
            {onDelete && <th className={styles.actionsSticky} style={{ width: 80 }}></th>}
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr
              key={rowKey(row)}
              className={onEdit ? styles.clickable : undefined}
              onClick={onEdit ? () => onEdit(row) : undefined}
            >
              {columns.map(c => (
                <td key={c.key} className={c.mono ? styles.mono : undefined}>
                  {c.render ? c.render(row[c.key], row) : (row[c.key] ?? '\u2014')}
                </td>
              ))}
              {onDelete && (
                <td className={styles.actionsSticky} onClick={e => e.stopPropagation()}>
                  <Button variant="danger" onClick={() => setConfirming(row)}>Delete</Button>
                </td>
              )}
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={columns.length + (onDelete ? 1 : 0)} className={styles.empty}>
                {emptyText}
              </td>
            </tr>
          )}
        </tbody>
      </table>

      {confirming && (
        <ConfirmDialog
          title="Delete record"
          message={deleteMessage ? deleteMessage(confirming) : 'Delete this record? This cannot be undone.'}
          onConfirm={() => { onDelete(confirming); setConfirming(null) }}
          onClose={() => setConfirming(null)}
        />
      )}
    </div>
  )
}
