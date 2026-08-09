import { render, screen, act, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useCallback, useEffect, useMemo, useState } from 'react'
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

describe('useAuthRequest identity stability', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  const wrapper = ({ children }) => <AuthProvider>{children}</AuthProvider>

  // Pages build their api client with useMemo([request]) and their loader with
  // useCallback([api]), then run the loader from useEffect([loader]). That whole chain
  // is only a "load once on mount" if the request itself keeps its identity across
  // renders. If it does not, every setState re-arms the effect and the page fetches
  // in a loop for as long as the state keeps changing.
  it('returns the same request function across re-renders', () => {
    const { result, rerender } = renderHook(() => useAuthRequest(), { wrapper })
    const first = result.current

    rerender()
    rerender()

    expect(result.current).toBe(first)
  })

  it('returns a new request function when the token changes', async () => {
    const { result } = renderHook(
      () => ({ request: useAuthRequest(), auth: useAuth() }),
      { wrapper },
    )
    const before = result.current.request

    sessionStorage.setItem('emcip-refresh-token', 'rt')
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ token: 'new-access', refreshToken: 'new-rt' }),
    })
    await act(async () => { await result.current.auth.refresh() })

    expect(result.current.request).not.toBe(before)
  })

  // The regression the operator actually saw: opening a page produced a burst of
  // hundreds of requests that tripped the API rate limiter (429) until the state
  // stopped changing. One mount must mean one fetch.
  it('does not re-fetch on every render when a page loads data into state', async () => {
    sessionStorage.setItem('emcip-token', 'tok')
    let calls = 0
    global.fetch = vi.fn().mockImplementation(async () => {
      calls += 1
      return {
        ok: true,
        status: 200,
        headers: { get: () => null },
        // A fresh array each time, exactly like a real JSON response: React cannot
        // bail out of the re-render, so a re-armed effect loops without limit.
        json: async () => [{ id: 1 }],
      }
    })

    function Page() {
      const request = useAuthRequest()
      const api = useMemo(() => ({ list: () => request('/api/things') }), [request])
      const [items, setItems] = useState([])
      const load = useCallback(() => { api.list().then(setItems) }, [api])
      useEffect(() => { load() }, [load])
      return <span data-testid="items">{items.length}</span>
    }

    render(<AuthProvider><Page /></AuthProvider>)

    await waitFor(() => expect(screen.getByTestId('items').textContent).toBe('1'))
    await act(async () => { await new Promise(r => setTimeout(r, 50)) })

    expect(calls).toBe(1)
  })
})
