import { useEffect } from 'react';
import styles from './Toast.module.css';

const TYPE_LABELS = {
  success: 'SUCCESS',
  error: 'ERROR',
  info: 'INFO',
  warning: 'WARNING',
};

const DEFAULT_DURATIONS = {
  success: 5000,
  error: 8000,
  info: 5000,
  warning: 5000,
};

export default function Toast({ id, type, message, duration, onDismiss }) {
  const effectiveDuration = duration ?? DEFAULT_DURATIONS[type] ?? 5000;

  useEffect(() => {
    const timer = setTimeout(() => onDismiss(id), effectiveDuration);
    return () => clearTimeout(timer);
  }, [id, effectiveDuration, onDismiss]);

  return (
    <div className={`${styles.toast} ${styles[type] || ''}`}>
      <div className={styles.body}>
        <div className={styles.typeLabel}>{TYPE_LABELS[type] || type}</div>
        <div className={styles.message}>{message}</div>
      </div>
      <button className={styles.close} onClick={() => onDismiss(id)}>
        &#x2715;
      </button>
    </div>
  );
}
