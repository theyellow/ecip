# Space Background — Realistic Planets & Sun

**Date:** 2026-04-25
**Status:** Approved

---

## Concept

Replace the current SVG circle-based planets and sun with procedurally rendered, per-pixel canvas drawings. Each body uses real Lambertian sphere shading (normal vector × light vector), `simplex-noise` for animated surface texture, and multi-band ring systems drawn with correct 3D occlusion. The result looks like a space game background, not a tinted circle.

All rendering is contained in `SpaceBackground.jsx`. No other component changes.

---

## Dependency

**`simplex-noise`** (npm) — ~5 KB gzip, zero transitive dependencies. Provides `createNoise2D()` which returns a function `noise(x, y) → [-1, 1]`.

---

## Architecture

Single `<canvas>` element fills the viewport (`position: fixed`, `z-index: -1`). One `requestAnimationFrame` loop handles everything:

**Dark mode draw order per frame:**
1. Clear canvas
2. Stars (existing parallax logic, unchanged)
3. Planet B — distant (upper-right)
4. Planet A — rings back-half (behind planet)
5. Planet A — disc
6. Planet A — rings front-half (in front of planet)

**Light mode draw order per frame:**
1. Clear canvas
2. Sun — outer corona halo
3. Sun — corona rays (12 triangles, noise-driven width/length)
4. Sun — prominences (2 arcs)
5. Sun — core disc

**Test IDs:** Invisible zero-size `<div>` elements with `data-testid="planet-hero"`, `data-testid="planet-distant"`, `data-testid="sun"` are kept in the JSX so existing test selectors compile. Visual rendering is canvas-only.

---

## Shared Lighting Model

Used identically by both planets. Pre-computed once per planet (not per frame) since the light source is fixed.

```
Light direction (normalised): lx = -0.55, ly = -0.45, lz = 0.70
```

For each pixel `(px, py)` inside disc of radius `r` centred at `(cx, cy)`:

```
nx = (px - cx) / r
ny = (py - cy) / r
nz_sq = 1 - nx*nx - ny*ny
if nz_sq < 0: skip (outside sphere)
nz = sqrt(nz_sq)

diffuse = max(0, nx*lx + ny*ly + nz*lz)
ambient = <per-planet constant>
limb    = nz * limbFactor + (1 - limbFactor)
light   = (diffuse + ambient) * limb   // clamp to [0, 1]
```

