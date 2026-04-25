# Theme Redesign — "The Lattice"

**Date:** 2026-04-25
**Inspiration:** Douglas Adams (HHGTTG) × Tad Williams (Otherland)
**Status:** Approved

---

## Concept

Adams provides the universe outside the window — deep space, cosmic scale, planets in view, gentle absurdity. Williams provides the room you're standing in — gold, midnight velvet, the feeling of stepping into a beautifully rendered simulation. "The Lattice" is named after the Otherland network.

The core design principle: **three depth planes**, each semi-transparent, so the animated background always breathes through the UI.

---

## Section 1 — Colour System

### Dark Mode

| Token | Value | Role |
|-------|-------|------|
| `--bg-primary` | `rgba(13, 11, 36, 0.82)` | Main content area — semi-transparent midnight indigo |
| `--bg-secondary` | `rgba(10, 8, 28, 0.75)` | Table headers, hover rows |
| `--bg-card` | `rgba(19, 17, 48, 0.88)` | Cards, modals — foreground plane |
| `--sidebar-bg` | `rgba(8, 8, 20, 0.90)` | Near-void, deepest layer |
| `--accent` | `#c9a84c` | Gold — buttons, active nav, focus rings |
| `--accent-hover` | `#f0c84a` | Gold brightened on hover |
| `--accent-secondary` | `#7b6cf6` | Cosmic blue-violet — hover states, secondary actions |
| `--text-primary` | `#e8e4d8` | Warm off-white (parchment tint) |
| `--text-secondary` | `#8a8090` | Muted violet-grey |
| `--text-muted` | `#504858` | Disabled, tertiary |
| `--accent-text` | `#0d0b24` | Text on gold buttons (dark, high contrast) |
| `--sidebar-text` | `#7a7090` | Nav items at rest |
| `--sidebar-active-bg` | `rgba(201, 168, 76, 0.08)` | Active nav item background |
| `--sidebar-active-text` | `#c9a84c` | Active nav item text (gold) |
| `--border` | `rgba(201, 168, 76, 0.15)` | Hairline gold border glow |
| `--shadow` | `rgba(201, 168, 76, 0.05)` | Elevation shadow tint |
| `--badge-green-bg` | `#0a2a14` | Status badge — connected/success |
| `--badge-green-text` | `#4ade80` | |
| `--badge-blue-bg` | `#0c1a3a` | Status badge — info |
| `--badge-blue-text` | `#60a5fa` | |
| `--badge-yellow-bg` | `#2a1e04` | Status badge — pending/warning |
| `--badge-yellow-text` | `#f0c84a` | |
| `--badge-red-bg` | `#2a0808` | Status badge — error/disconnected |
| `--badge-red-text` | `#f87171` | |
| `--badge-gray-bg` | `rgba(19, 17, 48, 0.88)` | Status badge — neutral |
| `--badge-gray-text` | `#8a8090` | |

### Light Mode

| Token | Value | Role |
|-------|-------|------|
| `--bg-primary` | `rgba(250, 247, 240, 0.88)` | Warm parchment, semi-transparent over sun |
| `--bg-secondary` | `rgba(240, 235, 220, 0.80)` | Table headers, hover rows |
| `--bg-card` | `rgba(255, 255, 255, 0.92)` | Cards — clean white, slightly lifted |
| `--sidebar-bg` | `rgba(8, 8, 20, 0.92)` | Stays dark in both modes — the airlock |
| `--accent` | `#a07828` | Darker gold (readable on light backgrounds) |
| `--accent-hover` | `#c9a84c` | Medium gold on hover |
| `--accent-secondary` | `#5b4fd4` | Deeper violet |
| `--text-primary` | `#1a1520` | Deep warm near-black |
| `--text-secondary` | `#5a5060` | Medium warm grey |
| `--text-muted` | `#9a9098` | Light muted grey |
| `--accent-text` | `#1a1520` | Text on gold buttons (dark, high contrast) |
| `--sidebar-text` | `#7a7090` | Nav items at rest (sidebar stays dark in light mode) |
| `--sidebar-active-bg` | `rgba(201, 168, 76, 0.08)` | Same as dark — sidebar is always dark |
| `--sidebar-active-text` | `#c9a84c` | Same as dark |
| `--border` | `rgba(160, 120, 40, 0.20)` | Warm gold border tint |
| `--shadow` | `rgba(100, 80, 20, 0.08)` | Warm elevation shadow |
| `--badge-green-bg` | `#dcfce7` | Status badge — connected/success |
| `--badge-green-text` | `#166534` | |
| `--badge-blue-bg` | `#dbeafe` | Status badge — info |
| `--badge-blue-text` | `#1e40af` | |
| `--badge-yellow-bg` | `#fef9c3` | Status badge — pending/warning |
| `--badge-yellow-text` | `#854d0e` | |
| `--badge-red-bg` | `#fee2e2` | Status badge — error/disconnected |
| `--badge-red-text` | `#991b1b` | |
| `--badge-gray-bg` | `#f1f5f9` | Status badge — neutral |
| `--badge-gray-text` | `#475569` | |

**Key rule:** Sidebar is dark (`rgba(8, 8, 20, 0.92)`) in both modes. It is the threshold — always space-side.

---

## Section 2 — Background Layer

### Component

`StarField.jsx` → renamed `SpaceBackground.jsx`. Handles all background layers in one component. `position: fixed`, `z-index: -1`, no pointer events.

### Dark Mode

**Stars (existing):** 150 stars, parallax drift on mouse, keep as-is.

