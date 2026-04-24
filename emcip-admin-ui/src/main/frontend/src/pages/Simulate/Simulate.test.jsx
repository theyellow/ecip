import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider } from '../../auth/AuthContext'
import { ThemeProvider } from '../../theme/ThemeContext'
import { Simulate } from './Simulate'

beforeEach(() => {
  global.fetch = vi.fn()
})

const wrap = ui => render(<ThemeProvider><AuthProvider>{ui}</AuthProvider></ThemeProvider>)

test('publishes via POST to /api/simulate/message', async () => {
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
    // Verify it goes through makeRequest (not a hardcoded relative URL bypassing API_BASE)
    // In test env, VITE_API_BASE is '' so full URL is just '/api/simulate/message'
    // The key: no fetch call to a different path like '/api/simulate' without '/message'
    expect(call[0]).toMatch(/\/api\/simulate\/message$/)
  })
})

test('shows error when fields are empty', async () => {
  wrap(<Simulate />)
  await userEvent.click(screen.getByRole('button', { name: /publish/i }))
  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent(/required/i)
  )
})
