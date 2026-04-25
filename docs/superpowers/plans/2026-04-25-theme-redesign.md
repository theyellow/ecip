# Theme Redesign — "The Lattice" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current indigo/cyan admin UI theme with "The Lattice" — an Adams × Williams sci-fi/fantasy aesthetic featuring gold + cosmic blue-violet accents, semi-transparent layered panels, rotating planets (dark mode), and a glowing sun (light mode).

**Architecture:** Pure CSS/React change — no backend involvement. The existing `StarField` canvas component is replaced by a new `SpaceBackground` component that handles all background layers (stars, planets, sun) in one place. Theme tokens in `variables.css` drive all colour changes downstream; no individual page components need touching.

**Tech Stack:** React 18, CSS Modules, CSS custom properties, SVG, Vitest + React Testing Library, Google Fonts (Cinzel)

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `emcip-admin-ui/src/main/frontend/index.html` | Modify | Replace Orbitron font link with Cinzel |
| `emcip-admin-ui/src/main/frontend/src/index.css` | Modify | Update `.emcip-wordmark`, add `h2` style |
| `emcip-admin-ui/src/main/frontend/src/theme/variables.css` | Modify | Full palette replacement — all CSS tokens |
| `emcip-admin-ui/src/main/frontend/src/logo/Logo.jsx` | Modify | Increase SVG stroke weights |
| `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.jsx` | Create | Stars canvas + planets (dark) + sun (light) |
| `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.module.css` | Create | Rotation + pulse CSS keyframe animations |
| `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.test.jsx` | Create | Tests: dark renders stars+planets, light renders sun |
| `emcip-admin-ui/src/main/frontend/src/layout/StarField/StarField.jsx` | Delete | Replaced by SpaceBackground |
| `emcip-admin-ui/src/main/frontend/src/layout/AppShell/AppShell.jsx` | Modify | Update import StarField → SpaceBackground |
| `emcip-admin-ui/src/main/frontend/src/layout/AppShell/AppShell.module.css` | Modify | Add `backdrop-filter: blur(2px)` to `.main` |
| `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.module.css` | Modify | Gold border-right, backdrop-filter, violet hover |
| `emcip-admin-ui/src/main/frontend/src/components/Modal/Modal.module.css` | Modify | Dark overlay, backdrop-filter on card |

> **Note:** `Button.module.css` does NOT need changes — it already uses `var(--accent)` and `var(--accent-text)`. The palette update alone fixes button colours.

---

## Task 1: Font — Replace Orbitron with Cinzel

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/index.html`
- Modify: `emcip-admin-ui/src/main/frontend/src/index.css`

- [ ] **Step 1: Update index.html — swap font links**

Replace lines 7–9 (the three Orbitron-related font tags) with:

```html
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
  <link href="https://fonts.googleapis.com/css2?family=Cinzel:wght@700;900&display=swap" rel="stylesheet" />
```

- [ ] **Step 2: Update index.css — wordmark class + h2 global style**

Replace the entire file with:

```css
@import './theme/variables.css';

*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

html, body, #root {
  height: 100%;
  font-family: system-ui, -apple-system, sans-serif;
  background: var(--bg-primary);
  color: var(--text-primary);
  transition: background 0.2s, color 0.2s;
}

.emcip-wordmark {
  font-family: 'Cinzel', serif;
  font-weight: 900;
  letter-spacing: 0.2em;
  color: var(--accent);
}

h2 {
  font-family: 'Cinzel', serif;
  font-weight: 700;
  color: var(--accent);
}

a { color: var(--accent); text-decoration: none; }
```

- [ ] **Step 3: Commit**

```bash
cd emcip-admin-ui/src/main/frontend
git add index.html src/index.css
git commit -m "feat(admin-ui): replace Orbitron with Cinzel — cinematic wordmark and h2 headers"
```

---

## Task 2: Colour Palette — Full Token Replacement

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/theme/variables.css`

- [ ] **Step 1: Replace variables.css completely**

