import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { Users } from './Users'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
  useAuthRequest: () => vi.fn(),
}))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

const mockUsersApi = {
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  resetPassword: vi.fn(),
}
const mockTenantsApi = { list: vi.fn() }

vi.mock('../../api/usersApi', () => ({
  usersApi: () => mockUsersApi,
}))
vi.mock('../../api/tenants', () => ({
  tenantsApi: () => mockTenantsApi,
}))

const USER = {
  id: 'user-uuid-1',
  username: 'admin',
  email: 'admin@emcip.local',
  role: 'ADMIN',
  tenantId: null,
  tenantName: null,
  enabled: true,
}

const TENANT_USER = {
  id: 'user-uuid-2',
  username: 'mod_anna',
  email: 'anna@emcip.local',
  role: 'TENANT_ADMIN',
  tenantId: 'tenant-1',
  tenantName: 'Test Tenant',
  enabled: false,
}

describe('Users page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUsersApi.list.mockResolvedValue([])
    mockTenantsApi.list.mockResolvedValue([{ id: 'tenant-1', name: 'Test Tenant' }])
  })

  it('renders empty table when no users exist', async () => {
    render(<Users />)
    await waitFor(() =>
      expect(screen.getByText(/no users configured/i)).toBeInTheDocument()
    )
  })

  it('displays user row with role and enabled badges', async () => {
    mockUsersApi.list.mockResolvedValue([USER])
    render(<Users />)
    await waitFor(() => expect(screen.getByText('admin')).toBeInTheDocument())
    expect(screen.getByText('ADMIN')).toBeInTheDocument()
    expect(screen.getByText('ON')).toBeInTheDocument()
  })

  it('shows OFF badge for disabled user', async () => {
    mockUsersApi.list.mockResolvedValue([TENANT_USER])
    render(<Users />)
    await waitFor(() => screen.getByText('mod_anna'))
    expect(screen.getByText('OFF')).toBeInTheDocument()
    expect(screen.getByText('Test Tenant')).toBeInTheDocument()
  })

  it('opens Add User modal when button clicked', async () => {
    render(<Users />)
    await waitFor(() => screen.getByText(/no users configured/i))
    await userEvent.click(screen.getByRole('button', { name: /add user/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Add User')).toBeInTheDocument()
  })

  it('creates a user and reloads list', async () => {
    const created = { ...USER, id: 'user-uuid-3', username: 'new_user' }
    mockUsersApi.create.mockResolvedValue(created)
    mockUsersApi.list
      .mockResolvedValueOnce([])
      .mockResolvedValue([created])

    render(<Users />)
    await waitFor(() => screen.getByText(/no users configured/i))
    await userEvent.click(screen.getByRole('button', { name: /add user/i }))
    await userEvent.type(screen.getAllByRole('textbox')[0], 'new_user')
    await userEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => expect(screen.getByText('new_user')).toBeInTheDocument())
    expect(mockUsersApi.create).toHaveBeenCalledWith(
      expect.objectContaining({ username: 'new_user' })
    )
  })

  it('opens Edit modal with prefilled values on row click', async () => {
    mockUsersApi.list.mockResolvedValue([USER])
    render(<Users />)
    await waitFor(() => screen.getByText('admin'))
    await userEvent.click(screen.getByText('admin'))
    expect(screen.getByText(/edit/i)).toBeInTheDocument()
    expect(screen.getByDisplayValue('admin@emcip.local')).toBeInTheDocument()
  })

  it('opens Password Reset modal', async () => {
    mockUsersApi.list.mockResolvedValue([USER])
    render(<Users />)
    await waitFor(() => screen.getByText('admin'))
    await userEvent.click(screen.getByRole('button', { name: /password/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText(/reset password/i)).toBeInTheDocument()
  })

  it('deletes user after confirmation', async () => {
    mockUsersApi.list.mockResolvedValue([USER])
    mockUsersApi.remove.mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    render(<Users />)
    await waitFor(() => screen.getByText('admin'))
    await userEvent.click(screen.getByRole('button', { name: /delete/i }))

    expect(mockUsersApi.remove).toHaveBeenCalledWith('user-uuid-1')
  })
})
