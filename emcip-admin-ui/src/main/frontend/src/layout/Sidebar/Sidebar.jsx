import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { hasPermission } from '../../auth/permissions'
import { tenantsApi } from '../../api/tenants'
import { Logo } from '../../logo/Logo'
import { useTheme } from '../../theme/ThemeContext'
import styles from './Sidebar.module.css'

const NAV = [
  { to: '/tenants',          label: 'Tenants',          icon: '⬡', permission: 'TENANTS_READ' },
  { to: '/intent-rules',     label: 'Intent Rules',     icon: '✦', permission: 'INTENT_RULES_READ' },
  { to: '/policy-rules',     label: 'Policy Rules',     icon: '⚖', permission: 'POLICY_RULES_READ' },
  { to: '/moderation-rules', label: 'Moderation Rules', icon: '⊘', permission: 'MODERATION_RULES_READ' },
  { to: '/decisions',        label: 'Decisions',        icon: '⚑',       permission: 'AUDIT_READ' },
  { to: '/resolution-queue', label: 'Resolution Queue', icon: '\u2297', permission: 'RESOLUTION_REVIEW_READ' },
  { to: '/groups',           label: 'Watched Groups',    icon: '◈',       permission: 'GROUPS_READ' },
  { to: '/knowledge',        label: 'Knowledge',        icon: '◆',       permission: 'KNOWLEDGE_READ' },
  { to: '/research',         label: 'Research',         icon: '⬟',       permission: 'KNOWLEDGE_READ' },
  { to: '/audit-log',        label: 'Audit Log',        icon: '◎', permission: 'AUDIT_READ' },
  { to: '/simulate',         label: 'Simulate Event',   icon: '▶', permission: 'SIMULATE_WRITE' },
  { to: '/telegram',         label: 'Telegram',         icon: '⌘', permission: 'TELEGRAM_READ' },
  { to: '/ai-config',        label: 'AI Config',        icon: '✦', permission: 'AI_CONFIG_READ' },
  { to: '/costs',            label: 'LLM Costs',         icon: '\u229B', permission: 'COSTS_READ' },
  { to: '/integrations',    label: 'Integrations',     icon: '\u2295', permission: 'INTEGRATIONS_TENANT_MANAGE' },
  { to: '/users',            label: 'Users',            icon: '◉', permission: 'USERS_READ' },
]

export function Sidebar() {
  const { theme, toggleTheme } = useTheme()
  const { role, currentTenant, setCurrentTenant, logout } = useAuth()
  const request = useAuthRequest()
  const [tenants, setTenants] = useState([])

  useEffect(() => {
    if (role === 'ADMIN') {
      tenantsApi(request).list()
        .then(setTenants)
        .catch(() => {})
    }
  }, [role])

  const handleTenantChange = (e) => {
    const id = e.target.value
    if (!id) {
      setCurrentTenant(null)
    } else {
      const found = tenants.find(t => t.id === id)
      setCurrentTenant(found ? { id: found.id, name: found.name } : null)
    }
  }

  return (
    <aside className={styles.sidebar}>
      <div className={styles.brand}>
        <Logo size={32} className={styles.logo} />
        <span className={`emcip-wordmark ${styles.wordmark}`}>EMCIP</span>
      </div>

      <div className={styles.tenantSwitcher}>
        <div className={styles.tenantLabel}>Tenant</div>
        {role === 'ADMIN' ? (
          <select
            className={styles.tenantSelect}
            value={currentTenant?.id ?? ''}
            onChange={handleTenantChange}
            aria-label="Select active tenant"
          >
            <option value="">All Tenants</option>
            {tenants.map(t => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </select>
        ) : (
          <span className={styles.tenantStaticName}>
            {currentTenant?.name ?? '—'}
          </span>
        )}
      </div>

      <nav className={styles.nav}>
        {NAV.filter(({ permission }) => hasPermission(role, permission)).map(({ to, label, icon }) => (
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
        <button
          className={styles.logoutBtn}
          onClick={logout}
          aria-label="Logout"
        >
          ⏻ Logout
        </button>
      </div>
    </aside>
  )
}