```css
:root {
  --bg-primary: rgba(250, 247, 240, 0.88);
  --bg-secondary: rgba(240, 235, 220, 0.80);
  --bg-card: rgba(255, 255, 255, 0.92);
  --text-primary: #1a1520;
  --text-secondary: #5a5060;
  --text-muted: #9a9098;
  --accent: #a07828;
  --accent-hover: #c9a84c;
  --accent-text: #1a1520;
  --accent-secondary: #5b4fd4;
  --border: rgba(160, 120, 40, 0.20);
  --shadow: rgba(100, 80, 20, 0.08);
  --sidebar-bg: rgba(8, 8, 20, 0.92);
  --sidebar-text: #7a7090;
  --sidebar-active-bg: rgba(201, 168, 76, 0.08);
  --sidebar-active-text: #c9a84c;
  --badge-green-bg: #dcfce7;
  --badge-green-text: #166534;
  --badge-blue-bg: #dbeafe;
  --badge-blue-text: #1e40af;
  --badge-yellow-bg: #fef9c3;
  --badge-yellow-text: #854d0e;
  --badge-red-bg: #fee2e2;
  --badge-red-text: #991b1b;
  --badge-gray-bg: #f1f5f9;
  --badge-gray-text: #475569;
}

[data-theme="dark"] {
  --bg-primary: rgba(13, 11, 36, 0.82);
  --bg-secondary: rgba(10, 8, 28, 0.75);
  --bg-card: rgba(19, 17, 48, 0.88);
  --text-primary: #e8e4d8;
  --text-secondary: #8a8090;
  --text-muted: #504858;
  --accent: #c9a84c;
  --accent-hover: #f0c84a;
  --accent-text: #0d0b24;
  --accent-secondary: #7b6cf6;
  --border: rgba(201, 168, 76, 0.15);
  --shadow: rgba(201, 168, 76, 0.05);
  --sidebar-bg: rgba(8, 8, 20, 0.90);
  --sidebar-text: #7a7090;
  --sidebar-active-bg: rgba(201, 168, 76, 0.08);
  --sidebar-active-text: #c9a84c;
  --badge-green-bg: #0a2a14;
  --badge-green-text: #4ade80;
  --badge-blue-bg: #0c1a3a;
  --badge-blue-text: #60a5fa;
  --badge-yellow-bg: #2a1e04;
  --badge-yellow-text: #f0c84a;
  --badge-red-bg: #2a0808;
  --badge-red-text: #f87171;
  --badge-gray-bg: rgba(19, 17, 48, 0.88);
  --badge-gray-text: #8a8090;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/theme/variables.css
git commit -m "feat(admin-ui): The Lattice colour palette — gold/violet tokens, semi-transparent layers"
```

---

## Task 3: Logo — Bolder Stroke Weights

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/logo/Logo.jsx`

- [ ] **Step 1: Update strokeWidth values**

Replace the entire file with:

```jsx
export function Logo({ size = 40, className = '' }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 40 40"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
      aria-label="The Construct — EMCIP logo"
    >
      {/* Hexagon */}
      <polygon
        points="20,5 32.99,12.5 32.99,27.5 20,35 7.01,27.5 7.01,12.5"
        stroke="currentColor"
        strokeWidth="2.5"
        fill="none"
      />
      {/* Circuit traces from each corner */}
      <path
        d="M20,5 L20,1 M32.99,12.5 L37.32,10 M32.99,27.5 L37.32,30 M20,35 L20,39 M7.01,27.5 L2.68,30 M7.01,12.5 L2.68,10"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
      {/* Eye — outer ellipse */}
      <ellipse
        cx="20" cy="20" rx="6" ry="4"
        stroke="currentColor"
        strokeWidth="1.5"
        fill="none"
      />
      {/* Eye — iris */}
      <circle
        cx="20" cy="20" r="2"
        stroke="currentColor"
        strokeWidth="1.5"
        fill="none"
      />
      {/* Eye — pupil */}
      <circle cx="20" cy="20" r="0.8" fill="currentColor" />
    </svg>
  )
}
```

- [ ] **Step 2: Commit**

```bash
git add src/logo/Logo.jsx
git commit -m "feat(admin-ui): bolder logo stroke weights — hex 2.5, traces 1.5, iris 1.5"
```

---

## Task 4: SpaceBackground Component (TDD)

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.test.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.module.css`
- Delete: `emcip-admin-ui/src/main/frontend/src/layout/StarField/StarField.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/AppShell/AppShell.jsx`

