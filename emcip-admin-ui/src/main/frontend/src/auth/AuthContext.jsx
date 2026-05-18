import { createContext, useContext, useState } from 'react'

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
    setToken(data.token)
  }

  const logout = () => {
    sessionStorage.removeItem('emcip-token')
    setToken(null)
  }

  return (
    <AuthContext.Provider value={{ token, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