`light` is stored in a pre-computed `Float32Array` (same dimensions as the planet's offscreen canvas). Each frame, only the noise texture value is recomputed; it is multiplied by `light` to get the final pixel brightness.

---

## Planet A — Gas Giant

**Role:** Hero planet, lower-left corner, partially cropped.
**Size:** Logical diameter 340 px; disc radius `r = 132`.
**Centre:** `(-80, viewportHeight + 80)` — cropped by corner edge.
**Mouse parallax:** `0.30×` of mouse offset.
**Ambient:** `0.12`
**Limb factor:** `0.35` (dramatic terminator)

### Surface Texture

```
noise2D = createNoise2D()

// Per frame, for each pixel inside disc:
t = noise2D(px * 0.007, py * 0.011 + time * 0.000025)
// t in [-1, 1]

// 5-stop colour palette (t maps [-1..1] → index 0..4):
stops = [
  { t: -1.0, r:  13, g:  26,  b:  58 },  // midnight indigo
  { t: -0.4, r:  18, g:  60,  b:  80 },  // deep teal
  { t:  0.0, r:  26, g:  80,  b: 100 },  // blue-green
  { t:  0.5, r:  80, g:  60, b: 140 },   // violet
  { t:  1.0, r:  13, g:  13,  b:  46 },  // near-black indigo
]
```

Interpolate linearly between stops. Multiply each channel by `light[pixel]`. Write to `ImageData`.

### Ring System

Five ellipse arcs. Ring x-radius = planet x-radius × scale factor. Ring y-radius = ring x-radius × `0.28` (tilt ~20°). Centre same as planet.

| Band | x-radius scale | Stroke width | Colour | Opacity |
|------|---------------|-------------|--------|---------|
| Outer haze | 2.10 | 28 px | `#c9a84c` | 0.12 |
| Outer ring | 1.88 | 16 px | `#c9a84c` | 0.45 |
| Gap | 1.72 | 8 px | — | 0 (skip) |
| Main ring | 1.58 | 22 px | `#b09050` | 0.60 |
| Inner ring | 1.38 | 10 px | `#7b6cf6` | 0.30 |

**3D occlusion draw sequence:**
1. Draw all ring bands clipped to the *back* half of the ellipse (upper arc, `Math.PI` to `0`).
2. Draw planet disc (paste `ImageData` from offscreen canvas).
3. Draw all ring bands clipped to the *front* half (lower arc, `0` to `Math.PI`).

Canvas clip path per half: `ctx.beginPath(); ctx.ellipse(..., startAngle, endAngle); ctx.clip()`.

---

## Planet B — Rocky/Icy

**Role:** Distant world, upper-right corner, partially cropped.
**Size:** Logical diameter 120 px; disc radius `r = 48`.
**Centre:** `(viewportWidth + 40, -40)` — cropped by corner.
**Mouse parallax:** `0.15×` (barely moves — distant feel).
**Ambient:** `0.18`
**Limb factor:** `0.25` (softer terminator, diffuse icy surface)

### Surface Texture

Two noise passes mixed:

```
// Primary — large-scale surface variation:
t1 = noise2D(px * 0.022, py * 0.022 + time * 0.000010)

// Secondary — fine grain / cratering:
t2 = noise2D(px * 0.048, py * 0.048 + time * 0.000006)

t = t1 * 0.70 + t2 * 0.30
```

4-stop colour palette:
```
stops = [
  { t: -1.0, r:  15, g:  15,  b:  30 },  // near-black
  { t: -0.2, r:  35, g:  40,  b:  80 },  // deep slate-blue
  { t:  0.4, r:  70, g:  65, b: 110 },   // muted violet
  { t:  1.0, r: 180, g: 185, b: 200 },   // cold grey-white (polar)
]
```

### Atmospheric Rim

After pasting planet disc: draw one ellipse arc (full circle), `lineWidth = 4`, `strokeStyle = rgba(123, 108, 246, 0.18)`, `shadowBlur = 8`, `shadowColor = rgba(123, 108, 246, 0.25)`. Radius = `r + 3`.

---

## Sun (Light Mode)

**Role:** Star in lower-right corner, partially cropped.
**Core radius:** `r = 76 px`.
**Centre:** `(viewportWidth + 60, viewportHeight + 60)` — cropped corner.

### Core Disc

Per-pixel with Lambertian shading (same model, `ambient = 0.20`, `limbFactor = 0.30`). Light direction points toward viewer: `lx = 0.0, ly = 0.0, lz = 1.0` — sun is self-luminous, but limb darkening still applies.

Colour palette (by `nz`, i.e. proximity to centre of disc):
```
nz → 1.0:  #fffce0  (white-hot centre)
nz → 0.7:  #f5c842  (amber)
nz → 0.0:  #f09020  (deep orange edge)
```

### Corona Rays

12 rays, indexed `i = 0..11`. Per frame:

```
baseAngle   = (i / 12) * 2π + time * 0.000004   // slow rotation
halfWidth   = noise2D(i * 3.7, time * 0.00008) * 6 + 4   // px at base
length      = noise2D(i * 2.1, time * 0.00006 + 10) * 55 + 70  // px
```

Each ray is a filled triangle:
- Tip: `(cx + cos(baseAngle) * (r + length), cy + sin(baseAngle) * (r + length))`
- Base left/right: points at radius `r`, offset `±halfWidth` perpendicular to ray direction

Fill: `rgba(245, 180, 50, 0)` → `rgba(245, 180, 50, 0.50)` linear gradient along ray length (tip to base).
`shadowBlur = 18`, `shadowColor = rgba(245, 200, 80, 0.35)`.

### Outer Halo

Two `arc` fills (full circles) before rays:

| Radius | Fill colour | globalAlpha |
|--------|-------------|-------------|
| `r + 80` | `#f5c842` | 0.04 |
| `r + 40` | `#f5c842` | 0.08 |

### Solar Prominences

Two `quadraticCurveTo` arcs in `rgba(240, 80, 20, opacity)`:
- Prominence 0: start angle `baseAngle + 0.4`, end angle `baseAngle + 1.1`, control point 40px above arc midpoint
- Prominence 1: start angle `baseAngle + 3.6`, end angle `baseAngle + 4.2`, same construction
- `baseAngle` advances with `time * 0.000003` (very slow)
- Opacity: `noise2D(time * 0.0001, 0) * 0.25 + 0.30` — subtle flicker

`strokeStyle`, `lineWidth = 3`, no fill.

---

## CSS Changes

`SpaceBackground.module.css`: Remove `@keyframes rotateA`, `rotateB`, `sunPulse` and their associated classes (`.planetHeroSvg`, `.planetDistantSvg`, `.sunPulse`). The file becomes empty (or minimal) — keep it for the module import to remain valid.

---

## Files Changed

| File | Change |
|------|--------|
| `src/main/frontend/package.json` | Add `simplex-noise` |
| `src/main/frontend/src/layout/SpaceBackground/SpaceBackground.jsx` | Full rewrite |
| `src/main/frontend/src/layout/SpaceBackground/SpaceBackground.module.css` | Remove all keyframe/planet/sun classes |
| `src/main/frontend/src/layout/SpaceBackground/SpaceBackground.test.jsx` | Update tests for new canvas-only structure |

---

## Out of Scope

- PDF generation pom.xml fix (separate task)
- Any other page or component
- Performance profiling / optimisation beyond the pre-computed lighting map
