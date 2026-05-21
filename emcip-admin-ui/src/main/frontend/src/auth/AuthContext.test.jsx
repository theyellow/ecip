import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import { AuthProvider, useAuth, useAuthRequest } from './AuthContext'

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

describe('AuthContext — refresh token', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('login stores refreshToken in sessionStorage', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ token: 'access-tok', refreshToken: 'refresh-tok' }),
    })

    const { result } = renderHook(() => useAuth(), {
      wrapper: ({ children }) => <AuthProvider>{children}</AuthProvider>,
    })

    await act(async () => { await result.current.login('user', 'pass') })

    expect(sessionStorage.getItem('emcip-refresh-token')).toBe('refresh-tok')
  })

  it('logout clears both tokens', async () => {
    sessionStorage.setItem('emcip-token', 'tok')
    sessionStorage.setItem('emcip-refresh-token', 'rt')
    global.fetch = vi.fn().mockResolvedValue({ ok: true })

    const { result } = renderHook(() => useAuth(), {
      wrapper: ({ children }) => <AuthProvider>{children}</AuthProvider>,
    })

    act(() => { result.current.logout() })

    expect(sessionStorage.getItem('emcip-token')).toBeNull()
    expect(sessionStorage.getItem('emcip-refresh-token')).toBeNull()
  })

  it('refresh updates tokens and returns new access token', async () => {
    sessionStorage.setItem('emcip-refresh-token', 'old-rt')
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ token: 'new-access', refreshToken: 'new-rt' }),
    })

    const { result } = renderHook(() => useAuth(), {
      wrapper: ({ children }) => <AuthProvider>{children}</AuthProvider>,
    })

    let newToken
    await act(async () => { newToken = await result.current.refresh() })

    expect(newToken).toBe('new-access')
    expect(sessionStorage.getItem('emcip-refresh-token')).toBe('new-rt')
  })
})
