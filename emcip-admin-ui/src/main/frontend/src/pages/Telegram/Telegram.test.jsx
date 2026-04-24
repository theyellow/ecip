import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { Telegram } from './Telegram'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
}))

vi.mock('../../api/telegram', () => ({
  telegramApi: () => ({
    getStatus: vi.fn().mockResolvedValue({ status: 'CONNECTED', message: 'Ready', phoneNumber: '+49123456' }),
    getConfig: vi.fn().mockResolvedValue({
      phoneNumber: '+49123456',
      apiId: 12345,
      apiHash: 'abc',
      sessionStringSet: true,
    }),
    saveConfig: vi.fn().mockResolvedValue({ saved: true }),
    reconnect: vi.fn().mockResolvedValue({ accepted: true }),
  }),
}))

vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

describe('Telegram page', () => {
  it('shows connection status badge', async () => {
    render(<Telegram />)
    await waitFor(() => {
      expect(screen.getByText('CONNECTED')).toBeInTheDocument()
    })
  })

  it('shows stored phone number in form', async () => {
    render(<Telegram />)
    await waitFor(() => {
      expect(screen.getByDisplayValue('+49123456')).toBeInTheDocument()
    })
  })

  it('save button submits config', async () => {
    render(<Telegram />)
    await waitFor(() => screen.getByDisplayValue('+49123456'))
    await userEvent.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => {
      expect(screen.getByText(/saved/i)).toBeInTheDocument()
    })
  })
})
