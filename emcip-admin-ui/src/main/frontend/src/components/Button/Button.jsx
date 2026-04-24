import styles from './Button.module.css'

export function Button({ variant = 'primary', children, ...props }) {
  return (
    <button className={`${styles.btn} ${styles[variant]}`} {...props}>
      {children}
    </button>
  )
}
