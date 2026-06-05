import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ModerationRules } from './ModerationRules'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token', currentTenant: { name: 'Test Tenant' } }),
  useAuthRequest: () => vi.fn(),
}))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

const mockApi = {
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
}

vi.mock('../../api/moderationRules', () => ({
  moderationRulesApi: () => mockApi,
}))

const RULE = {
  id: 'rule-uuid-1',
  name: 'no-spam',
  ruleType: 'KEYWORD',
  pattern: 'buy now',
  severity: 'HIGH',
  action: 'BAN',
  enabled: true,
}

describe('ModerationRules page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.list.mockResolvedValue([])
  })

  it('renders empty table when no rules exist', async () => {
    render(<ModerationRules />)
    await waitFor(() => expect(screen.getByText('Moderation Rules')).toBeInTheDocument())
    expect(screen.queryByText('no-spam')).not.toBeInTheDocument()
  })

  it('displays rule row with badges for type, severity and action', async () => {
    mockApi.list.mockResolvedValue([RULE])
    render(<ModerationRules />)
    await waitFor(() => expect(screen.getByText('no-spam')).toBeInTheDocument())
    expect(screen.getByText('KEYWORD')).toBeInTheDocument()
    expect(screen.getByText('buy now')).toBeInTheDocument()
    expect(screen.getByText('HIGH')).toBeInTheDocument()
    expect(screen.getByText('BAN')).toBeInTheDocument()
    expect(screen.getByText('ON')).toBeInTheDocument()
  })

  it('shows OFF badge for disabled rule', async () => {
    mockApi.list.mockResolvedValue([{ ...RULE, enabled: false }])
    render(<ModerationRules />)
    await waitFor(() => screen.getByText('no-spam'))
    expect(screen.getByText('OFF')).toBeInTheDocument()
  })

  it('opens Create Rule modal when button clicked', async () => {
    render(<ModerationRules />)
    await waitFor(() => screen.getByText('Moderation Rules'))
    await userEvent.click(screen.getByRole('button', { name: /create rule/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Create Rule')).toBeInTheDocument()
  })

  it('creates a rule and reloads list', async () => {
    const created = { ...RULE, id: 'rule-uuid-2', name: 'no-ads' }
    mockApi.create.mockResolvedValue(created)
    mockApi.list
      .mockResolvedValueOnce([])
      .mockResolvedValue([created])

    render(<ModerationRules />)
    await waitFor(() => screen.getByText('Moderation Rules'))
    await userEvent.click(screen.getByRole('button', { name: /create rule/i }))
    // Rule Name is the first textbox in the dialog (labels have no htmlFor)
    await userEvent.type(screen.getAllByRole('textbox')[0], 'no-ads')
    await userEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => expect(screen.getByText('no-ads')).toBeInTheDocument())
    expect(mockApi.create).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'no-ads' })
    )
  })

  it('opens Edit Rule modal with prefilled values', async () => {
    mockApi.list.mockResolvedValue([RULE])
    render(<ModerationRules />)
    await waitFor(() => screen.getByText('no-spam'))
    await userEvent.click(screen.getByText('no-spam'))

    expect(screen.getByText('Edit Rule')).toBeInTheDocument()
    expect(screen.getByDisplayValue('buy now')).toBeInTheDocument()
  })

  it('updates a rule and reloads list', async () => {
    const updated = { ...RULE, pattern: 'buy cheap' }
    mockApi.list.mockResolvedValue([RULE])
    mockApi.update.mockResolvedValue(updated)
    mockApi.list
      .mockResolvedValueOnce([RULE])
      .mockResolvedValue([updated])

    render(<ModerationRules />)
    await waitFor(() => screen.getByText('no-spam'))
    await userEvent.click(screen.getByText('no-spam'))

    const patternInput = screen.getByDisplayValue('buy now')
    await userEvent.clear(patternInput)
    await userEvent.type(patternInput, 'buy cheap')
    await userEvent.click(screen.getByRole('button', { name: /save/i }))

    expect(mockApi.update).toHaveBeenCalledWith(
      RULE.id,
      expect.objectContaining({ pattern: 'buy cheap' })
    )
  })

  it('deletes a rule after confirmation', async () => {
    mockApi.list.mockResolvedValue([RULE])
    mockApi.remove.mockResolvedValue(undefined)

    render(<ModerationRules />)
    await waitFor(() => screen.getByText('no-spam'))
    await userEvent.click(screen.getByRole('button', { name: /delete/i }))
    // ConfirmDialog appears — click the danger Delete button
    const confirmBtn = screen.getAllByRole('button', { name: /delete/i }).at(-1)
    await userEvent.click(confirmBtn)

    expect(mockApi.remove).toHaveBeenCalledWith(RULE.id)
  })

  it('does not delete when confirmation is cancelled', async () => {
    mockApi.list.mockResolvedValue([RULE])

    render(<ModerationRules />)
    await waitFor(() => screen.getByText('no-spam'))
    await userEvent.click(screen.getByRole('button', { name: /delete/i }))
    // ConfirmDialog appears — click Cancel
    await userEvent.click(screen.getByRole('button', { name: /cancel/i }))

    expect(mockApi.remove).not.toHaveBeenCalled()
  })

  it('shows error alert when list fails', async () => {
    mockApi.list.mockRejectedValue(new Error('Service unavailable'))
    render(<ModerationRules />)
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Service unavailable')
    )
  })
})
