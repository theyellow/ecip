import { createContext, useContext, useState } from 'react'
import { makeRefreshableRequest } from '../api/client'

const API_BASE = import.meta.env.VITE_API_BASE ?? ''
const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [token, setToken] = useState(() => sessionStorage.getItem('emcip-token'))

  const login = async (username, password) => {
    const res = await fetch(`${API_BASE}/api/auth/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    })
    if (!res.ok) throw new Error('Invalid credentials')
    const data = await res.json()
    sessionStorage.setItem('emcip-token', data.token)
    sessionStorage.setItem('emcip-refresh-token', data.refreshToken)
    setToken(data.token)
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
    setToken(null)
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
    sessionStorage.setItem('emcip-token', data.token)
    sessionStorage.setItem('emcip-refresh-token', data.refreshToken)
    setToken(data.token)
    return data.token
  }

  return (
    <AuthContext.Provider value={{ token, login, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}

/** Returns a fetch function that auto-refreshes on 401 and logs out on refresh failure. */
export function useAuthRequest() {
  const { token, refresh, logout } = useAuth()
  return makeRefreshableRequest(token ?? '', refresh, logout)
}