- [ ] **Step 1: Write the failing tests**

Create `src/layout/SpaceBackground/SpaceBackground.test.jsx`:

```jsx
import { render, screen } from '@testing-library/react'
import { SpaceBackground } from './SpaceBackground'
import { ThemeProvider } from '../../theme/ThemeContext'

beforeEach(() => {
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
```

- [ ] **Step 2: Run tests — confirm they fail**

```bash
cd emcip-admin-ui/src/main/frontend
npx vitest run src/layout/SpaceBackground/SpaceBackground.test.jsx
```

Expected: 4 failures — `SpaceBackground` not found.

- [ ] **Step 3: Create SpaceBackground.module.css**

Create `src/layout/SpaceBackground/SpaceBackground.module.css`:

```css
@keyframes rotateA {
  to { transform: rotate(360deg); }
}

@keyframes rotateB {
  to { transform: rotate(-360deg); }
}

@keyframes sunPulse {
  0%, 100% { opacity: 0.85; }
  50%       { opacity: 1; }
}

.planetHeroSvg {
  animation: rotateA 120s linear infinite;
  display: block;
}

.planetDistantSvg {
  animation: rotateB 180s linear infinite;
  display: block;
}

.sunPulse {
  animation: sunPulse 8s ease-in-out infinite;
  display: block;
}
```

- [ ] **Step 4: Create SpaceBackground.jsx**

Create `src/layout/SpaceBackground/SpaceBackground.jsx`:

