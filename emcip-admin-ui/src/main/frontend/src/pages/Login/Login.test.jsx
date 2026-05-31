import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider } from '../../auth/AuthContext'
import { ThemeProvider } from '../../theme/ThemeContext'
import { Login } from './Login'

const renderLogin = (onSuccess = vi.fn()) =>
  render(<ThemeProvider><AuthProvider><Login onSuccess={onSuccess} /></AuthProvider></ThemeProvider>)

test('renders operator and passphrase fields', () => {
  renderLogin()
  expect(screen.getByLabelText(/operator/i)).toBeInTheDocument()
  expect(screen.getByLabelText(/passphrase/i)).toBeInTheDocument()
})

test('calls login API on submit', async () => {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ token: 'abc' }),
  })
  const onSuccess = vi.fn()
  renderLogin(onSuccess)
  await userEvent.type(screen.getByLabelText(/operator/i), 'admin')
  await userEvent.type(screen.getByLabelText(/passphrase/i), 'secret')
  await userEvent.click(screen.getByRole('button', { name: /enter the construct/i }))
  await waitFor(() => expect(onSuccess).toHaveBeenCalled())
})

test('shows error message on invalid credentials', async () => {
  global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 401 })
  renderLogin()
  await userEvent.type(screen.getByLabelText(/operator/i), 'bad')
  await userEvent.type(screen.getByLabelText(/passphrase/i), 'wrong')
  await userEvent.click(screen.getByRole('button', { name: /enter the construct/i }))
  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent(/invalid credentials/i)
  )
})
