import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { Tenants } from './Tenants'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
  useAuthRequest: () => vi.fn(),
}))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

const mockApi = {
  list: vi.fn(),
  create: vi.fn(),
  remove: vi.fn(),
}

vi.mock('../../api/tenants', () => ({
  tenantsApi: () => mockApi,
}))

const TENANT = {
  id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
  name: 'Acme Corp',
  description: 'Primary tenant',
  llmModelOverride: 'gpt-4o',
  createdAt: '2026-01-15T10:00:00Z',
}

describe('Tenants page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.list.mockResolvedValue([])
  })

  it('renders empty table when no tenants exist', async () => {
    render(<Tenants />)
    await waitFor(() => expect(screen.getByText('Tenants')).toBeInTheDocument())
    expect(screen.queryByRole('row', { name: /acme/i })).not.toBeInTheDocument()
  })

  it('displays tenant row with truncated id, name, description, llm override and date', async () => {
    mockApi.list.mockResolvedValue([TENANT])
    render(<Tenants />)
    await waitFor(() => expect(screen.getByText('Acme Corp')).toBeInTheDocument())
    expect(screen.getByText(/aaaaaaaa/)).toBeInTheDocument()
    expect(screen.getByText('Primary tenant')).toBeInTheDocument()
    expect(screen.getByText('gpt-4o')).toBeInTheDocument()
  })

  it('shows em-dash for missing description and llmModelOverride', async () => {
    mockApi.list.mockResolvedValue([{ ...TENANT, description: null, llmModelOverride: null }])
    render(<Tenants />)
    await waitFor(() => screen.getByText('Acme Corp'))
    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBeGreaterThanOrEqual(2)
  })

  it('opens Create Tenant modal when button clicked', async () => {
    render(<Tenants />)
    await waitFor(() => screen.getByText('Tenants'))
    await userEvent.click(screen.getByRole('button', { name: /create tenant/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Create Tenant')).toBeInTheDocument()
  })

  it('creates a tenant and reloads list', async () => {
    const created = { ...TENANT, id: 'new-uuid-1234' }
    mockApi.create.mockResolvedValue(created)
    mockApi.list
      .mockResolvedValueOnce([])
      .mockResolvedValue([created])

    render(<Tenants />)
    await waitFor(() => screen.getByText('Tenants'))
    await userEvent.click(screen.getByRole('button', { name: /create tenant/i }))
    await userEvent.type(screen.getByLabelText(/name/i), 'Acme Corp')
    await userEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => expect(screen.getByText('Acme Corp')).toBeInTheDocument())
    expect(mockApi.create).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Acme Corp' })
    )
  })

  it('shows error message when list fails', async () => {
    mockApi.list.mockRejectedValue(new Error('Network error'))
    render(<Tenants />)
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Network error')
    )
  })

  it('deletes a tenant after confirmation', async () => {
    mockApi.list.mockResolvedValue([TENANT])
    mockApi.remove.mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    render(<Tenants />)
    await waitFor(() => screen.getByText('Acme Corp'))
    await userEvent.click(screen.getByRole('button', { name: /delete/i }))

    expect(mockApi.remove).toHaveBeenCalledWith(TENANT.id)
  })

  it('does not delete when confirmation is cancelled', async () => {
    mockApi.list.mockResolvedValue([TENANT])
    vi.spyOn(window, 'confirm').mockReturnValue(false)

    render(<Tenants />)
    await waitFor(() => screen.getByText('Acme Corp'))
    await userEvent.click(screen.getByRole('button', { name: /delete/i }))

    expect(mockApi.remove).not.toHaveBeenCalled()
  })
})