**Planet A — Hero:**
- Position: lower-left corner, partially cropped by edge
- Size: 280–320px diameter
- Rotation: slow axial spin, ~120s per revolution, CSS `@keyframes rotate`
- Style: ringed planet, ring plane tilted ~20°, atmospheric gradient (deep teal → midnight purple)
- Glow: faint radial ambient glow behind it
- Parallax: moves at 0.3× star speed on mouse movement

**Planet B — Distant:**
- Position: upper-right corner, partially cropped
- Size: ~100px diameter
- Rotation: ~180s per revolution, different direction
- Style: no rings, muted blue-grey, far-away feel
- Parallax: moves at 0.15× star speed

Both planets are absolutely-positioned SVG elements — not drawn on the stars canvas, so they composite cleanly.

### Light Mode

Stars render `null` (keep existing behaviour).

**Sun disc:**
- Position: bottom-right corner, partially cropped
- Size: ~200px diameter SVG circle
- Layers: 3–4 concentric corona rings, decreasing opacity outward
- Animation: slow gentle pulse, ~8s `ease-in-out` keyframes
- Colours: warm amber `#f5c842` core → `rgba(245, 180, 50, 0)` corona fade
- No planets — you are on the surface, not in space

### AppShell CSS

```css
.main {
  backdrop-filter: blur(2px);
}
```

Sidebar gets `backdrop-filter: blur(8px)`. Modal overlay gets `backdrop-filter: blur(16px)` on the card itself.

---

## Section 3 — Sidebar & Logo

### Logo SVG (`Logo.jsx`)

Geometry unchanged. Stroke weights increased:
- Hexagon: `strokeWidth` `1.5` → `2.5`
- Circuit traces: `strokeWidth` `1` → `1.5`
- Eye iris: `strokeWidth` `1` → `1.5`
- Colour: `color: var(--accent)` in sidebar context (gold, not muted grey)

### Wordmark Font

Replace **Orbitron** with **Cinzel** (Google Fonts).

```html
<!-- index.html -->
<link href="https://fonts.googleapis.com/css2?family=Cinzel:wght@700;900&display=swap" rel="stylesheet">
```

```css
.emcip-wordmark {
  font-family: 'Cinzel', serif;
  font-weight: 900;
  letter-spacing: 0.2em;
  color: var(--accent);
}
```

Roman display capitals, cinematic weight, no retro feel.

### Sidebar Colours

- Background: `rgba(8, 8, 20, 0.90)` + `backdrop-filter: blur(8px)`
- Right border: `1px solid rgba(201, 168, 76, 0.12)` — defines the plane boundary
- Nav items at rest: `var(--text-secondary)`
- Nav items active: `var(--accent)` (gold), background `rgba(201, 168, 76, 0.08)`
- Nav items hover: `rgba(123, 108, 246, 0.10)` background — cosmic violet whisper
- Theme toggle icon: small SVG moon/sun replacing unicode ☽/☀, gold colour

---

## Section 4 — Main Content & Cards

### Depth Planes (summary)

| Plane | Background | Blur |
|-------|-----------|------|
| Sidebar | `rgba(8, 8, 20, 0.90)` | `blur(8px)` |
| Main area | `rgba(13, 11, 36, 0.82)` | `blur(2px)` |
| Cards/Tables | `rgba(19, 17, 48, 0.88)` | `blur(12px)` |
| Modals | `rgba(19, 17, 48, 0.95)` | `blur(16px)` |

### Page Headers

```css
h2 {
  font-family: 'Cinzel', serif;
  font-weight: 700;
  color: var(--accent);
}
```

Cinzel at lighter weight than the wordmark. Creates hierarchy: wordmark (900) → page title (700) → body text (system-ui).

### Tables

- Header row background: `rgba(201, 168, 76, 0.06)` — gold-tinted, distinct from card body
- Row hover: `rgba(123, 108, 246, 0.06)` — violet whisper
- Row borders: `rgba(201, 168, 76, 0.08)` — barely visible gold thread

### Buttons

- **Primary:** gold background `var(--accent)`, text `var(--accent-text)` (reversed — dark text on gold)
- **Primary hover:** `var(--accent-hover)` brightened gold
- **Secondary:** transparent, gold border, gold text
- **Danger:** red, unchanged

### Form Inputs

- Background: `rgba(8, 8, 20, 0.60)`
- Border at rest: `rgba(201, 168, 76, 0.20)`
- Border on focus: `var(--accent)`
- Focus ring: `box-shadow: 0 0 0 2px rgba(201, 168, 76, 0.30)`

### Modals

- Overlay: `rgba(8, 8, 20, 0.75)` — planets dimly visible behind
- Card: `rgba(19, 17, 48, 0.95)` + `backdrop-filter: blur(16px)` — foreground, stepped forward from space

---

## Files to Change

| File | Change |
|------|--------|
| `src/theme/variables.css` | Full palette replacement |
| `src/layout/StarField/StarField.jsx` | Rename → `SpaceBackground.jsx`, add planets + sun |
| `src/layout/AppShell/AppShell.jsx` | Update import |
| `src/layout/AppShell/AppShell.module.css` | Add `backdrop-filter: blur(2px)` to `.main` |
| `src/layout/Sidebar/Sidebar.module.css` | Border-right, backdrop-filter, nav hover colours |
| `src/logo/Logo.jsx` | Stroke weights, gold colour |
| `src/index.css` | Cinzel import reference, `.emcip-wordmark` update, `h2` style |
| `index.html` | Add Cinzel Google Fonts link |
| `src/components/Button/Button.module.css` | Gold primary button (reversed) |
| `src/components/Modal/Modal.module.css` | Backdrop blur, updated overlay/card colours |

---

## Out of Scope

- Telegram auth flow (separate spec)
- Multi-account Telegram architecture (separate spec)
- Page-level layout changes
- Animation performance tuning beyond requestAnimationFrame
