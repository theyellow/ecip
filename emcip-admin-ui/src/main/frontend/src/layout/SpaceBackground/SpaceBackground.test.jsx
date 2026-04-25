import { render, screen } from '@testing-library/react'
import { SpaceBackground } from './SpaceBackground'
import { ThemeProvider } from '../../theme/ThemeContext'

beforeEach(() => {
  localStorage.clear()
  const gradient = { addColorStop: vi.fn() }
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({
    clearRect: vi.fn(),
    beginPath: vi.fn(),
    arc: vi.fn(),
    ellipse: vi.fn(),
    fill: vi.fn(),
    stroke: vi.fn(),
    clip: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    closePath: vi.fn(),
    quadraticCurveTo: vi.fn(),
    createLinearGradient: vi.fn(() => gradient),
    createImageData: vi.fn((w, h) => ({
      data: new Uint8ClampedArray(w * h * 4),
      width: w,
      height: h,
    })),
    putImageData: vi.fn(),
    fillStyle: '',
    strokeStyle: '',
    lineWidth: 1,
    shadowBlur: 0,
    shadowColor: '',
    globalAlpha: 1,
  }))
})

function renderWithTheme(theme) {
  localStorage.setItem('emcip-theme', theme)
  return render(<ThemeProvider><SpaceBackground /></ThemeProvider>)
}

test('renders canvas and both planet markers in dark mode', () => {
  renderWithTheme('dark')
  expect(screen.getByTestId('stars-canvas')).toBeInTheDocument()
  expect(screen.getByTestId('planet-hero')).toBeInTheDocument()
  expect(screen.getByTestId('planet-distant')).toBeInTheDocument()
})

test('does not render sun marker in dark mode', () => {
  renderWithTheme('dark')
  expect(screen.queryByTestId('sun')).not.toBeInTheDocument()
})

test('renders canvas and sun marker in light mode', () => {
  renderWithTheme('light')
  expect(screen.getByTestId('stars-canvas')).toBeInTheDocument()
  expect(screen.getByTestId('sun')).toBeInTheDocument()
})

test('does not render planet markers in light mode', () => {
  renderWithTheme('light')
  expect(screen.queryByTestId('planet-hero')).not.toBeInTheDocument()
  expect(screen.queryByTestId('planet-distant')).not.toBeInTheDocument()
})
