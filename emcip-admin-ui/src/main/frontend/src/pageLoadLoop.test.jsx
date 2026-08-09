import { render, act } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { AuthProvider } from './auth/AuthContext'
import { ThemeProvider } from './theme/ThemeContext'
import ToastProvider from './components/Toast/ToastProvider'
import { Telegram } from './pages/Telegram/Telegram'
import { Knowledge } from './pages/Knowledge/KnowledgePage'
import { IntegrationsPage } from './pages/IntegrationsPage/IntegrationsPage'
import { ResearchPage } from './pages/Research/ResearchPage'

/**
 * Mounting a page must issue each request once.
 *
 * These four pages are the ones whose effects depend on `[api]` / `[request]`, which is the shape
 * that loops when anything upstream changes identity per render. The companion test
 * (providerStability.test.jsx) asserts the cause; this one asserts the symptom against the real
 * pages, so a loop introduced by any future provider is caught even if nobody adds it there.
 *
 * Note what is deliberately NOT mocked: `AuthContext`. Every existing page test stubs it out with
 * `useAuthRequest: () => vi.fn()` — which is itself a new function per call — so none of them could
 * observe this class of bug, in either direction. Here the real provider stack runs and only
 * `global.fetch` is replaced.
 *
 * The assertion counts calls per path rather than in total. That needs no per-page tuning as
 * endpoints are added, and when it fails it names the endpoint that is looping.
 */

// role=ADMIN so permission-gated sections actually render and fetch.
const JWT = `x.${btoa(JSON.stringify({ role: 'ADMIN', sub: 'admin' }))}.y`

// A runaway loop is a chain of promise resolutions, so it starves the macrotask queue and
// `act()` never reaches quiescence: the test would die of timeout with a message that says
// nothing about the cause. Capping the stub stops the runaway and lets the recorded counts be
// reported by endpoint name instead.
const MAX_REQUESTS = 25

function trackFetch() {
  const calls = new Map()
  let total = 0
  global.fetch = vi.fn(async input => {
    const path = new URL(String(input), 'http://localhost').pathname
    calls.set(path, (calls.get(path) ?? 0) + 1)
    total += 1
    // Past the cap, never settle. Rejecting instead would let a page's catch handler write new
    // state (a toast with a fresh id, say) and keep the loop alive; a promise that never resolves
    // triggers no further render regardless of how the page handles failure.
    if (total > MAX_REQUESTS) return new Promise(() => {})
    return {
      ok: true,
      status: 200,
      headers: { get: () => null },
      // A fresh value per call, as a real JSON response would be: React cannot bail out of the
      // resulting re-render, so a re-armed effect has nothing to stop it.
      json: async () => [],
    }
  })
  return calls
}

/** Long enough for mount effects and their follow-up state updates to settle. */
async function settle() {
  await act(async () => {
    await new Promise(resolve => setTimeout(resolve, 150))
  })
}

function renderPage(ui) {
  return render(
    <MemoryRouter>
      <ThemeProvider>
        <ToastProvider>
          <AuthProvider>{ui}</AuthProvider>
        </ToastProvider>
      </ThemeProvider>
    </MemoryRouter>,
  )
}

const PAGES = [
  { name: 'Telegram', element: <Telegram /> },
  { name: 'Knowledge', element: <Knowledge /> },
  { name: 'IntegrationsPage', element: <IntegrationsPage /> },
  { name: 'ResearchPage', element: <ResearchPage /> },
]

describe('pages fetch each endpoint once per mount', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    vi.restoreAllMocks()
    sessionStorage.setItem('emcip-token', JWT)
  })

  it.each(PAGES)('$name', async ({ element }) => {
    const calls = trackFetch()

    renderPage(element)

    await settle()

    // Without this the test passes vacuously if a page stops fetching on mount — a green
    // "no endpoint looped" on zero endpoints proves nothing.
    expect(calls.size, 'page issued no requests at all; this assertion would be vacuous').toBeGreaterThan(0)

    const repeated = [...calls.entries()].filter(([, count]) => count > 1)
    expect(repeated, 'these endpoints were fetched more than once on a single mount').toEqual([])
  })
})
