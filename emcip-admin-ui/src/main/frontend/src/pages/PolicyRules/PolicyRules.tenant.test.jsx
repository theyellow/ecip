import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { PolicyRules } from './PolicyRules'

const mockTenants = [
  { id: '22222222-0000-0000-0000-000000000000', name: 'Beta Corp' },
]

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'test-token' }), useAuthRequest: () => vi.fn() }))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

vi.mock('../../api/policyRules', () => ({
  policyRulesApi: vi.fn(() => ({
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
    remove: vi.fn().mockResolvedValue(null),
    history: vi.fn().mockResolvedValue([]),
  })),
}))

vi.mock('../../api/tenants', () => ({
  tenantsApi: () => ({
    list: vi.fn().mockResolvedValue(mockTenants),
    create: vi.fn().mockResolvedValue({}),
    remove: vi.fn().mockResolvedValue(null),
  }),
}))

vi.mock('../../api/intentRules', () => ({
  intentRulesApi: () => ({
    list: vi.fn().mockResolvedValue([]),
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

describe('PolicyRules — global rule badge', () => {
  it('shows a Global badge for rules with no tenantId', async () => {
    const { policyRulesApi } = await import('../../api/policyRules')

    vi.mocked(policyRulesApi).mockImplementation(() => ({
      list: vi.fn().mockResolvedValue([
        { id: 'aaa', name: 'global-rule', targetIntent: 'SPAM', action: 'FLAG',
          priority: 0, tenantId: null, effectiveFrom: null, effectiveTo: null },
        { id: 'bbb', name: 'tenant-rule', targetIntent: 'SPAM', action: 'FLAG',
          priority: 1, tenantId: 'abc-123-def', effectiveFrom: null, effectiveTo: null },
      ]),
      create: vi.fn().mockResolvedValue({}),
      update: vi.fn().mockResolvedValue({}),
      remove: vi.fn().mockResolvedValue(null),
      history: vi.fn().mockResolvedValue([]),
    }))

    render(<PolicyRules />)

    await waitFor(() => screen.getByText('global-rule'))

    const badges = screen.getAllByText('Global')
    expect(badges.length).toBe(1)

    const rows = screen.getAllByRole('row')
    const tenantRow = rows.find(r => r.textContent.includes('tenant-rule'))
    expect(tenantRow?.textContent).not.toContain('Global')
  })
})
