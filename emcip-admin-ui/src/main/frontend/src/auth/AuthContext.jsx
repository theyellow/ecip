import { createContext, useContext, useState } from 'react'
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

  const setCurrentTenant = (tenant) => {
    if (tenant) {
      sessionStorage.setItem('emcip-current-tenant', JSON.stringify(tenant))
    } else {
      sessionStorage.removeItem('emcip-current-tenant')
    }
    setCurrentTenantState(tenant)
  }

  const login = async (username, password) => {
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
  }

  const logout = () => {
    const rt = sessionStorage.getItem('emcip-refresh-token')
    if (rt) {
      fetch(`${API_BASE}/api/auth/logout`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: rt }),
      }).catch(() => {})
    }
    sessionStorage.removeItem('emcip-token')
    sessionStorage.removeItem('emcip-refresh-token')
    sessionStorage.removeItem('emcip-current-tenant')
    setToken(null)
    setRole(null)
    setTenantId(null)
    setCurrentTenantState(null)
  }

  const refresh = async () => {
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
  }

  return (
    <AuthContext.Provider
      value={{ token, role, tenantId, currentTenant, setCurrentTenant, login, logout, refresh }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within <AuthProvider>')
  return context
}

/** Returns a fetch function that auto-refreshes on 401 and logs out on refresh failure. */
export function useAuthRequest() {
  const { token, role, currentTenant, refresh, logout } = useAuth()
  return makeRefreshableRequest(token ?? '', role, currentTenant, refresh, logout)
}
