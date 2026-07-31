import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { AuditLog } from './AuditLog'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
  useAuthRequest: () => vi.fn(),
}))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

const mockApi = {
  list: vi.fn(),
}

vi.mock('../../api/auditLog', () => ({
  auditLogApi: () => mockApi,
}))

const EVENT = {
  createdAt: '2026-05-13T08:00:00Z',
  eventType: 'POLICY_DECISION',
  sourceService: 'policy-engine',
  action: 'decide',
  resourceId: 'msg-12345',
  outcome: 'OK',
  details: 'Allowed by rule #3',
}

describe('AuditLog page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.list.mockResolvedValue({ items: [], total: 0 })
  })

  it('renders heading and filter controls', async () => {
    render(<AuditLog />)
    await waitFor(() => expect(screen.getByText('Audit Log')).toBeInTheDocument())
    expect(screen.getByText('All types')).toBeInTheDocument()
  })

  it('displays event row with type, action, truncated resource and outcome', async () => {
    mockApi.list.mockResolvedValue({ items: [EVENT], total: 1 })
    render(<AuditLog />)
    await waitFor(() => expect(screen.getAllByText('POLICY_DECISION').length).toBeGreaterThanOrEqual(1))
    expect(screen.getByText('decide')).toBeInTheDocument()
    expect(screen.getByText('msg-1234…')).toBeInTheDocument()
    expect(screen.getByText('OK')).toBeInTheDocument()
  })

  it('shows em-dash for missing fields', async () => {
    mockApi.list.mockResolvedValue({ items: [{ ...EVENT, action: null, resourceId: null, outcome: null }], total: 1 })
    render(<AuditLog />)
    await waitFor(() => screen.getAllByText('POLICY_DECISION'))
    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBeGreaterThanOrEqual(2)
  })

  it('reloads with eventType filter when type selected', async () => {
    mockApi.list.mockResolvedValue({ items: [EVENT], total: 1 })
    render(<AuditLog />)
    await waitFor(() => screen.getByText('All types'))

    // Combobox order: event-type, page-size, time-preset, action
    const [typeSelect] = screen.getAllByRole('combobox')
    await userEvent.selectOptions(typeSelect, 'POLICY_DECISION')

    // list(page, size, eventType, from, to) — '24h' preset yields from=<iso>, to=null
    await waitFor(() =>
      expect(mockApi.list).toHaveBeenCalledWith(
        expect.any(Number),
        expect.any(Number),
        'POLICY_DECISION',
        expect.any(String),
        null
      )
    )
  })

  it('reloads with new page size when size selected', async () => {
    mockApi.list.mockResolvedValue({ items: [], total: 0 })
    render(<AuditLog />)
    await waitFor(() => screen.getByText('Audit Log'))

    // Combobox order: event-type, page-size, time-preset, action — size is the 2nd
    const [, sizeSelect] = screen.getAllByRole('combobox')
    await userEvent.selectOptions(sizeSelect, '100')

    // list(page, size, eventType, from, to) — '24h' preset yields from=<iso>, to=null
    await waitFor(() =>
      expect(mockApi.list).toHaveBeenCalledWith(
        expect.any(Number),
        100,
        expect.anything(),
        expect.any(String),
        null
      )
    )
  })

  it('shows error when list fails', async () => {
    mockApi.list.mockRejectedValue(new Error('Forbidden'))
    render(<AuditLog />)
    await waitFor(() => expect(screen.getByText('Forbidden')).toBeInTheDocument())
  })

  it('shows all expected event type options in the filter', async () => {
    render(<AuditLog />)
    await waitFor(() => screen.getByText('All types'))
    // Event type filter options
    expect(screen.getByRole('option', { name: 'MESSAGE_RECEIVED' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'MESSAGE_CLASSIFIED' })).toBeInTheDocument()
    expect(screen.getAllByRole('option', { name: 'POLICY_DECISION' }).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByRole('option', { name: 'MODERATION_ACTION' }).length).toBeGreaterThanOrEqual(1)
    // Action filter options
    expect(screen.getByRole('option', { name: 'All actions' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'SEND_MESSAGE' })).toBeInTheDocument()
  })
})
