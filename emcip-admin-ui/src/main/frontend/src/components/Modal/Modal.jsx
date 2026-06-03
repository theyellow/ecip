import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import styles from './Modal.module.css'
import { Button } from '../Button/Button'

export function Modal({ title, onClose, onSubmit, submitLabel = 'Save', children }) {
  useEffect(() => {
    const handler = e => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [onClose])

  return createPortal(
    <div className={styles.overlay} onClick={e => e.target === e.currentTarget && onClose()}>
      <div className={styles.card} role="dialog" aria-modal="true" aria-labelledby="modal-title">
        <div className={styles.header}>
          <h3 id="modal-title">{title}</h3>
          <button className={styles.close} onClick={onClose} aria-label="Close">✕</button>
        </div>
        <div className={styles.body}>{children}</div>
        {onSubmit && (
          <div className={styles.footer}>
            <Button variant="secondary" onClick={onClose}>Cancel</Button>
            <Button variant="primary" onClick={onSubmit}>{submitLabel}</Button>
          </div>
        )}
      </div>
    </div>,
    document.body
  )
}
