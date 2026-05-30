import styles from './SegmentedControl.module.css'

export function SegmentedControl({ options, value, onChange }) {
  return (
    <div className={styles.container}>
      {options.map(opt => (
        <button
          key={opt.value}
          type="button"
          className={`${styles.option} ${value === opt.value ? styles.active : ''}`}
          onClick={() => onChange(opt.value)}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}
