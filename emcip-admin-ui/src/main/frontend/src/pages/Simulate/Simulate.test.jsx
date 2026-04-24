import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider } from '../../auth/AuthContext'
import { ThemeProvider } from '../../theme/ThemeContext'
import { Simulate } from './Simulate'

beforeEach(() => {
  global.fetch = vi.fn()
})

const wrap = ui => render(<ThemeProvider><AuthProvider>{ui}</AuthProvider></ThemeProvider>)

test('publishes to /api/simulate/message via POST', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    status: 202,
    json: async () => ({ eventId: 'abc', status: 'published' }),
  })

  wrap(<Simulate />)

  await userEvent.type(screen.getByLabelText(/chat id/i), '-1001234567890')
  await userEvent.type(screen.getByLabelText(/message text/i), 'Hello world')
  await userEvent.click(screen.getByRole('button', { name: /publish/i }))

  await waitFor(() => {
    const call = fetch.mock.calls.find(c => c[0].includes('/api/simulate/message'))
    expect(call).toBeDefined()
    expect(call[1].method).toBe('POST')
  })
})
