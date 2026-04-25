import styles from './Badge.module.css'

export function Badge({ variant = 'gray', children }) {
  return <span className={`${styles.badge} ${styles[variant]}`}>{children}</span>
}
