import styles from './SectionLabel.module.css'

export function SectionLabel({ children, aside }) {
  return (
    <div className={styles.label}>
      <span>&mdash; {children} &mdash;</span>
      {aside && <span className={styles.aside}>{aside}</span>}
    </div>
  )
}
