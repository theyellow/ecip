import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { Sidebar } from './Sidebar'

const mockLogout = vi.fn()

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ logout: mockLogout }),
}))

vi.mock('../../theme/ThemeContext', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: vi.fn() }),
}))

vi.mock('../../logo/Logo', () => ({
  Logo: () => <svg data-testid="logo" />,
}))

function renderSidebar() {
  return render(
    <MemoryRouter>
      <Sidebar />
    </MemoryRouter>,
  )
}

describe('Sidebar', () => {
  it('renders the logout button', () => {
    renderSidebar()
    expect(screen.getByRole('button', { name: /logout/i })).toBeInTheDocument()
  })

  it('calls logout when the logout button is clicked', async () => {
    mockLogout.mockClear()
    renderSidebar()
    await userEvent.click(screen.getByRole('button', { name: /logout/i }))
    expect(mockLogout).toHaveBeenCalledOnce()
  })
})
