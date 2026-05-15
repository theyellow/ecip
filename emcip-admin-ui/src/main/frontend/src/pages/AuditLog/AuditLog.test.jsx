import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { AuditLog } from './AuditLog'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
}))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

const mockApi = {
  list: vi.fn(),
}

vi.mock('../../api/auditLog', () => ({
  auditLogApi: () => mockApi,
}))

const EVENT = {
  timestamp: '2026-05-13T08:00:00Z',
  eventType: 'POLICY_DECISION',
  entityId: 'entity-uuid-1',
  details: 'Allowed by rule #3',
}

describe('AuditLog page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.list.mockResolvedValue([])
  })

  it('renders heading and filter controls', async () => {
    render(<AuditLog />)
    await waitFor(() => expect(screen.getByText('Audit Log')).toBeInTheDocument())
    expect(screen.getByText('All types')).toBeInTheDocument()
  })

  it('displays event row with timestamp, type, entityId and details', async () => {
    mockApi.list.mockResolvedValue([EVENT])
    render(<AuditLog />)
    await waitFor(() => expect(screen.getByText('POLICY_DECISION')).toBeInTheDocument())
    expect(screen.getByText('entity-uuid-1')).toBeInTheDocument()
    expect(screen.getByText('Allowed by rule #3')).toBeInTheDocument()
  })

  it('shows em-dash for missing entityId and details', async () => {
    mockApi.list.mockResolvedValue([{ ...EVENT, entityId: null, details: null }])
    render(<AuditLog />)
    await waitFor(() => screen.getByText('POLICY_DECISION'))
    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBeGreaterThanOrEqual(2)
  })

  it('reloads with eventType filter when type selected', async () => {
    mockApi.list.mockResolvedValue([EVENT])
    render(<AuditLog />)
    await waitFor(() => screen.getByText('All types'))

    // First combobox is the event-type filter; second is the page-size selector
    const [typeSelect] = screen.getAllByRole('combobox')
    await userEvent.selectOptions(typeSelect, 'POLICY_DECISION')

    await waitFor(() =>
      expect(mockApi.list).toHaveBeenCalledWith(expect.anything(), 'POLICY_DECISION')
    )
  })

  it('reloads with new page size when size selected', async () => {
    mockApi.list.mockResolvedValue([])
    render(<AuditLog />)
    await waitFor(() => screen.getByText('Audit Log'))

    // Second combobox is the page-size selector
    const [, sizeSelect] = screen.getAllByRole('combobox')
    await userEvent.selectOptions(sizeSelect, '100')

    await waitFor(() =>
      expect(mockApi.list).toHaveBeenCalledWith(100, expect.anything())
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
    expect(screen.getByRole('option', { name: 'MESSAGE_RECEIVED' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'MESSAGE_CLASSIFIED' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'POLICY_DECISION' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'MODERATION_ACTION' })).toBeInTheDocument()
  })
})
