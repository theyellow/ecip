import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { Decisions } from './Flags'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
  useAuthRequest: () => vi.fn(),
}))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

const mockFlagsApi = {
  list: vi.fn(),
  updateStatus: vi.fn(),
  reply: vi.fn(),
}

vi.mock('../../api/flags', () => ({
  flagsApi: () => mockFlagsApi,
}))

const DECISION = {
  id: 'flag-1',
  decision: 'FLAG',
  originalIntent: 'SPAM',
  confidence: 0.92,
  reason: 'Repeated spam links',
  timestamp: '2026-05-28T14:30:00Z',
  signalStatus: 'NEW',
  policyId: 'pol-1',
  metadata: JSON.stringify({ messageText: 'Buy cheap watches', chatId: 12345, senderId: 'user-99' }),
}

describe('Decisions page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFlagsApi.list.mockResolvedValue({ items: [], total: 0 })
  })

  it('renders empty table message', async () => {
    render(<Decisions />)
    await waitFor(() =>
      expect(screen.getByText(/no decisions yet/i)).toBeInTheDocument()
    )
  })

  it('displays decision rows with badges', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [DECISION], total: 1 })
    render(<Decisions />)
    await waitFor(() => expect(screen.getByText('FLAG')).toBeInTheDocument())
    expect(screen.getByText('SPAM')).toBeInTheDocument()
    expect(screen.getByText('NEW')).toBeInTheDocument()
    expect(screen.getByText('92%')).toBeInTheDocument()
  })

  it('opens detail modal on row click', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [DECISION], total: 1 })
    render(<Decisions />)
    await waitFor(() => screen.getByText('FLAG'))
    await userEvent.click(screen.getByText('Repeated spam links'))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Decision Detail')).toBeInTheDocument()
    expect(screen.getAllByText('Buy cheap watches').length).toBeGreaterThanOrEqual(1)
  })

  it('changes status in detail modal', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [DECISION], total: 1 })
    mockFlagsApi.updateStatus.mockResolvedValue({})
    render(<Decisions />)
    await waitFor(() => screen.getByText('FLAG'))
    await userEvent.click(screen.getByText('Repeated spam links'))
    await userEvent.click(screen.getByRole('button', { name: /reviewed/i }))
    await waitFor(() =>
      expect(mockFlagsApi.updateStatus).toHaveBeenCalledWith('flag-1', 'REVIEWED')
    )
  })

  it('shows reply section and sends reply', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [DECISION], total: 1 })
    mockFlagsApi.reply.mockResolvedValue({})
    render(<Decisions />)
    await waitFor(() => screen.getByText('FLAG'))
    await userEvent.click(screen.getByText('Repeated spam links'))
    await userEvent.click(screen.getByText(/reply/i))
    const textarea = screen.getByPlaceholderText('Type your response...')
    await userEvent.type(textarea, 'Please stop')
    await userEvent.click(screen.getByRole('button', { name: /send/i }))
    await waitFor(() =>
      expect(mockFlagsApi.reply).toHaveBeenCalledWith(
        'flag-1',
        expect.objectContaining({ text: 'Please stop', target: 'GROUP' })
      )
    )
  })

  it('filters by decision', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [], total: 0 })
    render(<Decisions />)
    await waitFor(() => screen.getByText(/no decisions yet/i))
    const selects = screen.getAllByRole('combobox')
    await userEvent.selectOptions(selects[0], 'FLAG')
    await waitFor(() =>
      expect(mockFlagsApi.list).toHaveBeenCalledWith(0, 50, 'FLAG', '', null, null, null)
    )
  })

  it('filters by intent text', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [], total: 0 })
    render(<Decisions />)
    await waitFor(() => screen.getByText(/no decisions yet/i))
    const intentInput = screen.getByPlaceholderText(/intent/i)
    await userEvent.type(intentInput, 'SPAM')
    await waitFor(() =>
      expect(mockFlagsApi.list).toHaveBeenCalledWith(0, 50, '', 'SPAM', null, null, null)
    )
  })

  it('shows custom date inputs when Custom range is selected', async () => {
    render(<Decisions />)
    await waitFor(() => screen.getByText(/no decisions yet/i))
    const selects = screen.getAllByRole('combobox')
    const timeSelect = selects[selects.length - 1]
    await userEvent.selectOptions(timeSelect, 'custom')
    expect(screen.getAllByDisplayValue('').filter(el => el.type === 'datetime-local').length).toBe(2)
  })

  it('shows error when API fails', async () => {
    mockFlagsApi.list.mockRejectedValue(new Error('Network error'))
    render(<Decisions />)
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(screen.getByText('Network error')).toBeInTheDocument()
  })

  it('shows pagination controls', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [DECISION], total: 200 })
    render(<Decisions />)
    await waitFor(() => screen.getByText(/page 1 of/i))
    expect(screen.getByRole('button', { name: /next/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /prev/i })).toBeInTheDocument()
  })
})
