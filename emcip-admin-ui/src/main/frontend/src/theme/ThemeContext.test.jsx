import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider, useTheme } from './ThemeContext'

function Toggle() {
  const { theme, toggleTheme } = useTheme()
  return <button onClick={toggleTheme}>{theme}</button>
}

test('starts with light theme', () => {
  localStorage.clear()
  render(<ThemeProvider><Toggle /></ThemeProvider>)
  expect(screen.getByRole('button')).toHaveTextContent('light')
})

test('toggles to dark and persists to localStorage', async () => {
  localStorage.clear()
  render(<ThemeProvider><Toggle /></ThemeProvider>)
  await userEvent.click(screen.getByRole('button'))
  expect(screen.getByRole('button')).toHaveTextContent('dark')
  expect(localStorage.getItem('emcip-theme')).toBe('dark')
})

test('reads initial theme from localStorage', () => {
  localStorage.setItem('emcip-theme', 'dark')
  render(<ThemeProvider><Toggle /></ThemeProvider>)
  expect(screen.getByRole('button')).toHaveTextContent('dark')
})
