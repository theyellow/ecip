import { render, screen } from '@testing-library/react'
import { SpaceBackground } from './SpaceBackground'
import { ThemeProvider } from '../../theme/ThemeContext'

beforeEach(() => {
  localStorage.clear()
  window.matchMedia = vi.fn().mockReturnValue({ matches: false })
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({
    clearRect: vi.fn(),
    beginPath: vi.fn(),
    arc: vi.fn(),
    fill: vi.fn(),
    shadowBlur: 0,
    shadowColor: '',
    fillStyle: '',
  }))
})

function renderWithTheme(theme) {
  localStorage.setItem('emcip-theme', theme)
  return render(<ThemeProvider><SpaceBackground /></ThemeProvider>)
}

test('renders root and canvas in dark mode', () => {
  renderWithTheme('dark')
  expect(screen.getByTestId('space-background')).toBeInTheDocument()
  expect(screen.getByTestId('sky-canvas')).toBeInTheDocument()
})

test('renders root and canvas in light mode', () => {
  renderWithTheme('light')
  expect(screen.getByTestId('space-background')).toBeInTheDocument()
  expect(screen.getByTestId('sky-canvas')).toBeInTheDocument()
})
