import { NavLink } from 'react-router-dom'
import { Logo } from '../../logo/Logo'
import { useTheme } from '../../theme/ThemeContext'
import styles from './Sidebar.module.css'

const NAV = [
  { to: '/tenants',      label: 'Tenants',       icon: '⬡' },
  { to: '/policy-rules',     label: 'Policy Rules',     icon: '⚖' },
  { to: '/moderation-rules', label: 'Moderation Rules', icon: '⊘' },
  { to: '/groups',           label: 'Groups',           icon: '◈' },
  { to: '/audit-log',    label: 'Audit Log',      icon: '◎' },
  { to: '/simulate',     label: 'Simulate Event', icon: '▶' },
  { to: '/telegram',     label: 'Telegram',       icon: '⌘' },
  { to: '/ai-config',    label: 'AI Config',      icon: '✦' },
]

export function Sidebar() {
  const { theme, toggleTheme } = useTheme()

  return (
    <aside className={styles.sidebar}>
      <div className={styles.brand}>
        <Logo size={32} className={styles.logo} />
        <span className={`emcip-wordmark ${styles.wordmark}`}>EMCIP</span>
      </div>

      <nav className={styles.nav}>
        {NAV.map(({ to, label, icon }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) =>
              `${styles.item} ${isActive ? styles.active : ''}`
            }
          >
            <span className={styles.icon}>{icon}</span>
            {label}
          </NavLink>
        ))}
      </nav>

      <div className={styles.footer}>
        <button
          className={styles.themeToggle}
          onClick={toggleTheme}
          aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
          title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
        >
          {theme === 'light' ? '☽' : '☀'}
        </button>
      </div>
    </aside>
  )
}
