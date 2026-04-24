import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider } from '../../auth/AuthContext'
import { Login } from './Login'

const renderLogin = (onSuccess = vi.fn()) =>
  render(<AuthProvider><Login onSuccess={onSuccess} /></AuthProvider>)

test('renders username and password fields', () => {
  renderLogin()
  expect(screen.getByLabelText(/username/i)).toBeInTheDocument()
  expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
})

test('calls login API on submit', async () => {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ token: 'abc' }),
  })
  const onSuccess = vi.fn()
  renderLogin(onSuccess)
  await userEvent.type(screen.getByLabelText(/username/i), 'admin')
  await userEvent.type(screen.getByLabelText(/password/i), 'secret')
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }))
  await waitFor(() => expect(onSuccess).toHaveBeenCalled())
})

test('shows error message on invalid credentials', async () => {
  global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 401 })
  renderLogin()
  await userEvent.type(screen.getByLabelText(/username/i), 'bad')
  await userEvent.type(screen.getByLabelText(/password/i), 'wrong')
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }))
  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent(/invalid credentials/i)
  )
})
