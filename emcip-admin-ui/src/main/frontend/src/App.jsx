import { Component } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ThemeProvider } from './theme/ThemeContext'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { AppShell } from './layout/AppShell/AppShell'
import { SpaceBackground } from './layout/SpaceBackground/SpaceBackground'
import { Login } from './pages/Login/Login'
import { Groups } from './pages/Groups/Groups'
import { Tenants } from './pages/Tenants/Tenants'
import { PolicyRules } from './pages/PolicyRules/PolicyRules'
import { AuditLog } from './pages/AuditLog/AuditLog'
import { Simulate } from './pages/Simulate/Simulate'
import { Telegram } from './pages/Telegram/Telegram'
import { AIConfig } from './pages/AIConfig/AIConfig'
import { ModerationRules } from './pages/ModerationRules/ModerationRules'
import { Flags } from './pages/Flags/Flags'
import { Users } from './pages/Users/Users'

class PageErrorBoundary extends Component {
  state = { error: null }
  static getDerivedStateFromError(error) { return { error } }
  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: '2rem', color: 'var(--color-error, red)' }}>
          <strong>Page error:</strong> {this.state.error.message}
          <br />
          <button onClick={() => this.setState({ error: null })} style={{ marginTop: '1rem' }}>
            Retry
          </button>
        </div>
      )
    }
    return this.props.children
  }
}

function AuthGate() {
  const { token } = useAuth()

  if (!token) {
    return <Login onSuccess={() => {}} />
  }

  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<Navigate to="/telegram" replace />} />
        <Route path="tenants" element={<Tenants />} />
        <Route path="policy-rules" element={<PolicyRules />} />
        <Route path="groups" element={<Groups />} />
        <Route path="audit-log" element={<PageErrorBoundary><AuditLog /></PageErrorBoundary>} />
        <Route path="simulate" element={<Simulate />} />
        <Route path="telegram" element={<Telegram />} />
        <Route path="ai-config" element={<AIConfig />} />
        <Route path="moderation-rules" element={<ModerationRules />} />
        <Route path="flags" element={<Flags />} />
        <Route path="users" element={<Users />} />
      </Route>
    </Routes>
  )
}

export function App() {
  return (
    <BrowserRouter>
      <ThemeProvider>
        <AuthProvider>
          <SpaceBackground />
          <AuthGate />
        </AuthProvider>
      </ThemeProvider>
    </BrowserRouter>
  )
}
