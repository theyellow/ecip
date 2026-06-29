import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { Sidebar } from './Sidebar'

const mockLogout = vi.fn()
const mockSetCurrentTenant = vi.fn()

// Default mock: ADMIN with no active tenant
let mockAuth = {
  role: 'ADMIN',
  currentTenant: null,
  setCurrentTenant: mockSetCurrentTenant,
  logout: mockLogout,
}
let mockRequest = vi.fn().mockResolvedValue([])

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => mockAuth,
  useAuthRequest: () => mockRequest,
}))

vi.mock('../../theme/ThemeContext', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: vi.fn() }),
}))

vi.mock('../../logo/Logo', () => ({
  Logo: () => <svg data-testid="logo" />,
}))

function renderSidebar() {
  return render(
    <MemoryRouter>
      <Sidebar />
    </MemoryRouter>,
  )
}

describe('Sidebar', () => {
  it('renders the logout button', () => {
    renderSidebar()
    expect(screen.getByRole('button', { name: /logout/i })).toBeInTheDocument()
  })

  it('calls logout when the logout button is clicked', async () => {
    mockLogout.mockClear()
    renderSidebar()
    await userEvent.click(screen.getByRole('button', { name: /logout/i }))
    expect(mockLogout).toHaveBeenCalledOnce()
  })

  it('ADMIN sees tenant dropdown', () => {
    mockAuth = { role: 'ADMIN', currentTenant: null, setCurrentTenant: mockSetCurrentTenant, logout: mockLogout }
    renderSidebar()
    expect(screen.getByRole('combobox', { name: /select active tenant/i })).toBeInTheDocument()
  })

  it('ADMIN sees all nav items including Tenants, AI Config, Users, Intent Rules', () => {
    mockAuth = { role: 'ADMIN', currentTenant: null, setCurrentTenant: mockSetCurrentTenant, logout: mockLogout }
    renderSidebar()
    expect(screen.getByText('Tenants')).toBeInTheDocument()
    expect(screen.getByText('Intent Rules')).toBeInTheDocument()
    expect(screen.getByText('AI Config')).toBeInTheDocument()
    expect(screen.getByText('Users')).toBeInTheDocument()
    expect(screen.getByText('Watched Groups')).toBeInTheDocument()
  })

  it('TENANT_ADMIN sees static tenant label, not dropdown', () => {
    mockAuth = { role: 'TENANT_ADMIN', currentTenant: { id: 'tid', name: 'Acme Corp' }, setCurrentTenant: mockSetCurrentTenant, logout: mockLogout }
    renderSidebar()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.getByText('Acme Corp')).toBeInTheDocument()
  })

  it('TENANT_ADMIN does not see Tenants, AI Config, or Users nav items', () => {
    mockAuth = { role: 'TENANT_ADMIN', currentTenant: { id: 'tid', name: 'Acme Corp' }, setCurrentTenant: mockSetCurrentTenant, logout: mockLogout }
    renderSidebar()
    expect(screen.queryByText('Tenants')).not.toBeInTheDocument()
    expect(screen.queryByText('AI Config')).not.toBeInTheDocument()
    expect(screen.queryByText('Users')).not.toBeInTheDocument()
    // But still sees allowed items
    expect(screen.getByText('Watched Groups')).toBeInTheDocument()
    expect(screen.getByText('Telegram')).toBeInTheDocument()
  })
})
