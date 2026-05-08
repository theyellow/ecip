import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ThemeProvider } from './theme/ThemeContext'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { AppShell } from './layout/AppShell/AppShell'
import { Login } from './pages/Login/Login'
import { Groups } from './pages/Groups/Groups'
import { Tenants } from './pages/Tenants/Tenants'
import { PolicyRules } from './pages/PolicyRules/PolicyRules'
import { AuditLog } from './pages/AuditLog/AuditLog'
import { Simulate } from './pages/Simulate/Simulate'
import { Telegram } from './pages/Telegram/Telegram'
import { AIConfig } from './pages/AIConfig/AIConfig'
import { ModerationRules } from './pages/ModerationRules/ModerationRules'

function AuthGate() {
  const { token } = useAuth()

  if (!token) {
    return <Login onSuccess={() => {}} />
  }

  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<Navigate to="/tenants" replace />} />
        <Route path="tenants" element={<Tenants />} />
        <Route path="policy-rules" element={<PolicyRules />} />
        <Route path="groups" element={<Groups />} />
        <Route path="audit-log" element={<AuditLog />} />
        <Route path="simulate" element={<Simulate />} />
        <Route path="telegram" element={<Telegram />} />
        <Route path="ai-config" element={<AIConfig />} />
        <Route path="moderation-rules" element={<ModerationRules />} />
      </Route>
    </Routes>
  )
}

export function App() {
  return (
    <BrowserRouter>
      <ThemeProvider>
        <AuthProvider>
          <AuthGate />
        </AuthProvider>
      </ThemeProvider>
    </BrowserRouter>
  )
}
