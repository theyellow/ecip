import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import { makeRefreshableRequest } from '../api/client'

const API_BASE = import.meta.env.VITE_API_BASE ?? ''
const AuthContext = createContext(null)

function decodeJwt(token) {
  try {
    const payload = token.split('.')[1]
    return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
  } catch {
    return null
  }
}

function storedTenant() {
  const raw = sessionStorage.getItem('emcip-current-tenant')
  return raw ? JSON.parse(raw) : null
}

export function AuthProvider({ children }) {
  const [token, setToken] = useState(() => sessionStorage.getItem('emcip-token'))
  const [role, setRole] = useState(() => {
    const t = sessionStorage.getItem('emcip-token')
    return t ? (decodeJwt(t)?.role ?? null) : null
  })
  const [tenantId, setTenantId] = useState(() => {
    const t = sessionStorage.getItem('emcip-token')
    return t ? (decodeJwt(t)?.tenantId ?? null) : null
  })
  const [currentTenant, setCurrentTenantState] = useState(() => storedTenant())

  // Everything this provider hands out is memoized, and it has to stay that way.
  // Consumers build their api client with useMemo([request]) and their loaders with
  // useCallback([api]); a value that changes identity on every render silently turns
  // those "run once on mount" effects into a request loop that only stops when the
  // fetched state stops changing. Deps are the setState functions and sessionStorage,
  // both stable, so an empty dep list is correct rather than merely convenient.
  const setCurrentTenant = useCallback((tenant) => {
    if (tenant) {
      sessionStorage.setItem('emcip-current-tenant', JSON.stringify(tenant))
    } else {
      sessionStorage.removeItem('emcip-current-tenant')
    }
    setCurrentTenantState(tenant)
  }, [])

  const login = useCallback(async (username, password) => {
    const res = await fetch(`${API_BASE}/api/auth/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    })
    if (!res.ok) throw new Error(`Login failed: ${res.status}`)
    const data = await res.json()
    const payload = decodeJwt(data.token)
    const newRole = payload?.role ?? null
    const newTenantId = payload?.tenantId ?? null

    sessionStorage.setItem('emcip-token', data.token)
    sessionStorage.setItem('emcip-refresh-token', data.refreshToken)
    setToken(data.token)
    setRole(newRole)
    setTenantId(newTenantId)

    // Non-ADMIN roles: lock currentTenant to their JWT-embedded tenant
    if (newRole !== 'ADMIN' && newTenantId) {
      const tenant = { id: newTenantId, name: payload?.tenantName ?? newTenantId }
      setCurrentTenant(tenant)
    } else {
      setCurrentTenant(null)
    }
  }, [setCurrentTenant])

  const logout = useCallback(() => {
    const rt = sessionStorage.getItem('emcip-refresh-token')
    if (rt) {
      fetch(`${API_BASE}/api/auth/logout`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: rt }),
      }).catch(e => console.warn('Auth logout cleanup failed:', e?.message || e))
    }
    sessionStorage.removeItem('emcip-token')
    sessionStorage.removeItem('emcip-refresh-token')
    sessionStorage.removeItem('emcip-current-tenant')
    setToken(null)
    setRole(null)
    setTenantId(null)
    setCurrentTenantState(null)
  }, [])

  const refresh = useCallback(async () => {
    const rt = sessionStorage.getItem('emcip-refresh-token')
    if (!rt) throw new Error('No refresh token')
    const res = await fetch(`${API_BASE}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt }),
    })
    if (!res.ok) throw new Error('Refresh failed')
    const data = await res.json()
    const payload = decodeJwt(data.token)
    sessionStorage.setItem('emcip-token', data.token)
    sessionStorage.setItem('emcip-refresh-token', data.refreshToken)
    setToken(data.token)
    setRole(payload?.role ?? null)
    setTenantId(payload?.tenantId ?? null)
    return data.token
  }, [])

  const value = useMemo(
    () => ({ token, role, tenantId, currentTenant, setCurrentTenant, login, logout, refresh }),
    [token, role, tenantId, currentTenant, setCurrentTenant, login, logout, refresh],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within <AuthProvider>')
  return context
}

/**
 * Returns a fetch function that auto-refreshes on 401 and logs out on refresh failure.
 *
 * The returned function is stable: it changes identity only when something that changes
 * what the request actually sends changes. Callers memoize api clients and effects on it,
 * so handing back a new function per render re-arms those effects and produces a fetch
 * loop rather than a single load.
 */
export function useAuthRequest() {
  const { token, role, currentTenant, refresh, logout } = useAuth()
  return useMemo(
    () => makeRefreshableRequest(token ?? '', role, currentTenant, refresh, logout),
    [token, role, currentTenant, refresh, logout],
  )
}
