import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { Flags } from './Flags'

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

const FLAG = {
  id: 'flag-1',
  decision: 'BAN',
  originalIntent: 'SPAM',
  confidence: 0.92,
  reason: 'Repeated spam links',
  timestamp: '2026-05-28T14:30:00Z',
  signalStatus: 'NEW',
  policyId: 'pol-1',
  metadata: JSON.stringify({ messageText: 'Buy cheap watches', chatId: 12345, senderId: 'user-99' }),
}

describe('Flags page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFlagsApi.list.mockResolvedValue({ items: [], total: 0 })
  })

  it('renders empty table message', async () => {
    render(<Flags />)
    await waitFor(() =>
      expect(screen.getByText(/no flags yet/i)).toBeInTheDocument()
    )
  })

  it('displays flag rows with badges', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [FLAG], total: 1 })
    render(<Flags />)
    await waitFor(() => expect(screen.getByText('BAN')).toBeInTheDocument())
    expect(screen.getByText('SPAM')).toBeInTheDocument()
    expect(screen.getByText('NEW')).toBeInTheDocument()
    expect(screen.getByText('92%')).toBeInTheDocument()
  })

  it('opens detail modal on row click', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [FLAG], total: 1 })
    render(<Flags />)
    await waitFor(() => screen.getByText('BAN'))
    await userEvent.click(screen.getByText('Repeated spam links'))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Flag Detail')).toBeInTheDocument()
    expect(screen.getAllByText('Buy cheap watches').length).toBeGreaterThanOrEqual(1)
  })

  it('changes status in detail modal', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [FLAG], total: 1 })
    mockFlagsApi.updateStatus.mockResolvedValue({})
    render(<Flags />)
    await waitFor(() => screen.getByText('BAN'))
    await userEvent.click(screen.getByText('Repeated spam links'))
    await userEvent.click(screen.getByRole('button', { name: /reviewed/i }))
    await waitFor(() => expect(mockFlagsApi.updateStatus).toHaveBeenCalledWith('flag-1', 'REVIEWED'))
  })

  it('shows reply section and sends reply', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [FLAG], total: 1 })
    mockFlagsApi.reply.mockResolvedValue({})
    render(<Flags />)
    await waitFor(() => screen.getByText('BAN'))
    await userEvent.click(screen.getByText('Repeated spam links'))
    await userEvent.click(screen.getByText(/reply/i))
    const textarea = screen.getByPlaceholderText('Type your response...')
    await userEvent.type(textarea, 'Please stop')
    await userEvent.click(screen.getByRole('button', { name: /send/i }))
    await waitFor(() => expect(mockFlagsApi.reply).toHaveBeenCalledWith('flag-1', expect.objectContaining({ text: 'Please stop', target: 'GROUP' })))
  })

  it('filters by decision', async () => {
    mockFlagsApi.list.mockResolvedValue({ items: [], total: 0 })
    render(<Flags />)
    await waitFor(() => screen.getByText(/no flags yet/i))
    const selects = screen.getAllByRole('combobox')
    await userEvent.selectOptions(selects[0], 'BAN')
    await waitFor(() => expect(mockFlagsApi.list).toHaveBeenCalledWith(0, 50, 'BAN'))
  })

  it('shows error when API fails', async () => {
    mockFlagsApi.list.mockRejectedValue(new Error('Network error'))
    render(<Flags />)
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(screen.getByText('Network error')).toBeInTheDocument()
  })
})
