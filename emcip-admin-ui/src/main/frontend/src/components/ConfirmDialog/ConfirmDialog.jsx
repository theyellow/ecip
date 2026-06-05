import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '../Button/Button'
import styles from '../Modal/Modal.module.css'

export function ConfirmDialog({ title = 'Confirm', message, confirmLabel = 'Delete', onConfirm, onClose }) {
  useEffect(() => {
    const h = e => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', h)
    return () => document.removeEventListener('keydown', h)
  }, [onClose])

  return createPortal(
    <div className={styles.overlay} onClick={e => e.target === e.currentTarget && onClose()}>
      <div className={styles.card} role="alertdialog" aria-modal="true" aria-labelledby="confirm-title">
        <div className={styles.header}>
          <h3 id="confirm-title">{title}</h3>
          <button className={styles.close} onClick={onClose} aria-label="Close">✕</button>
        </div>
        <div className={styles.body}>
          <p style={{ margin: 0, color: 'var(--fg-1)', lineHeight: 1.6 }}>{message}</p>
        </div>
        <div className={styles.footer}>
          <Button variant="secondary" onClick={onClose}>Cancel</Button>
          <Button variant="danger" onClick={onConfirm}>{confirmLabel}</Button>
        </div>
      </div>
    </div>,
    document.body
  )
}
