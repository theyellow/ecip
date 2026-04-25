import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { Groups } from './Groups'

const mockGroups = [
  { telegramChatId: -1001234567890, name: 'Test Group', moderationLevel: 'LOW', autoRespond: false }
]
const mockTenants = [
  { id: '11111111-0000-0000-0000-000000000000', name: 'Acme Corp' },
]

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'test-token' }) }))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

vi.mock('../../api/groups', () => ({
  groupsApi: () => ({
    list: vi.fn().mockResolvedValue(mockGroups),
    create: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
    remove: vi.fn().mockResolvedValue(null),
  }),
}))

vi.mock('../../api/tenants', () => ({
  tenantsApi: () => ({
    list: vi.fn().mockResolvedValue(mockTenants),
    create: vi.fn().mockResolvedValue({}),
    remove: vi.fn().mockResolvedValue(null),
  }),
}))

describe('Groups page — tenant dropdown', () => {
  it('shows tenant dropdown in Add Group modal', async () => {
    render(<Groups />)
    await waitFor(() => screen.getByText('+ Add Group'))
    screen.getByRole('button', { name: /add group/i }).click()
    await waitFor(() => {
      expect(screen.getByText(/acme corp/i)).toBeInTheDocument()
    })
  })
})
