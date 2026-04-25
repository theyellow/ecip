# Space Background — Realistic Planets & Sun Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace SVG-circle planets and sun with per-pixel canvas-rendered bodies using real Lambertian sphere shading and simplex-noise animated surface texture.

**Architecture:** A single canvas element replaces the hybrid canvas+SVG approach. Both dark-mode planets and the light-mode sun are drawn pixel-by-pixel using pre-computed lighting maps and `simplex-noise` for animated cloud bands / corona rays. Test-ID `<div>` stubs are kept so existing selectors compile.

**Tech Stack:** React 18, Vite, Vitest + React Testing Library, `simplex-noise` v4 (ESM, ~5 KB gzip)

---

### Task 1: Install `simplex-noise` and update tests to match new structure

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/package.json`
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.test.jsx`

The new component always renders a `<canvas data-testid="stars-canvas">` (used for both stars and sun), plus invisible `<div>` stubs for dark-mode planet IDs or light-mode sun ID. The old test "does not render stars canvas or planets in light mode" is wrong under the new architecture — canvas is always present.

- [ ] **Step 1: Write the updated test file**

Replace the entire contents of `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.test.jsx`:

```jsx
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
```

- [ ] **Step 2: Install simplex-noise**

```bash
cd emcip-admin-ui/src/main/frontend
npm install simplex-noise
```

Expected: `package.json` gains `"simplex-noise": "^4.x.x"` in `dependencies`.

- [ ] **Step 3: Run tests — expect failures**

```bash
cd emcip-admin-ui/src/main/frontend
npm test -- --run
```

Expected: 2–3 tests fail because `SpaceBackground.jsx` still renders the old SVG structure (no canvas in light mode, SVG planet divs instead of invisible zero-size divs). The new tests for canvas-in-light-mode and "no planet markers in light mode" will fail.

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/package.json \
        emcip-admin-ui/src/main/frontend/package-lock.json \
        emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.test.jsx
git commit -m "test(admin-ui): update SpaceBackground tests for canvas-only rendering"
```

---

### Task 2: Rewrite SpaceBackground.jsx with per-pixel canvas rendering

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.jsx`

This is the full rewrite. All helper functions are defined at module scope (not inside the component) so they can be understood and tested in isolation. The component only holds refs and the `useEffect` draw loop.

- [ ] **Step 1: Replace the entire file**