```jsx
import { useEffect, useRef, useState } from 'react'
import { useTheme } from '../../theme/ThemeContext'
import styles from './SpaceBackground.module.css'

const TOTAL = 150
const DRIFTERS = 8
const SIZES = [0.5, 1, 1.5]
const PARALLAX = [0.5, 1.5, 3]

export function SpaceBackground() {
  const { theme } = useTheme()
  const canvasRef = useRef(null)
  const animRef = useRef(null)
  const mouseRef = useRef({ x: 0, y: 0 })
  const starsRef = useRef(null)
  const [planetOffset, setPlanetOffset] = useState({ x: 0, y: 0 })

  useEffect(() => {
    if (theme !== 'dark') {
      if (animRef.current) cancelAnimationFrame(animRef.current)
      return
    }

    const canvas = canvasRef.current
    const ctx = canvas.getContext('2d')

    const resize = () => {
      canvas.width = window.innerWidth
      canvas.height = window.innerHeight
    }
    resize()
    window.addEventListener('resize', resize)

    starsRef.current = Array.from({ length: TOTAL }, (_, i) => ({
      x: Math.random() * canvas.width,
      y: Math.random() * canvas.height,
      size: SIZES[Math.floor(Math.random() * 3)],
      opacity: 0.4 + Math.random() * 0.6,
      drift: i < DRIFTERS,
      vx: (Math.random() - 0.5) * 0.04,
      vy: (Math.random() - 0.5) * 0.04,
      layer: Math.floor(Math.random() * 3),
    }))

    const draw = () => {
      const w = canvas.width
      const h = canvas.height
      ctx.clearRect(0, 0, w, h)
      const mx = (mouseRef.current.x / w - 0.5) * 10
      const my = (mouseRef.current.y / h - 0.5) * 10
      for (const star of starsRef.current) {
        if (star.drift) {
          star.x = (star.x + star.vx + w) % w
          star.y = (star.y + star.vy + h) % h
        }
        const px = star.x + mx * PARALLAX[star.layer]
        const py = star.y + my * PARALLAX[star.layer]
        ctx.beginPath()
        ctx.arc(px, py, star.size, 0, Math.PI * 2)
        ctx.fillStyle = `rgba(255,255,255,${star.opacity})`
        ctx.fill()
      }
      animRef.current = requestAnimationFrame(draw)
    }
    draw()

    const onMouse = e => {
      mouseRef.current = { x: e.clientX, y: e.clientY }
      setPlanetOffset({
        x: (e.clientX / window.innerWidth - 0.5) * 3,
        y: (e.clientY / window.innerHeight - 0.5) * 3,
      })
    }
    window.addEventListener('mousemove', onMouse)

    return () => {
      cancelAnimationFrame(animRef.current)
      window.removeEventListener('resize', resize)
      window.removeEventListener('mousemove', onMouse)
    }
  }, [theme])

  const outerStyle = {
    position: 'fixed', top: 0, left: 0,
    width: '100vw', height: '100vh',
    zIndex: -1, pointerEvents: 'none',
    overflow: 'hidden',
  }

  if (theme === 'light') {
    return (
      <div style={outerStyle} data-testid="space-background">
        <div style={{ position: 'absolute', bottom: -60, right: -60 }} data-testid="sun">
          <svg className={styles.sunPulse} width="280" height="280" viewBox="0 0 280 280">
            <defs>
              <radialGradient id="sunCore" cx="50%" cy="50%" r="50%">
                <stop offset="0%"   stopColor="#fff7c0"/>
                <stop offset="40%"  stopColor="#f5c842"/>
                <stop offset="100%" stopColor="#f5a800" stopOpacity="0.8"/>
              </radialGradient>
              <radialGradient id="corona1" cx="50%" cy="50%" r="50%">
                <stop offset="55%"  stopColor="#f5c842" stopOpacity="0.22"/>
                <stop offset="100%" stopColor="#f5a800" stopOpacity="0"/>
              </radialGradient>
              <radialGradient id="corona2" cx="50%" cy="50%" r="50%">
                <stop offset="65%"  stopColor="#f5c842" stopOpacity="0.10"/>
                <stop offset="100%" stopColor="#f5a800" stopOpacity="0"/>
              </radialGradient>
              <radialGradient id="corona3" cx="50%" cy="50%" r="50%">
                <stop offset="72%"  stopColor="#f5c842" stopOpacity="0.05"/>
                <stop offset="100%" stopColor="#f5a800" stopOpacity="0"/>
              </radialGradient>
            </defs>
            <ellipse cx="140" cy="140" rx="138" ry="138" fill="url(#corona3)"/>
            <ellipse cx="140" cy="140" rx="118" ry="118" fill="url(#corona2)"/>
            <ellipse cx="140" cy="140" rx="100" ry="100" fill="url(#corona1)"/>
            <circle  cx="140" cy="140" r="76"           fill="url(#sunCore)"/>
          </svg>
        </div>
      </div>
    )
  }

  return (
    <div style={outerStyle} data-testid="space-background">
      <canvas
        ref={canvasRef}
        data-testid="stars-canvas"
        style={{ position: 'absolute', top: 0, left: 0, width: '100%', height: '100%' }}
      />

      {/* Planet A — hero, lower-left, ringed */}
      <div
        data-testid="planet-hero"
        style={{
          position: 'absolute', bottom: -100, left: -80,
          transform: `translate(${planetOffset.x * 3}px, ${planetOffset.y * 3}px)`,
          transition: 'transform 0.15s ease-out',
        }}
      >
        <svg className={styles.planetHeroSvg} width="340" height="340" viewBox="0 0 340 340">
          <defs>
            <radialGradient id="planetABody" cx="38%" cy="32%" r="62%">
              <stop offset="0%"   stopColor="#2a6b7c"/>
              <stop offset="55%"  stopColor="#1a3a5c"/>
              <stop offset="100%" stopColor="#0d1a3a"/>
            </radialGradient>
            <radialGradient id="planetAGlow" cx="50%" cy="50%" r="50%">
              <stop offset="68%"  stopColor="#1a3a5c" stopOpacity="0"/>
              <stop offset="100%" stopColor="#00a0c0" stopOpacity="0.22"/>
            </radialGradient>
            <linearGradient id="ringAGrad" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%"   stopColor="#c9a84c" stopOpacity="0.08"/>
              <stop offset="28%"  stopColor="#c9a84c" stopOpacity="0.55"/>
              <stop offset="72%"  stopColor="#7b6cf6" stopOpacity="0.45"/>
              <stop offset="100%" stopColor="#7b6cf6" stopOpacity="0.08"/>
            </linearGradient>
          </defs>
          <circle  cx="170" cy="170" r="164"                             fill="url(#planetAGlow)"/>
          <ellipse cx="170" cy="188" rx="235" ry="40" fill="none" stroke="url(#ringAGrad)" strokeWidth="20" opacity="0.6"/>
          <circle  cx="170" cy="170" r="132"                             fill="url(#planetABody)"/>
        </svg>
      </div>

      {/* Planet B — distant, upper-right */}
      <div
        data-testid="planet-distant"
        style={{
          position: 'absolute', top: -30, right: -30,
          transform: `translate(${-planetOffset.x * 1.5}px, ${planetOffset.y * 1.5}px)`,
          transition: 'transform 0.15s ease-out',
        }}
      >
        <svg className={styles.planetDistantSvg} width="130" height="130" viewBox="0 0 130 130">
          <defs>
            <radialGradient id="planetBBody" cx="34%" cy="28%" r="66%">
              <stop offset="0%"   stopColor="#4a4a7a"/>
              <stop offset="62%"  stopColor="#2a2a50"/>
              <stop offset="100%" stopColor="#15152e"/>
            </radialGradient>
            <radialGradient id="planetBGlow" cx="50%" cy="50%" r="50%">
              <stop offset="68%"  stopColor="#2a2a50" stopOpacity="0"/>
              <stop offset="100%" stopColor="#7b6cf6" stopOpacity="0.18"/>
            </radialGradient>
          </defs>
          <circle cx="65" cy="65" r="63" fill="url(#planetBGlow)"/>
          <circle cx="65" cy="65" r="48" fill="url(#planetBBody)"/>
        </svg>
      </div>
    </div>
  )
}
```

