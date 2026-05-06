import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { Telegram } from './Telegram'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
}))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

const mockApi = {
  listAccounts: vi.fn(),
  createAccount: vi.fn(),
  deleteAccount: vi.fn(),
  getStatus: vi.fn(),
  reconnect: vi.fn(),
  submitCode: vi.fn(),
  submitPassword: vi.fn(),
  logout: vi.fn(),
  discoverChats: vi.fn(),
  listWatched: vi.fn(),
  watchGroup: vi.fn(),
  unwatchGroup: vi.fn(),
}

vi.mock('../../api/telegram', () => ({
  telegramApi: () => mockApi,
}))

describe('Telegram page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.listAccounts.mockResolvedValue([])
  })

  it('renders accounts table with no-accounts empty state', async () => {
    render(<Telegram />)
    await waitFor(() =>
      expect(screen.getByText('No accounts configured')).toBeInTheDocument()
    )
  })

  it('shows account row with status badge', async () => {
    mockApi.listAccounts.mockResolvedValue([
      { id: 'uuid-1', displayName: 'Monitor 1', phoneNumber: '+49123', status: 'ACTIVE', lastError: null },
    ])
    render(<Telegram />)
    await waitFor(() => expect(screen.getByText('Monitor 1')).toBeInTheDocument())
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
  })

  it('Groups button expands group panel for that account', async () => {
    mockApi.listAccounts.mockResolvedValue([
      { id: 'uuid-1', displayName: 'Monitor 1', phoneNumber: '+49123', status: 'ACTIVE', lastError: null },
    ])
    mockApi.listWatched.mockResolvedValue([])
    render(<Telegram />)
    await waitFor(() => screen.getByText('Monitor 1'))

    await userEvent.click(screen.getByRole('button', { name: /groups/i }))
    await waitFor(() =>
      expect(screen.getByText(/no groups watched/i)).toBeInTheDocument()
    )
  })

  it('Discover button opens modal and shows discovered chats', async () => {
    mockApi.listAccounts.mockResolvedValue([
      { id: 'uuid-1', displayName: 'Monitor 1', phoneNumber: '+49123', status: 'ACTIVE', lastError: null },
    ])
    mockApi.listWatched.mockResolvedValue([])
    mockApi.discoverChats.mockResolvedValue([
      { chatId: 111, title: 'My Group', type: 'SUPERGROUP' },
    ])
    render(<Telegram />)
    await waitFor(() => screen.getByText('Monitor 1'))
    await userEvent.click(screen.getByRole('button', { name: /groups/i }))
    await userEvent.click(screen.getByRole('button', { name: /discover/i }))

    await waitFor(() => expect(screen.getByText('My Group')).toBeInTheDocument())
  })

  it('Watch button in discover modal calls watchGroup', async () => {
    mockApi.listAccounts.mockResolvedValue([
      { id: 'uuid-1', displayName: 'Monitor 1', phoneNumber: '+49123', status: 'ACTIVE', lastError: null },
    ])
    mockApi.listWatched.mockResolvedValue([])
    mockApi.discoverChats.mockResolvedValue([
      { chatId: 111, title: 'My Group', type: 'SUPERGROUP' },
    ])
    mockApi.watchGroup.mockResolvedValue({ chatId: 111, name: 'My Group', moderationLevel: 'MEDIUM' })
    render(<Telegram />)
    await waitFor(() => screen.getByText('Monitor 1'))
    await userEvent.click(screen.getByRole('button', { name: /groups/i }))
    await userEvent.click(screen.getByRole('button', { name: /discover/i }))
    await waitFor(() => screen.getByText('My Group'))

    await userEvent.click(screen.getByRole('button', { name: /^watch$/i }))
    expect(mockApi.watchGroup).toHaveBeenCalledWith('uuid-1', { chatId: 111, title: 'My Group' })
  })

  it('adds account and shows it in list', async () => {
    const newAccount = { id: 'uuid-2', displayName: 'Bot 2', phoneNumber: '+49999', status: 'UNCONFIGURED', lastError: null }
    mockApi.createAccount.mockResolvedValue(newAccount)
    mockApi.listAccounts
      .mockResolvedValueOnce([])
      .mockResolvedValue([newAccount])

    render(<Telegram />)
    await waitFor(() => screen.getByText('No accounts configured'))
    await userEvent.click(screen.getByRole('button', { name: /add account/i }))
    await userEvent.type(screen.getByPlaceholderText(/monitor account/i), 'Bot 2')
    await userEvent.type(screen.getByPlaceholderText(/\+49/i), '+49999')
    await userEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => expect(screen.getByText('Bot 2')).toBeInTheDocument())
  })
})