```jsx
import { useEffect, useRef } from 'react'
import { createNoise2D } from 'simplex-noise'
import { useTheme } from '../../theme/ThemeContext'

// ─── Star constants ──────────────────────────────────────────────────────────
const STAR_COUNT = 150
const STAR_DRIFTERS = 8
const STAR_SIZES = [0.5, 1, 1.5]
const STAR_PARALLAX = [0.5, 1.5, 3]

// ─── Planet / sun radii ───────────────────────────────────────────────────────
const PLANET_A_R = 132
const PLANET_B_R = 48
const SUN_R = 76

// ─── Shared directional light (upper-left, toward viewer) ─────────────────────
const LX = -0.55
const LY = -0.45
const LZ = 0.70

// ─── Colour palettes ──────────────────────────────────────────────────────────
const PLANET_A_STOPS = [
  { t: -1.0, r: 13,  g: 26,  b: 58  },
  { t: -0.4, r: 18,  g: 60,  b: 80  },
  { t:  0.0, r: 26,  g: 80,  b: 100 },
  { t:  0.5, r: 80,  g: 60,  b: 140 },
  { t:  1.0, r: 13,  g: 13,  b: 46  },
]

const PLANET_B_STOPS = [
  { t: -1.0, r: 15,  g: 15,  b: 30  },
  { t: -0.2, r: 35,  g: 40,  b: 80  },
  { t:  0.4, r: 70,  g: 65,  b: 110 },
  { t:  1.0, r: 180, g: 185, b: 200 },
]

// ─── Ring bands for Planet A ──────────────────────────────────────────────────
const RINGS = [
  { scale: 2.10, width: 28, r: 201, g: 168, b: 76,  opacity: 0.12 },
  { scale: 1.88, width: 16, r: 201, g: 168, b: 76,  opacity: 0.45 },
  { scale: 1.58, width: 22, r: 176, g: 144, b: 80,  opacity: 0.60 },
  { scale: 1.38, width: 10, r: 123, g: 108, b: 246, opacity: 0.30 },
]

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Pre-computes a per-pixel Lambert lighting value for a sphere of radius r.
 * Returns { map: Float32Array, size: number } where size = r * 2.
 * Pixels outside the sphere are stored as -1 (skip when drawing).
 */
function computeLightMap(r, ambient, limbFactor, lx = LX, ly = LY, lz = LZ) {
  const size = r * 2
  const map = new Float32Array(size * size)
  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      const nx = (px - r + 0.5) / r
      const ny = (py - r + 0.5) / r
      const nzSq = 1 - nx * nx - ny * ny
      if (nzSq <= 0) { map[py * size + px] = -1; continue }
      const nz = Math.sqrt(nzSq)
      const diffuse = Math.max(0, nx * lx + ny * ly + nz * lz)
      const limb = nz * limbFactor + (1 - limbFactor)
      map[py * size + px] = Math.min(1, (diffuse + ambient) * limb)
    }
  }
  return { map, size }
}

/** Linear interpolation across a sorted colour stop array. */
function lerpColor(stops, t) {
  if (t <= stops[0].t) return [stops[0].r, stops[0].g, stops[0].b]
  for (let i = 0; i < stops.length - 1; i++) {
    if (t <= stops[i + 1].t) {
      const a = stops[i], b = stops[i + 1]
      const f = (t - a.t) / (b.t - a.t)
      return [a.r + (b.r - a.r) * f, a.g + (b.g - a.g) * f, a.b + (b.b - a.b) * f]
    }
  }
  const l = stops[stops.length - 1]
  return [l.r, l.g, l.b]
}

/**
 * Draws a planet disc using per-pixel Lambert shading + simplex-noise texture.
 * noiseB / scaleXB / scaleYB / driftB / mixB: optional second noise pass (Planet B craters).
 */
function drawDisc(
  ctx, cx, cy, r, lightData, stops,
  noise2D, scaleX, scaleY, drift, time,
  noiseB, scaleXB, scaleYB, driftB, mixB,
) {
  const { map, size } = lightData
  const imgData = ctx.createImageData(size, size)
  const d = imgData.data
  const ox = Math.round(cx - r)
  const oy = Math.round(cy - r)
  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      const light = map[py * size + px]
      if (light < 0) continue
      const wx = ox + px
      const wy = oy + py
      let t = noise2D(wx * scaleX, wy * scaleY + time * drift)
      if (noiseB) {
        const t2 = noiseB(wx * scaleXB, wy * scaleYB + time * driftB)
        t = t * (1 - mixB) + t2 * mixB
      }
      const [cr, cg, cb] = lerpColor(stops, t)
      const i = (py * size + px) * 4
      d[i]     = Math.min(255, Math.round(cr * light))
      d[i + 1] = Math.min(255, Math.round(cg * light))
      d[i + 2] = Math.min(255, Math.round(cb * light))
      d[i + 3] = 255
    }
  }
  ctx.putImageData(imgData, ox, oy)
}

/**
 * Draws one half of the ring system for Planet A.
 * half='back'  → upper arc (behind planet, drawn before disc)
 * half='front' → lower arc (in front of planet, drawn after disc)
 */
function drawRingHalf(ctx, cx, cy, r, half) {
  const startAngle = half === 'back' ? Math.PI : 0
  const endAngle   = half === 'back' ? Math.PI * 2 : Math.PI
  for (const ring of RINGS) {
    const rx = r * ring.scale
    const ry = rx * 0.28
    ctx.save()
    ctx.beginPath()
    ctx.ellipse(cx, cy, rx + ring.width, ry + ring.width * 0.28, 0, startAngle, endAngle)
    ctx.clip()
    ctx.strokeStyle = `rgba(${ring.r},${ring.g},${ring.b},${ring.opacity})`
    ctx.lineWidth = ring.width
    ctx.beginPath()
    ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2)
    ctx.stroke()
    ctx.restore()
  }
}

/** Thin violet atmospheric rim glow for Planet B. */
function drawAtmosphericRim(ctx, cx, cy, r) {
  ctx.save()
  ctx.strokeStyle = 'rgba(123,108,246,0.18)'
  ctx.lineWidth = 4
  ctx.shadowBlur = 8
  ctx.shadowColor = 'rgba(123,108,246,0.25)'
  ctx.beginPath()
  ctx.arc(cx, cy, r + 3, 0, Math.PI * 2)
  ctx.stroke()
  ctx.restore()
}

/** Two soft amber halos behind the sun disc. */
function drawSunHalo(ctx, cx, cy, r) {
  ctx.save()
  ctx.shadowBlur = 60
  ctx.shadowColor = '#f5c842'
  ctx.globalAlpha = 0.04
  ctx.fillStyle = '#f5c842'
  ctx.beginPath()
  ctx.arc(cx, cy, r + 80, 0, Math.PI * 2)
  ctx.fill()
  ctx.globalAlpha = 0.08
  ctx.beginPath()
  ctx.arc(cx, cy, r + 40, 0, Math.PI * 2)
  ctx.fill()
  ctx.restore()
}

/** 12 noise-driven corona rays with slowly rotating base angle. */
function drawCoronaRays(ctx, cx, cy, r, noise2D, time) {
  const baseRot = time * 0.000004
  ctx.save()
  for (let i = 0; i < 12; i++) {
    const angle = (i / 12) * Math.PI * 2 + baseRot
    const halfWidth = noise2D(i * 3.7, time * 0.00008) * 6 + 4
    const length    = noise2D(i * 2.1, time * 0.00006 + 10) * 55 + 70
    const cosA = Math.cos(angle), sinA = Math.sin(angle)
    const tipX = cx + cosA * (r + length)
    const tipY = cy + sinA * (r + length)
    const bx = cx + cosA * r
    const by = cy + sinA * r
    const b1x = bx + (-sinA) * halfWidth
    const b1y = by + cosA    * halfWidth
    const b2x = bx - (-sinA) * halfWidth
    const b2y = by - cosA    * halfWidth
    const grad = ctx.createLinearGradient(tipX, tipY, bx, by)
    grad.addColorStop(0, 'rgba(245,180,50,0)')
    grad.addColorStop(1, 'rgba(245,180,50,0.50)')
    ctx.fillStyle = grad
    ctx.shadowBlur = 18
    ctx.shadowColor = 'rgba(245,200,80,0.35)'
    ctx.beginPath()
    ctx.moveTo(tipX, tipY)
    ctx.lineTo(b1x, b1y)
    ctx.lineTo(b2x, b2y)
    ctx.closePath()
    ctx.fill()
  }
  ctx.restore()
}

/** Two slowly rotating solar prominence arcs with noise-driven opacity flicker. */
function drawProminences(ctx, cx, cy, r, noise2D, time) {
  const baseAngle = time * 0.000003
  const opacity   = noise2D(time * 0.0001, 0) * 0.25 + 0.30
  ctx.save()
  ctx.strokeStyle = `rgba(240,80,20,${opacity})`
  ctx.lineWidth = 3
  ctx.shadowBlur = 10
  ctx.shadowColor = 'rgba(240,80,20,0.4)'

  const a0s = baseAngle + 0.4, a0e = baseAngle + 1.1
  const p0mx = (Math.cos(a0s) + Math.cos(a0e)) / 2
  const p0my = (Math.sin(a0s) + Math.sin(a0e)) / 2
  ctx.beginPath()
  ctx.moveTo(cx + Math.cos(a0s) * r, cy + Math.sin(a0s) * r)
  ctx.quadraticCurveTo(
    cx + p0mx * (r + 40), cy + p0my * (r + 40),
    cx + Math.cos(a0e) * r, cy + Math.sin(a0e) * r,
  )
  ctx.stroke()

  const a1s = baseAngle + 3.6, a1e = baseAngle + 4.2
  const p1mx = (Math.cos(a1s) + Math.cos(a1e)) / 2
  const p1my = (Math.sin(a1s) + Math.sin(a1e)) / 2
  ctx.beginPath()
  ctx.moveTo(cx + Math.cos(a1s) * r, cy + Math.sin(a1s) * r)
  ctx.quadraticCurveTo(
    cx + p1mx * (r + 40), cy + p1my * (r + 40),
    cx + Math.cos(a1e) * r, cy + Math.sin(a1e) * r,
  )
  ctx.stroke()
  ctx.restore()
}

/**
 * Draws the sun disc pixel-by-pixel with limb darkening.
 * Colour interpolates white-hot centre → amber → deep orange edge by nz.
 */
function drawSunDisc(ctx, cx, cy, r, lightData) {
  const { map, size } = lightData
  const imgData = ctx.createImageData(size, size)
  const d = imgData.data
  const ox = Math.round(cx - r)
  const oy = Math.round(cy - r)
  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      const light = map[py * size + px]
      if (light < 0) continue
      const nx = (px - r + 0.5) / r
      const ny = (py - r + 0.5) / r
      const nzSq = 1 - nx * nx - ny * ny
      if (nzSq <= 0) continue
      const nz = Math.sqrt(nzSq)
      let cr, cg, cb
      if (nz < 0.7) {
        const f = nz / 0.7
        cr = 240 + (245 - 240) * f
        cg = 144 + (200 - 144) * f
        cb = 32  + (66  - 32)  * f
      } else {
        const f = (nz - 0.7) / 0.3
        cr = 245 + (255 - 245) * f
        cg = 200 + (252 - 200) * f
        cb = 66  + (224 - 66)  * f
      }
      const i = (py * size + px) * 4
      d[i]     = Math.min(255, Math.round(cr * light))
      d[i + 1] = Math.min(255, Math.round(cg * light))
      d[i + 2] = Math.min(255, Math.round(cb * light))
      d[i + 3] = 255
    }
  }
  ctx.putImageData(imgData, ox, oy)
}

// ─── Component ────────────────────────────────────────────────────────────────

export function SpaceBackground() {
  const { theme } = useTheme()
  const canvasRef    = useRef(null)
  const animRef      = useRef(null)
  const mouseRef     = useRef({ x: 0, y: 0 })
  const starsRef     = useRef(null)
  const startTimeRef = useRef(null)
  // Light maps — computed once per theme mount, expensive to recompute
  const lightARef    = useRef(null)
  const lightBRef    = useRef(null)
  const lightSunRef  = useRef(null)
  // Noise instances — each needs its own seed for different patterns
  const noiseARef    = useRef(null)
  const noiseBRef    = useRef(null)
  const noiseBFineRef = useRef(null)
  const noiseSunRef  = useRef(null)

  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas.getContext('2d')

    const resize = () => {
      canvas.width  = window.innerWidth
      canvas.height = window.innerHeight
    }
    resize()
    window.addEventListener('resize', resize)

    startTimeRef.current = performance.now()

    if (theme === 'dark') {
      lightARef.current   = computeLightMap(PLANET_A_R, 0.12, 0.35)
      lightBRef.current   = computeLightMap(PLANET_B_R, 0.18, 0.25)
      noiseARef.current   = createNoise2D()
      noiseBRef.current   = createNoise2D()
      noiseBFineRef.current = createNoise2D()

      starsRef.current = Array.from({ length: STAR_COUNT }, (_, i) => ({
        x:       Math.random() * canvas.width,
        y:       Math.random() * canvas.height,
        size:    STAR_SIZES[Math.floor(Math.random() * 3)],
        opacity: 0.4 + Math.random() * 0.6,
        drift:   i < STAR_DRIFTERS,
        vx:      (Math.random() - 0.5) * 0.04,
        vy:      (Math.random() - 0.5) * 0.04,
        layer:   Math.floor(Math.random() * 3),
      }))
    } else {
      // lx=0, ly=0, lz=1 → self-luminous sun, limb darkening only
      lightSunRef.current = computeLightMap(SUN_R, 0.20, 0.30, 0, 0, 1)
      noiseSunRef.current = createNoise2D()
    }

    const draw = () => {
      const w    = canvas.width
      const h    = canvas.height
      const time = performance.now() - startTimeRef.current
      ctx.clearRect(0, 0, w, h)

      if (theme === 'dark') {
        // Stars with mouse parallax
        const mx = (mouseRef.current.x / w - 0.5) * 10
        const my = (mouseRef.current.y / h - 0.5) * 10
        for (const star of starsRef.current) {
          if (star.drift) {
            star.x = (star.x + star.vx + w) % w
            star.y = (star.y + star.vy + h) % h
          }
          ctx.beginPath()
          ctx.arc(
            star.x + mx * STAR_PARALLAX[star.layer],
            star.y + my * STAR_PARALLAX[star.layer],
            star.size, 0, Math.PI * 2,
          )
          ctx.fillStyle = `rgba(255,255,255,${star.opacity})`
          ctx.fill()
        }

        // Planet B — distant, upper-right, no rings
        const bParallaxX = (mouseRef.current.x / w - 0.5) * -4.5
        const bParallaxY = (mouseRef.current.y / h - 0.5) * 4.5
        const bx = w + 40 + bParallaxX * 0.15
        const by = -40 + bParallaxY * 0.15
        drawDisc(
          ctx, bx, by, PLANET_B_R, lightBRef.current, PLANET_B_STOPS,
          noiseBRef.current, 0.022, 0.022, 0.000010, time,
          noiseBFineRef.current, 0.048, 0.048, 0.000006, 0.30,
        )
        drawAtmosphericRim(ctx, bx, by, PLANET_B_R)

        // Planet A — hero, lower-left, ringed (back rings → disc → front rings)
        const aParallaxX = (mouseRef.current.x / w - 0.5) * 6
        const aParallaxY = (mouseRef.current.y / h - 0.5) * 6
        const ax = -80 + aParallaxX
        const ay = h + 80 + aParallaxY
        drawRingHalf(ctx, ax, ay, PLANET_A_R, 'back')
        drawDisc(
          ctx, ax, ay, PLANET_A_R, lightARef.current, PLANET_A_STOPS,
          noiseARef.current, 0.007, 0.011, 0.000025, time,
          null, 0, 0, 0, 0,
        )
        drawRingHalf(ctx, ax, ay, PLANET_A_R, 'front')
      } else {
        // Light mode: sun, lower-right
        const sx = w + 60
        const sy = h + 60
        drawSunHalo(ctx, sx, sy, SUN_R)
        drawCoronaRays(ctx, sx, sy, SUN_R, noiseSunRef.current, time)
        drawProminences(ctx, sx, sy, SUN_R, noiseSunRef.current, time)
        drawSunDisc(ctx, sx, sy, SUN_R, lightSunRef.current)
      }

      animRef.current = requestAnimationFrame(draw)
    }

    draw()

    const onMouse = e => { mouseRef.current = { x: e.clientX, y: e.clientY } }
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

  return (
    <div style={outerStyle} data-testid="space-background">
      <canvas
        ref={canvasRef}
        data-testid="stars-canvas"
        style={{ position: 'absolute', top: 0, left: 0, width: '100%', height: '100%' }}
      />
      {theme === 'dark' && (
        <>
          <div data-testid="planet-hero"    style={{ position: 'absolute', width: 0, height: 0 }} />
          <div data-testid="planet-distant" style={{ position: 'absolute', width: 0, height: 0 }} />
        </>
      )}
      {theme === 'light' && (
        <div data-testid="sun" style={{ position: 'absolute', width: 0, height: 0 }} />
      )}
    </div>
  )
}
```

