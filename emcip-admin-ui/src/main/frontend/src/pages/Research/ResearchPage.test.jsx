import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, it, expect, vi } from 'vitest'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ currentTenant: null }),
  useAuthRequest: () => vi.fn(),
}))

vi.mock('../../api/research', () => ({
  researchApi: () => ({
    listSessions: () => Promise.reject(new Error('Network error')),
  }),
}))

import { ResearchPage } from './ResearchPage'

describe('ResearchPage loading/error states', () => {
  it('shows error message without simultaneously showing loading text', async () => {
    render(<MemoryRouter><ResearchPage /></MemoryRouter>)

    // Wait for alert to appear and loading state to clear
    await waitFor(
      () => {
        const errorEl = screen.getByRole('alert')
        expect(errorEl).toBeInTheDocument()
        expect(errorEl.textContent).toMatch(/couldn't load/i)
        // By this point, loading should be false
        expect(screen.queryByText(/loading sessions/i)).not.toBeInTheDocument()
      },
      { timeout: 3000 }
    )
  })

  it('shows a Retry button when load fails', async () => {
    render(<MemoryRouter><ResearchPage /></MemoryRouter>)

    await waitFor(
      () => {
        const retryBtn = screen.getByRole('button', { name: /retry/i })
        expect(retryBtn).toBeInTheDocument()
      },
      { timeout: 3000 }
    )
  })
})
