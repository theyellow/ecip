import { render, renderHook, act, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { AuthProvider, useAuth, useAuthRequest } from './auth/AuthContext'
import { ThemeProvider, useTheme } from './theme/ThemeContext'
import ToastProvider from './components/Toast/ToastProvider'
import { useToast } from './components/Toast/useToast'

/**
 * A context that changes identity on every render is not a style problem in this app — it is an
 * outage. Pages are built as
 *
 *   useAuthRequest() -> useMemo(api, [request]) -> useCallback(load, [api]) -> useEffect([load])
 *
 * so a value that is new each render re-arms the effect on every `setState`, which fetches, which
 * sets state again. That shipped once already: it drained the admin-api rate limiter and turned
 * every page into 429s, and it self-terminates as soon as the error text stops changing, which made
 * it read as a transient glitch rather than a loop.
 *
 * `eslint-plugin-react-hooks` does not catch this. `exhaustive-deps` checks for *missing*
 * dependencies, never for dependencies that are unstable — it was silent throughout.
 *
 * So the invariant is asserted directly, once, for every context the app exposes. A new provider
 * added to this list costs one line; leaving it out is what lets the next loop through.
 */

const CASES = [
  { name: 'useAuth()', Provider: AuthProvider, useValue: useAuth },
  { name: 'useAuthRequest()', Provider: AuthProvider, useValue: useAuthRequest },
  { name: 'useTheme()', Provider: ThemeProvider, useValue: useTheme },
  { name: 'useToast()', Provider: ToastProvider, useValue: useToast },
]

describe('context values are referentially stable across re-renders', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it.each(CASES)('$name', ({ Provider, useValue }) => {
    const { result, rerender } = renderHook(() => useValue(), {
      wrapper: ({ children }) => <Provider>{children}</Provider>,
    })
    const first = result.current

    rerender()
    rerender()

    // Named keys first: `expect(x).toBe(y)` on two structurally identical objects reports
    // "no visual difference", which says nothing about *which* member moved.
    if (first && typeof first === 'object') {
      const changed = Object.keys(first).filter(key => !Object.is(first[key], result.current[key]))
      expect(changed, `these context members changed identity across a re-render`).toEqual([])
    }
    expect(result.current).toBe(first)
  })
})

/**
 * The opposite failure: memoizing with an empty dependency list would satisfy every assertion above
 * while freezing the app. Stability has to mean "changes only when the underlying value changes",
 * not "never changes".
 */
describe('context values still change when the underlying state changes', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('useTheme() reports the new theme after a toggle', () => {
    const { result } = renderHook(() => useTheme(), {
      wrapper: ({ children }) => <ThemeProvider>{children}</ThemeProvider>,
    })
    const before = result.current.theme

    act(() => result.current.toggleTheme())

    expect(result.current.theme).not.toBe(before)
  })

  it('useAuthRequest() returns a new request after the token changes', async () => {
    const { result } = renderHook(() => ({ request: useAuthRequest(), auth: useAuth() }), {
      wrapper: ({ children }) => <AuthProvider>{children}</AuthProvider>,
    })
    const before = result.current.request

    sessionStorage.setItem('emcip-refresh-token', 'rt')
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ token: 'new-access', refreshToken: 'new-rt' }),
    })
    await act(async () => { await result.current.auth.refresh() })

    expect(result.current.request).not.toBe(before)
  })
})

/**
 * End-to-end version of the same invariant, through the real provider stack rather than one context
 * at a time. This is what a page actually does, so it catches a loop introduced anywhere in the
 * chain — including in a provider nobody remembered to add to CASES.
 */
describe('the page data-loading pattern fetches once per mount', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('does not re-fetch when the loaded data lands in state', async () => {
    sessionStorage.setItem('emcip-token', 'tok')
    let calls = 0
    global.fetch = vi.fn().mockImplementation(async () => {
      calls += 1
      // Past the cap, never settle. A loop is a chain of promise resolutions that starves the
      // macrotask queue, so `act()` would never reach quiescence and the test would die of
      // timeout rather than reporting the call count.
      if (calls > 25) return new Promise(() => {})
      return {
        ok: true,
        status: 200,
        headers: { get: () => null },
        // A fresh array per call, exactly like a real JSON response. React cannot bail out of
        // the re-render, so a re-armed effect loops without limit.
        json: async () => [{ id: 1 }],
      }
    })

    function Page() {
      const request = useAuthRequest()
      const { theme } = useTheme()
      const { addToast } = useToast()
      const api = useMemo(() => ({ list: () => request('/api/things') }), [request])
      const [items, setItems] = useState([])
      const load = useCallback(() => {
        api.list().then(setItems).catch(() => addToast('error', 'failed'))
      }, [api, addToast])
      useEffect(() => { load() }, [load])
      return <span data-testid="items">{`${items.length}-${theme}`}</span>
    }

    render(
      <ThemeProvider>
        <ToastProvider>
          <AuthProvider>
            <Page />
          </AuthProvider>
        </ToastProvider>
      </ThemeProvider>,
    )

    await act(async () => { await new Promise(resolve => setTimeout(resolve, 100)) })

    expect(screen.getByTestId('items').textContent).toMatch(/^1-/)
    expect(calls, 'one mount must issue exactly one request').toBe(1)
  })
})
