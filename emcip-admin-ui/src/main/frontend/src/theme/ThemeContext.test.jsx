import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider, useTheme } from './ThemeContext'

function Toggle() {
  const { theme, toggleTheme } = useTheme()
  return <button onClick={toggleTheme}>{theme}</button>
}

test('starts with light theme during daytime hours', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2024-01-01T10:00:00'))
  localStorage.clear()
  render(<ThemeProvider><Toggle /></ThemeProvider>)
  expect(screen.getByRole('button')).toHaveTextContent('light')
  vi.useRealTimers()
})

test('toggles to dark and persists to localStorage', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2024-01-01T10:00:00'))
  localStorage.clear()
  render(<ThemeProvider><Toggle /></ThemeProvider>)
  await userEvent.click(screen.getByRole('button'))
  expect(screen.getByRole('button')).toHaveTextContent('dark')
  expect(localStorage.getItem('emcip-theme')).toBe('dark')
  vi.useRealTimers()
})

test('reads initial theme from localStorage', () => {
  localStorage.setItem('emcip-theme', 'dark')
  render(<ThemeProvider><Toggle /></ThemeProvider>)
  expect(screen.getByRole('button')).toHaveTextContent('dark')
})
