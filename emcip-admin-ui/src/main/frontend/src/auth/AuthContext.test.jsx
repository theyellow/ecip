import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, useAuth } from './AuthContext'

// Helper component that exposes auth context values for assertions
function AuthConsumer() {
  const { token, login, logout } = useAuth()
  return (
    <div>
      <span data-testid="token">{token ?? 'null'}</span>
      <button onClick={() => login('user', 'pass')}>Login</button>
      <button onClick={logout}>Logout</button>
    </div>
  )
}

function renderWithProvider() {
  return render(
    <AuthProvider>
      <AuthConsumer />
    </AuthProvider>,
  )
}

describe('AuthContext', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('initializes token as null when sessionStorage is empty', () => {
    renderWithProvider()
    expect(screen.getByTestId('token').textContent).toBe('null')
  })

  it('reads existing token from sessionStorage on init', () => {
    sessionStorage.setItem('emcip-token', 'stored-token-abc')
    renderWithProvider()
    expect(screen.getByTestId('token').textContent).toBe('stored-token-abc')
  })

  it('persists token in sessionStorage after successful login', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ token: 'new-token-xyz' }),
    })

    renderWithProvider()

    await userEvent.click(screen.getByText('Login'))

    expect(sessionStorage.getItem('emcip-token')).toBe('new-token-xyz')
    expect(screen.getByTestId('token').textContent).toBe('new-token-xyz')
  })

  it('removes token from sessionStorage on logout', async () => {
    sessionStorage.setItem('emcip-token', 'existing-token')
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ token: 'existing-token' }),
    })

    renderWithProvider()
    expect(screen.getByTestId('token').textContent).toBe('existing-token')

    await userEvent.click(screen.getByText('Logout'))

    expect(sessionStorage.getItem('emcip-token')).toBeNull()
    expect(screen.getByTestId('token').textContent).toBe('null')
  })
})