- [ ] **Step 2: Run tests — expect all pass**

```bash
cd emcip-admin-ui/src/main/frontend
npm test -- --run
```

Expected: all tests pass including the 4 SpaceBackground tests.

If `createImageData` throws in jsdom because canvas mock's `width`/`height` are 0: check that `HTMLCanvasElement.prototype.getContext` mock returns `createImageData: vi.fn((w, h) => ({ data: new Uint8ClampedArray(w * h * 4), width: w, height: h }))`. If canvas.width is 0 (jsdom default before resize), the light map size `r * 2` is fixed (not canvas-dependent), so the disc rendering won't blow up.

- [ ] **Step 3: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.jsx
git commit -m "feat(admin-ui): per-pixel canvas planets and sun with Lambertian shading and simplex-noise"
```

---

### Task 3: Clear CSS and final commit

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.module.css`

The keyframe animations (`rotateA`, `rotateB`, `sunPulse`) and their classes are now dead code — animation is driven by the canvas loop. The file must remain (the module import in SpaceBackground.jsx would break if deleted), but its content is cleared.

- [ ] **Step 1: Replace the CSS file content**

Replace the entire contents of `emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.module.css` with:

```css
/* Rendering handled entirely by canvas — no CSS animations needed */
```

- [ ] **Step 2: Run full test suite**

```bash
cd emcip-admin-ui/src/main/frontend
npm test -- --run
```

Expected: all tests pass (35 tests, 12 files).

- [ ] **Step 3: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/layout/SpaceBackground/SpaceBackground.module.css
git commit -m "chore(admin-ui): remove dead CSS animations from SpaceBackground"
```

- [ ] **Step 4: Push**

```bash
git push origin main
```
