import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { PolicyRules } from './PolicyRules'

const mockTenants = [
  { id: '22222222-0000-0000-0000-000000000000', name: 'Beta Corp' },
]

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'test-token' }) }))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

vi.mock('../../api/policyRules', () => ({
  policyRulesApi: () => ({
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
    remove: vi.fn().mockResolvedValue(null),
    history: vi.fn().mockResolvedValue([]),
  }),
}))

vi.mock('../../api/tenants', () => ({
  tenantsApi: () => ({
    list: vi.fn().mockResolvedValue(mockTenants),
    create: vi.fn().mockResolvedValue({}),
    remove: vi.fn().mockResolvedValue(null),
  }),
}))

describe('PolicyRules page — tenant dropdown', () => {
  it('shows tenant dropdown in Create Rule modal', async () => {
    render(<PolicyRules />)
    await waitFor(() => screen.getByText('+ Create Rule'))
    screen.getByRole('button', { name: /create rule/i }).click()
    await waitFor(() => {
      expect(screen.getByText(/beta corp/i)).toBeInTheDocument()
    })
  })
})
