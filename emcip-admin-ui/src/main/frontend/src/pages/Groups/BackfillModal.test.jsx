import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { BackfillModal } from './BackfillModal'

const GROUP = { telegramChatId: -1001234567890, name: 'Test Group' }

const makeApi = overrides => ({
  watchers: vi.fn().mockResolvedValue([
    { accountId: 'acc-1', displayName: 'Bot Account', phoneNumber: '+491234' },
  ]),
  backfill: vi.fn().mockResolvedValue({ backfillId: 'bf-1', status: 'RUNNING' }),
  backfillStatus: vi.fn().mockResolvedValue({ backfillId: 'bf-1', status: 'RUNNING', processed: 0 }),
  ...overrides,
})

describe('BackfillModal', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders account dropdown populated with watchers', async () => {
    const api = makeApi()
    render(<BackfillModal group={GROUP} onClose={vi.fn()} api={api} />)

    await waitFor(() =>
      expect(screen.getByRole('option', { name: 'Bot Account' })).toBeInTheDocument()
    )
  })

  it('submit button disabled until account and preset selected', async () => {
    const api = makeApi()
    render(<BackfillModal group={GROUP} onClose={vi.fn()} api={api} />)

    await waitFor(() => screen.getByRole('option', { name: 'Bot Account' }))

    expect(screen.getByRole('button', { name: /start backfill/i })).toBeDisabled()

    await userEvent.selectOptions(
      screen.getByRole('combobox'),
      screen.getByRole('option', { name: 'Bot Account' })
    )
    // still disabled — no preset yet
    expect(screen.getByRole('button', { name: /start backfill/i })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: /last 7 days/i }))

    expect(screen.getByRole('button', { name: /start backfill/i })).not.toBeDisabled()
  })

  it('shows processing count while polling', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const api = makeApi({
      backfillStatus: vi.fn().mockResolvedValue({ backfillId: 'bf-1', status: 'RUNNING', processed: 42 }),
    })
    render(<BackfillModal group={GROUP} onClose={vi.fn()} api={api} />)

    await waitFor(() => screen.getByRole('option', { name: 'Bot Account' }))
    await userEvent.selectOptions(screen.getByRole('combobox'), 'acc-1')
    await userEvent.click(screen.getByRole('button', { name: /last 7 days/i }))
    await userEvent.click(screen.getByRole('button', { name: /start backfill/i }))

    await act(async () => { vi.advanceTimersByTime(2500) })

    await waitFor(() =>
      expect(screen.getByText(/42 messages/i)).toBeInTheDocument()
    )
  })

  it('shows done state on completion', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const api = makeApi({
      backfillStatus: vi.fn().mockResolvedValue({ backfillId: 'bf-1', status: 'COMPLETED', processed: 100 }),
    })
    render(<BackfillModal group={GROUP} onClose={vi.fn()} api={api} />)

    await waitFor(() => screen.getByRole('option', { name: 'Bot Account' }))
    await userEvent.selectOptions(screen.getByRole('combobox'), 'acc-1')
    await userEvent.click(screen.getByRole('button', { name: /last 7 days/i }))
    await userEvent.click(screen.getByRole('button', { name: /start backfill/i }))

    await act(async () => { vi.advanceTimersByTime(2500) })

    await waitFor(() =>
      expect(screen.getByText(/100 messages ingested/i)).toBeInTheDocument()
    )
  })

  it('shows empty-watchers message when no accounts', async () => {
    const api = makeApi({ watchers: vi.fn().mockResolvedValue([]) })
    render(<BackfillModal group={GROUP} onClose={vi.fn()} api={api} />)

    await waitFor(() =>
      expect(
        screen.getByText(/no watcher accounts are connected to this group/i)
      ).toBeInTheDocument()
    )
    expect(screen.getByRole('button', { name: /start backfill/i })).toBeDisabled()
  })
})