- [ ] **Step 5: Run tests — confirm they pass**

```bash
npx vitest run src/layout/SpaceBackground/SpaceBackground.test.jsx
```

Expected: 4 tests pass.

- [ ] **Step 6: Update AppShell.jsx — swap import**

Replace the entire file with:

```jsx
import { Outlet } from 'react-router-dom'
import { Sidebar } from '../Sidebar/Sidebar'
import { SpaceBackground } from '../SpaceBackground/SpaceBackground'
import styles from './AppShell.module.css'

export function AppShell() {
  return (
    <>
      <SpaceBackground />
      <div className={styles.shell}>
        <Sidebar />
        <main className={styles.main}>
          <Outlet />
        </main>
      </div>
    </>
  )
}
```

- [ ] **Step 7: Delete StarField.jsx**

```bash
git rm src/layout/StarField/StarField.jsx
```

- [ ] **Step 8: Run full test suite**

```bash
npx vitest run
```

Expected: all tests pass (no test referenced `StarField` directly).

- [ ] **Step 9: Commit**

```bash
git add src/layout/SpaceBackground/ src/layout/AppShell/AppShell.jsx
git commit -m "feat(admin-ui): SpaceBackground — rotating planets (dark) + pulsing sun (light), replaces StarField"
```

---

## Task 5: AppShell + Sidebar CSS

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/AppShell/AppShell.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.module.css`

- [ ] **Step 1: Update AppShell.module.css — add backdrop-filter**

Replace the entire file with:

```css
.shell { display: flex; min-height: 100vh; }
.main  { flex: 1; padding: 2rem; background: var(--bg-primary); backdrop-filter: blur(2px); overflow-y: auto; min-height: 100vh; }
```

- [ ] **Step 2: Update Sidebar.module.css — border, blur, violet hover**

Replace the entire file with:

```css
.sidebar     { width: 220px; min-height: 100vh; background: var(--sidebar-bg); backdrop-filter: blur(8px); display: flex; flex-direction: column; padding: 1.25rem 0; flex-shrink: 0; border-right: 1px solid rgba(201, 168, 76, 0.12); }
.brand       { display: flex; align-items: center; gap: 0.6rem; padding: 0 1.25rem 1.5rem; border-bottom: 1px solid rgba(201, 168, 76, 0.10); }
.logo        { color: var(--sidebar-active-text); }
.wordmark    { font-size: 1.1rem; color: var(--sidebar-active-text); }
.nav         { flex: 1; display: flex; flex-direction: column; padding: 1rem 0; gap: 0.1rem; }
.item        { display: flex; align-items: center; gap: 0.6rem; padding: 0.6rem 1.25rem; color: var(--sidebar-text); font-size: 0.875rem; transition: background 0.15s, color 0.15s; }
.item:hover  { background: rgba(123, 108, 246, 0.10); color: var(--sidebar-text); }
.active      { background: var(--sidebar-active-bg); color: var(--sidebar-active-text); }
.icon        { font-size: 1rem; width: 18px; text-align: center; }
.footer      { padding: 1rem 1.25rem; border-top: 1px solid rgba(201, 168, 76, 0.10); }
.themeToggle { background: none; border: none; cursor: pointer; font-size: 1.1rem; color: var(--sidebar-text); padding: 0.25rem; transition: color 0.15s; }
.themeToggle:hover { color: var(--sidebar-active-text); }
```

- [ ] **Step 3: Commit**

```bash
git add src/layout/AppShell/AppShell.module.css src/layout/Sidebar/Sidebar.module.css
git commit -m "feat(admin-ui): depth layers — backdrop-filter on main + sidebar, gold border-right"
```

---

## Task 6: Modal CSS

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/components/Modal/Modal.module.css`

