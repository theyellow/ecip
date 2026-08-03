import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import ToastProvider from '../../components/Toast/ToastProvider'
import { IntegrationsPage } from './IntegrationsPage'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ role: 'ADMIN' }),
  useAuthRequest: () => vi.fn(),
}))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

vi.mock('../../api/integrations', () => ({
  integrationsApi: () => ({
    listGlobalKeys: vi.fn().mockRejectedValue(new Error('Network error')),
    listSources: vi.fn().mockResolvedValue([]),
  }),
}))

describe('IntegrationsPage', () => {
  it('shows an error toast when a data load fails', async () => {
    render(
      <ToastProvider>
        <IntegrationsPage />
      </ToastProvider>
    )

    await waitFor(() =>
      expect(screen.getByText(/failed to load/i)).toBeInTheDocument()
    )
  })
})
