import { render, screen } from '@testing-library/react'
import { SpaceBackground } from './SpaceBackground'
import { ThemeProvider } from '../../theme/ThemeContext'

beforeEach(() => {
  localStorage.clear()
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({
    clearRect: vi.fn(),
    beginPath: vi.fn(),
    arc: vi.fn(),
    fill: vi.fn(),
  }))
})

function renderWithTheme(theme) {
  localStorage.setItem('emcip-theme', theme)
  return render(<ThemeProvider><SpaceBackground /></ThemeProvider>)
}

test('renders stars canvas and both planets in dark mode', () => {
  renderWithTheme('dark')
  expect(screen.getByTestId('stars-canvas')).toBeInTheDocument()
  expect(screen.getByTestId('planet-hero')).toBeInTheDocument()
  expect(screen.getByTestId('planet-distant')).toBeInTheDocument()
})

test('does not render sun in dark mode', () => {
  renderWithTheme('dark')
  expect(screen.queryByTestId('sun')).not.toBeInTheDocument()
})

test('renders sun in light mode', () => {
  renderWithTheme('light')
  expect(screen.getByTestId('sun')).toBeInTheDocument()
})

test('does not render stars canvas or planets in light mode', () => {
  renderWithTheme('light')
  expect(screen.queryByTestId('stars-canvas')).not.toBeInTheDocument()
  expect(screen.queryByTestId('planet-hero')).not.toBeInTheDocument()
})