- [ ] **Step 1: Update Modal.module.css — dark overlay + card blur**

Replace the entire file with:

```css
.overlay { position: fixed; inset: 0; background: rgba(8, 8, 20, 0.75); backdrop-filter: blur(2px); display: flex; align-items: center; justify-content: center; z-index: 100; }
.card    { background: var(--bg-card); backdrop-filter: blur(16px); border: 1px solid var(--border); border-radius: 10px; width: 520px; max-width: 95vw; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 8px 32px var(--shadow); }
.header  { display: flex; align-items: center; justify-content: space-between; padding: 1rem 1.25rem; border-bottom: 1px solid var(--border); }
.header h3 { font-size: 1rem; font-weight: 600; color: var(--text-primary); }
.close   { background: none; border: none; cursor: pointer; color: var(--text-muted); font-size: 1.1rem; padding: 0.25rem; }
.body    { padding: 1.25rem; overflow-y: auto; flex: 1; display: flex; flex-direction: column; gap: 0.75rem; }
.footer  { padding: 1rem 1.25rem; border-top: 1px solid var(--border); display: flex; gap: 0.5rem; justify-content: flex-end; }
```

- [ ] **Step 2: Run full test suite one final time**

```bash
cd emcip-admin-ui/src/main/frontend
npx vitest run
```

Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/components/Modal/Modal.module.css
git commit -m "feat(admin-ui): modal — dark void overlay, backdrop-blur card, The Lattice complete"
```

---

---

## Task 7: Table Header + Row Hover + Input Focus — All Pages

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/theme/variables.css` (append 3 tokens)
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Tenants/Tenants.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Simulate/Simulate.module.css`

- [ ] **Step 1: Append 3 new tokens to variables.css**

In `:root` add after `--badge-gray-text`:
```css
  --table-header-bg: rgba(160, 120, 40, 0.05);
  --table-row-hover: rgba(91, 79, 212, 0.04);
  --input-bg: rgba(255, 255, 255, 0.85);
```

In `[data-theme="dark"]` add after `--badge-gray-text`:
```css
  --table-header-bg: rgba(201, 168, 76, 0.06);
  --table-row-hover: rgba(123, 108, 246, 0.06);
  --input-bg: rgba(8, 8, 20, 0.60);
```

- [ ] **Step 2: Update Tenants.module.css**

Replace line 4 (`.table th`):
```css
.table th { padding: 0.7rem 1rem; text-align: left; font-size: 0.75rem; font-weight: 600; letter-spacing: 0.05em; text-transform: uppercase; color: var(--text-muted); background: var(--table-header-bg); border-bottom: 1px solid var(--border); }
```

Add after line 6 (`.table tr:last-child td`):
```css
.table tr:hover td { background: var(--table-row-hover); }
```

Replace line 10 (`.input`):
```css
.input { width: 100%; padding: 0.5rem 0.75rem; border: 1px solid var(--border); border-radius: 6px; background: var(--input-bg); color: var(--text-primary); font-size: 0.875rem; }
.input:focus { border-color: var(--accent); outline: none; box-shadow: 0 0 0 2px rgba(201, 168, 76, 0.30); }
```

- [ ] **Step 3: Update Groups.module.css (same pattern as Tenants)**

Replace `.table th` background `var(--bg-secondary)` → `var(--table-header-bg)`.

Add `.table tr:hover td { background: var(--table-row-hover); }` after the last-child rule.

Replace `.input` background `var(--bg-secondary)` → `var(--input-bg)` and add `.input:focus` rule identical to Step 2.

- [ ] **Step 4: Update PolicyRules.module.css (same pattern as Tenants)**

Replace `.table th` background `var(--bg-secondary)` → `var(--table-header-bg)`.

Add `.table tr:hover td { background: var(--table-row-hover); }` after the last-child rule.

Replace `.input` background `var(--bg-secondary)` → `var(--input-bg)` and add `.input:focus` rule identical to Step 2.

- [ ] **Step 5: Update AuditLog.module.css (no .input — table only)**

Replace `.table th` background `var(--bg-secondary)` → `var(--table-header-bg)`.

Add after `.table tr:last-child td { border-bottom: none; }`:
```css
.table tr:hover td { background: var(--table-row-hover); }
```

- [ ] **Step 6: Update AIConfig.module.css (no bg on th — row hover + input only)**

After `.table td` block (line ~37) add:
```css
.table tr:hover td { background: var(--table-row-hover); }
```

Replace `.input` background `var(--bg-secondary)` → `var(--input-bg)`.

After the `.input` block add:
```css
.input:focus { border-color: var(--accent); outline: none; box-shadow: 0 0 0 2px rgba(201, 168, 76, 0.30); }
```

- [ ] **Step 7: Update Simulate.module.css (input only, no table)**

Replace `.input` background `var(--bg-secondary)` → `var(--input-bg)`.

Add after `.input`:
```css
.input:focus { border-color: var(--accent); outline: none; box-shadow: 0 0 0 2px rgba(201, 168, 76, 0.30); }
```

- [ ] **Step 8: Commit**

```bash
git add src/theme/variables.css src/pages/
git commit -m "feat(admin-ui): gold table headers, violet row hover, gold input focus ring — all pages"
```

---

## Verification

After all tasks, run:

```bash
cd emcip-admin-ui/src/main/frontend
npx vitest run
```

And build:

```bash
cd emcip-admin-ui
mvn spotless:apply && mvn frontend:install-node-and-npm frontend:npm@build -pl . -q
```

Expected: build succeeds, no Spotless violations.
