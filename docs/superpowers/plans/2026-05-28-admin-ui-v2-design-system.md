# Admin UI v2 Design System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the admin-ui token system with the v2 design tokens, restyle shared components, add a DataTable component, place project guidance, and redesign the Groups page as proof-of-concept.

**Architecture:** The v2 token system replaces the v1 `variables.css` with a comprehensive set of semantic tokens (colors, type scale, spacing, radii, atmospherics). Shared components (Button, Badge, Modal) are restyled to match v2 specs. A new DataTable shared component replaces per-page table boilerplate. V1 compatibility aliases ensure unredesigned pages continue working. The Groups page is redesigned as proof-of-concept.

**Tech Stack:** React 18, CSS Modules, CSS Custom Properties, Vite, Vitest

**Spec:** `docs/superpowers/specs/2026-05-28-admin-ui-v2-design-system-design.md`

**Codebase base path:** `emcip-admin-ui/src/main/frontend`

---

## File Structure

### New files
- `public/fonts/Cinzel-Variable.ttf` — copy from `documentation/fonts/`
- `public/fonts/SourceCodePro-Variable.ttf` — copy from `documentation/fonts/`
- `src/components/DataTable/DataTable.jsx` — shared table component
- `src/components/DataTable/DataTable.module.css` — DataTable styles
- `src/components/DataTable/DataTable.test.jsx` — DataTable tests
- `src/components/SectionLabel/SectionLabel.jsx` — em-dash section label
- `src/components/SectionLabel/SectionLabel.module.css` — SectionLabel styles
- `emcip-admin-ui/CLAUDE.md` — project guidance (module root, not inside `src/`)

### Modified files
- `src/theme/variables.css` — complete replacement with v2 tokens
- `src/index.css` — global typography rules
- `src/components/Button/Button.module.css` — v2 restyle
- `src/components/Badge/Badge.module.css` — v2 restyle, add violet variant
- `src/components/Badge/Badge.jsx` — add violet variant
- `src/components/Modal/Modal.module.css` — v2 restyle
- `src/pages/Groups/Groups.jsx` — rewrite using DataTable
- `src/pages/Groups/Groups.module.css` — replace with v2 styles (minimal — DataTable handles most)

### Deleted
- `emcip-admin-ui/design_handoff_emcip_admin/` — entire directory, after all work is done

---

### Task 1: Font files and v2 token system

**Files:**
- Copy: `documentation/fonts/Cinzel-Variable.ttf` → `emcip-admin-ui/src/main/frontend/public/fonts/Cinzel-Variable.ttf`
- Copy: `documentation/fonts/SourceCodePro-Variable.ttf` → `emcip-admin-ui/src/main/frontend/public/fonts/SourceCodePro-Variable.ttf`
- Rewrite: `emcip-admin-ui/src/main/frontend/src/theme/variables.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/index.css`

- [ ] **Step 1: Copy font files**

```bash
mkdir -p emcip-admin-ui/src/main/frontend/public/fonts
cp documentation/fonts/Cinzel-Variable.ttf emcip-admin-ui/src/main/frontend/public/fonts/
cp documentation/fonts/SourceCodePro-Variable.ttf emcip-admin-ui/src/main/frontend/public/fonts/
```

- [ ] **Step 2: Replace `variables.css` with v2 token system**

Rewrite `emcip-admin-ui/src/main/frontend/src/theme/variables.css` with the complete v2 token set. This file must contain:

1. `@font-face` declarations for Cinzel and Source Code Pro (paths relative to the built app: `/fonts/...`)
2. Base palette (gold ramp, teal ramp, void ramp, cream, signal colors) from the handoff's `colors_and_type.css`
3. Font family tokens (`--font-display`, `--font-body`, `--font-mono`)
4. Type scale (`--fs-xs` through `--fs-4xl`), line-height tokens (`--lh-tight/snug/normal/loose`), tracking tokens
5. Spacing scale (`--sp-0` through `--sp-9`)
6. Radii (`--r-xs` through `--r-pill`)
7. Elevation / shadow tokens
8. Dark theme semantic tokens (default — `:root, :root[data-theme="dark"]`) from `tokens.css` v2: `--bg-app`, `--bg-card`, `--fg-1/2/3`, `--accent`, `--border`, `--sidebar-*`, `--signal-*-bg/fg`, `--orb-*`, `--sky-*`, `--code-*`
9. Light theme semantic tokens (`:root[data-theme="light"]`) from `tokens.css` v2
10. V1 compatibility aliases at the bottom

```css
/* ─── EMCIP v2 Design Tokens ─────────────────────────────────────────── */

/* Webfonts */
@font-face {
  font-family: 'Cinzel';
  src: url('/fonts/Cinzel-Variable.ttf') format('truetype-variations');
  font-weight: 400 900;
  font-style: normal;
  font-display: swap;
}

@font-face {
  font-family: 'Source Code Pro';
  src: url('/fonts/SourceCodePro-Variable.ttf') format('truetype-variations');
  font-weight: 200 900;
  font-style: normal;
  font-display: swap;
}

/* ─── Base palette ──────────────────────────────────────────────────── */
:root {
  /* Gold */
  --c-gold-50:  #f7efd6;
  --c-gold-100: #ecdca0;
  --c-gold-300: #e0c47a;
  --c-gold-500: #d4a849;
  --c-gold-600: #b8902f;
  --c-gold-700: #8a6a1c;
  --c-gold-800: #7a5a1e;
  --c-gold-900: #4a3611;
  --c-gold-bright: #f5cc66;
  --c-gold-warm: #e8a648;

  /* Teal */
  --c-teal-100: #b8d4d8;
  --c-teal-300: #6a9ab0;
  --c-teal-500: #3a6878;
  --c-teal-700: #1e4050;
  --c-teal-900: #0a2030;

  /* Void (cool-black depths) */
  --c-void-950: #030710;
  --c-void-900: #050a14;
  --c-void-800: #08121e;
  --c-void-700: #0c1a28;
  --c-void-600: #122438;
  --c-void-500: #1a3048;
  --c-void-400: #2a4258;

  /* Cream */
  --c-cream-100: #faf6ec;
  --c-cream-200: #f0e6d0;
  --c-cream-300: #e2d4b0;

  /* Signal */
  --c-sig-ok:   #4ade80;
  --c-sig-info:  #60a5fa;
  --c-sig-warn:  #f0c84a;
  --c-sig-stop:  #f87171;

  /* Atmosphere */
  --c-mist: #2a4258;
  --c-fog:  rgba(58, 104, 120, 0.10);
  --c-haze: rgba(212, 168, 73, 0.04);
}

/* ─── Type families ─────────────────────────────────────────────────── */
:root {
  --font-display: 'Cinzel', 'Trajan Pro', 'Cormorant Garamond', Georgia, serif;
  --font-body:    'Inter', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
  --font-mono:    'Source Code Pro', 'IBM Plex Mono', 'SF Mono', Menlo, monospace;

  --tracking-display: 0.20em;
  --tracking-label:   0.05em;
  --tracking-caps:    0.10em;
}

/* ─── Type scale ────────────────────────────────────────────────────── */
:root {
  --fs-xs:   12px;
  --fs-sm:   14px;
  --fs-base: 16px;
  --fs-md:   18px;
  --fs-lg:   22px;
  --fs-xl:   28px;
  --fs-2xl:  36px;
  --fs-3xl:  48px;
  --fs-4xl:  72px;

  --lh-tight:  1.15;
  --lh-snug:   1.35;
  --lh-normal: 1.55;
  --lh-loose:  1.75;
}

/* ─── Spacing (4px grid) ────────────────────────────────────────────── */
:root {
  --sp-0: 0;
  --sp-1: 4px;
  --sp-2: 8px;
  --sp-3: 12px;
  --sp-4: 16px;
  --sp-5: 24px;
  --sp-6: 32px;
  --sp-7: 48px;
  --sp-8: 64px;
  --sp-9: 96px;
}

/* ─── Radii ─────────────────────────────────────────────────────────── */
:root {
  --r-xs:   3px;
  --r-sm:   6px;
  --r-md:   8px;
  --r-lg:   12px;
  --r-pill: 9999px;
}

/* ─── Elevation ─────────────────────────────────────────────────────── */
:root {
  --shadow-1: 0 1px 2px rgba(100, 80, 20, 0.08);
  --shadow-2: 0 4px 12px rgba(100, 80, 20, 0.10);
  --shadow-3: 0 8px 32px rgba(100, 80, 20, 0.14);
  --glow-gold: 0 0 0 1px rgba(201, 168, 76, 0.30), 0 0 24px rgba(201, 168, 76, 0.25);
}

/* ─── Otherland v2 (dark, the default) ──────────────────────────────── */
:root,
:root[data-theme="dark"] {
  --bg-app:        var(--c-void-900);
  --bg-app-soft:   var(--c-void-800);
  --bg-card:       rgba(12, 26, 40, 0.78);
  --bg-card-solid: var(--c-void-700);
  --bg-sunken:     rgba(5, 10, 20, 0.65);
  --bg-input:      rgba(5, 10, 20, 0.55);

  --fg-1:          #ede4ce;
  --fg-2:          #a09484;
  --fg-3:          #5a5448;
  --fg-on-accent:  var(--c-void-900);

  --accent:        var(--c-gold-500);
  --accent-hover:  var(--c-gold-bright);
  --accent-soft:   rgba(212, 168, 73, 0.10);
  --accent-2:      var(--c-teal-500);

  --border:        rgba(212, 168, 73, 0.18);
  --border-strong: rgba(212, 168, 73, 0.45);
  --rule:          rgba(212, 168, 73, 0.08);

  --sidebar-bg:    rgba(3, 7, 16, 0.92);
  --sidebar-fg:    #c8c0b0;
  --sidebar-fg-muted: #807868;
  --sidebar-fg-active: var(--c-gold-bright);
  --sidebar-bg-active: rgba(212, 168, 73, 0.14);
  --sidebar-bg-hover:  rgba(212, 168, 73, 0.06);

  --shadow-card:   0 4px 24px rgba(0, 0, 0, 0.55), 0 0 0 1px rgba(212,168,73,0.08);
  --shadow-modal:  0 22px 64px rgba(0, 0, 0, 0.75), 0 0 0 1px rgba(212,168,73,0.18);

  --orb-core:      var(--c-gold-bright);
  --orb-mid:       var(--c-gold-warm);
  --orb-glow:      rgba(245, 204, 102, 0.35);

  --sky-grad-1:    var(--c-void-950);
  --sky-grad-2:    var(--c-void-900);
  --sky-grad-3:    var(--c-void-700);
  --sky-bloom-a:   rgba(245, 204, 102, 0.20);
  --sky-bloom-b:   rgba(58, 104, 120, 0.18);
  --mono-fill:     rgb(3, 7, 16);
  --mono-grad-1:   rgba(8, 18, 30, 0.75);
  --mono-grad-2:   rgba(3, 7, 16, 1);
  --fog-tint:      rgba(58, 104, 120, 0.10);
  --fog-tint-deep: rgba(58, 104, 120, 0.25);
  --orb-ring:      rgba(245, 204, 102, 1);
  --orb-ring-soft: rgba(212, 168, 73, 1);
  --orb-ring-opacity:      0.55;
  --orb-ring-soft-opacity: 0.40;

  --signal-ok-bg:    #0a2a14;  --signal-ok-fg:    #4ade80;
  --signal-info-bg:  #0c1a3a;  --signal-info-fg:  #60a5fa;
  --signal-warn-bg:  #2a1e04;  --signal-warn-fg:  #f0c84a;
  --signal-stop-bg:  #2a0808;  --signal-stop-fg:  #f87171;
  --signal-mute-bg:  rgba(12, 26, 40, 0.78);  --signal-mute-fg:  #a09484;

  --code-bg:       rgba(5, 10, 20, 0.65);
  --code-fg:       var(--c-gold-300);
  --code-block-bg: var(--c-void-950);
  --code-block-fg: #b8d4d8;
}

/* ─── Parchment v2 (light) ──────────────────────────────────────────── */
:root[data-theme="light"] {
  --bg-app:        #eef0ec;
  --bg-app-soft:   #e2e8e4;
  --bg-card:       rgba(255, 255, 255, 0.78);
  --bg-card-solid: #ffffff;
  --bg-sunken:     #d8e0dc;
  --bg-input:      rgba(255, 255, 255, 0.85);

  --fg-1:          #1a2028;
  --fg-2:          #5a6470;
  --fg-3:          #95a0aa;
  --fg-on-accent:  var(--c-void-900);

  --accent:        var(--c-gold-700);
  --accent-hover:  var(--c-gold-500);
  --accent-soft:   rgba(212, 168, 73, 0.12);
  --accent-2:      var(--c-teal-700);

  --border:        rgba(138, 106, 28, 0.22);
  --border-strong: rgba(138, 106, 28, 0.45);
  --rule:          rgba(138, 106, 28, 0.10);

  --sidebar-bg:    rgba(3, 7, 16, 0.94);
  --sidebar-fg:    #c8c0b0;
  --sidebar-fg-muted: #807868;
  --sidebar-fg-active: var(--c-gold-bright);
  --sidebar-bg-active: rgba(212, 168, 73, 0.14);
  --sidebar-bg-hover:  rgba(212, 168, 73, 0.06);

  --shadow-card:   0 4px 18px rgba(40, 60, 70, 0.10), 0 0 0 1px rgba(138,106,28,0.06);
  --shadow-modal:  0 22px 64px rgba(40, 60, 70, 0.18), 0 0 0 1px rgba(138,106,28,0.12);

  --orb-core:      #f8d27a;
  --orb-mid:       #e8a648;
  --orb-glow:      rgba(245, 204, 102, 0.55);

  --sky-grad-1:    #d8dfdb;
  --sky-grad-2:    #e8eef0;
  --sky-grad-3:    #f5f0e0;
  --sky-bloom-a:   rgba(245, 204, 102, 0.42);
  --sky-bloom-b:   rgba(168, 196, 204, 0.30);
  --mono-fill:     rgba(180, 198, 200, 0.55);
  --mono-grad-1:   rgba(210, 224, 228, 0.65);
  --mono-grad-2:   rgba(196, 214, 220, 0.90);
  --fog-tint:      rgba(255, 255, 255, 0.30);
  --fog-tint-deep: rgba(255, 255, 255, 0.55);
  --orb-ring:      rgba(184, 144, 47, 1);
  --orb-ring-soft: rgba(168, 144, 80, 1);
  --orb-ring-opacity:      0.45;
  --orb-ring-soft-opacity: 0.35;

  --signal-ok-bg:    #dcfce7;  --signal-ok-fg:    #166534;
  --signal-info-bg:  #dbeafe;  --signal-info-fg:  #1e40af;
  --signal-warn-bg:  #fef9c3;  --signal-warn-fg:  #854d0e;
  --signal-stop-bg:  #fee2e2;  --signal-stop-fg:  #991b1b;
  --signal-mute-bg:  #f1f5f9;  --signal-mute-fg:  #475569;
}

/* ─── v1 compat aliases (remove when all pages are redesigned) ───── */
:root {
  --bg-primary: var(--bg-app);
  --bg-secondary: var(--bg-app-soft);
  --text-primary: var(--fg-1);
  --text-secondary: var(--fg-2);
  --text-muted: var(--fg-3);
  --accent-text: var(--fg-on-accent);
  --accent-secondary: var(--accent-2);
  --shadow: var(--shadow-card);
  --sidebar-text: var(--sidebar-fg);
  --sidebar-active-bg: var(--sidebar-bg-active);
  --sidebar-active-text: var(--sidebar-fg-active);
  --badge-green-bg: var(--signal-ok-bg);
  --badge-green-text: var(--signal-ok-fg);
  --badge-blue-bg: var(--signal-info-bg);
  --badge-blue-text: var(--signal-info-fg);
  --badge-yellow-bg: var(--signal-warn-bg);
  --badge-yellow-text: var(--signal-warn-fg);
  --badge-red-bg: var(--signal-stop-bg);
  --badge-red-text: var(--signal-stop-fg);
  --badge-gray-bg: var(--signal-mute-bg);
  --badge-gray-text: var(--signal-mute-fg);
  --table-header-bg: var(--rule);
  --table-row-hover: var(--accent-soft);
  --input-bg: var(--bg-input);
}
```

- [ ] **Step 3: Update `index.css` with global typography**

Replace the contents of `emcip-admin-ui/src/main/frontend/src/index.css` with:

```css
@import './theme/variables.css';

*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

html, body, #root {
  height: 100%;
  font-family: var(--font-body);
  font-size: var(--fs-base);
  line-height: var(--lh-normal);
  color: var(--fg-1);
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
  transition: color 0.2s;
}

h1, .h1 {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: var(--fs-3xl);
  line-height: var(--lh-tight);
  letter-spacing: var(--tracking-display);
  color: var(--accent);
  text-transform: uppercase;
}

h2, .h2 {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: var(--fs-2xl);
  line-height: var(--lh-snug);
  letter-spacing: var(--tracking-display);
  color: var(--accent);
  text-transform: uppercase;
}

h3, .h3 {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: var(--fs-xl);
  line-height: var(--lh-snug);
  letter-spacing: var(--tracking-caps);
  color: var(--accent);
  text-transform: uppercase;
}

h4, .h4 {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: var(--fs-lg);
  letter-spacing: var(--tracking-caps);
  color: var(--fg-1);
  text-transform: uppercase;
}

.emcip-wordmark {
  font-family: var(--font-display);
  font-weight: 900;
  letter-spacing: var(--tracking-display);
  color: var(--accent);
  text-transform: uppercase;
}

a { color: var(--accent); text-decoration: none; border-bottom: 1px solid transparent; transition: border-color 0.15s, color 0.15s; }
a:hover { color: var(--accent-hover); border-bottom-color: var(--accent-hover); }

:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

::selection { background: var(--c-teal-500); color: var(--c-cream-100); }
```

- [ ] **Step 4: Run all existing tests to confirm nothing broke**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run 2>&1 | tail -20
```

Expected: all existing tests pass. Token rename uses v1 compat aliases, so no CSS-level breakage. If any tests reference specific CSS class names or inline styles that depend on the old variable names, they should still resolve via the compat aliases.

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/public/fonts/ emcip-admin-ui/src/main/frontend/src/theme/variables.css emcip-admin-ui/src/main/frontend/src/index.css
git commit -m "feat(admin-ui): v2 design token system + fonts

Replace variables.css with comprehensive v2 tokens (palette, type scale,
spacing, radii, semantic themes). Add Cinzel and Source Code Pro variable
fonts. Update global typography in index.css. V1 compat aliases preserve
existing pages."
```

---

### Task 2: Restyle Button component

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/components/Button/Button.module.css`

- [ ] **Step 1: Replace Button.module.css with v2 styles**

```css
.btn {
  padding: 9px 16px;
  border-radius: 0;
  border: 1px solid transparent;
  cursor: pointer;
  font-family: var(--font-display);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  transition: background 0.15s, color 0.15s, border-color 0.15s, filter 0.15s;
}

.btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.primary {
  background: linear-gradient(180deg, var(--orb-core) 0%, var(--accent) 100%);
  color: var(--fg-on-accent);
  border-color: var(--border-strong);
  box-shadow: 0 0 0 1px rgba(212, 168, 73, 0.20);
}

.primary:hover:not(:disabled) {
  filter: brightness(1.08);
  box-shadow: 0 0 12px var(--orb-glow);
}

.secondary {
  background: transparent;
  color: var(--fg-2);
  border-color: var(--border);
}

.secondary:hover:not(:disabled) {
  background: var(--accent-soft);
  color: var(--fg-1);
  border-color: var(--accent);
}

.danger {
  background: transparent;
  color: var(--signal-stop-fg);
  border-color: rgba(248, 113, 113, 0.30);
}

.danger:hover:not(:disabled) {
  background: rgba(248, 113, 113, 0.10);
  border-color: rgba(248, 113, 113, 0.60);
}
```

- [ ] **Step 2: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run 2>&1 | tail -20
```

Expected: all tests pass. No test asserts on Button CSS values.

- [ ] **Step 3: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/components/Button/Button.module.css
git commit -m "style(admin-ui): restyle Button to v2 design system

Zero radius, gradient primary, Cinzel uppercase, no scale on press.
Danger uses signal-stop tokens."
```

---

### Task 3: Restyle Badge component + add violet variant

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/components/Badge/Badge.module.css`
- Modify: `emcip-admin-ui/src/main/frontend/src/components/Badge/Badge.jsx`

- [ ] **Step 1: Replace Badge.module.css with v2 styles**

```css
.badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 0;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  font-family: var(--font-mono);
  text-transform: uppercase;
  border: 1px solid;
}

.green {
  background: rgba(74, 222, 128, 0.10);
  color: var(--signal-ok-fg);
  border-color: rgba(74, 222, 128, 0.30);
}

.blue {
  background: rgba(96, 165, 250, 0.10);
  color: var(--signal-info-fg);
  border-color: rgba(96, 165, 250, 0.30);
}

.yellow {
  background: rgba(240, 200, 74, 0.10);
  color: var(--signal-warn-fg);
  border-color: rgba(240, 200, 74, 0.40);
}

.red {
  background: rgba(248, 113, 113, 0.10);
  color: var(--signal-stop-fg);
  border-color: rgba(248, 113, 113, 0.30);
}

.gray {
  background: rgba(168, 144, 80, 0.06);
  color: var(--fg-2);
  border-color: var(--rule);
}

.violet {
  background: rgba(58, 104, 120, 0.10);
  color: var(--c-teal-300);
  border-color: rgba(58, 104, 120, 0.30);
}
```

- [ ] **Step 2: Update Badge.jsx — no code changes needed**

The JSX already maps `variant` to `styles[variant]`. The `violet` class added in CSS will work when `<Badge variant="violet">` is used. No JSX change required — the existing spread `styles[variant]` handles it.

Verify: read `emcip-admin-ui/src/main/frontend/src/components/Badge/Badge.jsx` to confirm it uses `styles[variant]`:

```jsx
export function Badge({ variant = 'gray', children }) {
  return <span className={`${styles.badge} ${styles[variant]}`}>{children}</span>
}
```

This already works for `violet` — `styles['violet']` will resolve to the new `.violet` class. No change needed.

- [ ] **Step 3: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run 2>&1 | tail -20
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/components/Badge/Badge.module.css
git commit -m "style(admin-ui): restyle Badge to v2 design system

Zero radius with border, mono font, add violet variant for teal accent."
```

---

### Task 4: Restyle Modal component

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/components/Modal/Modal.module.css`

- [ ] **Step 1: Replace Modal.module.css with v2 styles**

```css
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(3, 7, 16, 0.72);
  backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.card {
  background: var(--bg-card-solid);
  border: 1px solid var(--border-strong);
  border-radius: 0;
  width: 520px;
  max-width: 95vw;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-modal);
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
  background: rgba(212, 168, 73, 0.04);
}

.header h3 {
  font-family: var(--font-display);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--accent);
  margin: 0;
}

.close {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--fg-2);
  font-size: 14px;
  padding: 4px;
}

.close:hover {
  color: var(--accent);
}

.body {
  padding: 18px;
  overflow-y: auto;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.footer {
  padding: 12px 18px;
  border-top: 1px solid var(--border);
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
```

- [ ] **Step 2: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run 2>&1 | tail -20
```

Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/components/Modal/Modal.module.css
git commit -m "style(admin-ui): restyle Modal to v2 design system

Zero radius, solid card background, stronger border, gold header with
Cinzel uppercase title, deeper overlay blur."
```

---

### Task 5: Create DataTable shared component

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.module.css`
- Create: `emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.test.jsx`

- [ ] **Step 1: Write DataTable tests**

Create `emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.test.jsx`:

```jsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DataTable } from './DataTable'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'code', label: 'Code', mono: true },
]

const ROWS = [
  { id: '1', name: 'Alpha', code: 'A1' },
  { id: '2', name: 'Beta', code: 'B2' },
]

describe('DataTable', () => {
  it('renders title and system ID', () => {
    render(<DataTable title="Items" systemId="test · 2 items" columns={COLUMNS} rows={ROWS} />)
    expect(screen.getByText('Items')).toBeInTheDocument()
    expect(screen.getByText('test · 2 items')).toBeInTheDocument()
  })

  it('renders column headers and row data', () => {
    render(<DataTable title="Items" columns={COLUMNS} rows={ROWS} />)
    expect(screen.getByText('Name')).toBeInTheDocument()
    expect(screen.getByText('Code')).toBeInTheDocument()
    expect(screen.getByText('Alpha')).toBeInTheDocument()
    expect(screen.getByText('B2')).toBeInTheDocument()
  })

  it('shows empty state when no rows', () => {
    render(<DataTable title="Items" columns={COLUMNS} rows={[]} emptyText="Nothing here" />)
    expect(screen.getByText('Nothing here')).toBeInTheDocument()
  })

  it('calls onEdit when row is clicked', async () => {
    const onEdit = vi.fn()
    render(<DataTable title="Items" columns={COLUMNS} rows={ROWS} onEdit={onEdit} />)
    await userEvent.click(screen.getByText('Alpha'))
    expect(onEdit).toHaveBeenCalledWith(ROWS[0])
  })

  it('calls onDelete when delete button is clicked', async () => {
    const onDelete = vi.fn()
    render(<DataTable title="Items" columns={COLUMNS} rows={ROWS} onDelete={onDelete} />)
    const deleteButtons = screen.getAllByText('Delete')
    await userEvent.click(deleteButtons[0])
    expect(onDelete).toHaveBeenCalledWith(ROWS[0])
  })

  it('renders add button when addLabel and onAdd provided', async () => {
    const onAdd = vi.fn()
    render(<DataTable title="Items" columns={COLUMNS} rows={ROWS} addLabel="+ Add" onAdd={onAdd} />)
    await userEvent.click(screen.getByText('+ Add'))
    expect(onAdd).toHaveBeenCalled()
  })

  it('renders custom cell via column render function', () => {
    const columns = [
      { key: 'name', label: 'Name', render: (val) => `[${val}]` },
    ]
    render(<DataTable title="Items" columns={columns} rows={[{ id: '1', name: 'Test' }]} />)
    expect(screen.getByText('[Test]')).toBeInTheDocument()
  })

  it('renders filter dropdowns', async () => {
    const onChange = vi.fn()
    const filters = [{
      value: '',
      onChange,
      options: [{ value: '', label: 'All' }, { value: 'A', label: 'Type A' }],
    }]
    render(<DataTable title="Items" columns={COLUMNS} rows={ROWS} filters={filters} />)
    expect(screen.getByText('All')).toBeInTheDocument()
    expect(screen.getByText('Type A')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/components/DataTable/DataTable.test.jsx 2>&1 | tail -10
```

Expected: FAIL — module `./DataTable` not found.

- [ ] **Step 3: Create DataTable.module.css**

Create `emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.module.css`:

```css
.pageHeader {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: var(--sp-5);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--rule);
}

.pageHeader h2 {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: 26px;
  line-height: 1;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--accent);
  margin: 0;
}

.systemId {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.10em;
  color: var(--fg-3);
  margin-top: 6px;
  text-transform: uppercase;
}

.controls {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}

.filter {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
}

.filter:focus {
  border-color: var(--accent);
  outline: none;
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

.table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-card);
  backdrop-filter: blur(14px);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-card);
}

.table th {
  padding: 11px 14px;
  text-align: left;
  font-family: var(--font-body);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fg-3);
  background: rgba(212, 168, 73, 0.04);
  border-bottom: 1px solid var(--rule);
}

.table td {
  padding: 12px 14px;
  font-size: 13px;
  border-bottom: 1px solid var(--rule);
  color: var(--fg-1);
  vertical-align: middle;
}

.table tr:last-child td {
  border-bottom: none;
}

.table tr:hover td {
  background: rgba(212, 168, 73, 0.04);
}

.clickable {
  cursor: pointer;
}

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
}

.empty {
  text-align: center;
  color: var(--fg-3);
  padding: var(--sp-7);
  font-style: italic;
  font-family: var(--font-display);
  letter-spacing: 0.10em;
  text-transform: uppercase;
  font-size: 12px;
}
```

- [ ] **Step 4: Create DataTable.jsx**

Create `emcip-admin-ui/src/main/frontend/src/components/DataTable/DataTable.jsx`:

```jsx
import { Button } from '../Button/Button'
import styles from './DataTable.module.css'

export function DataTable({
  title,
  systemId,
  addLabel,
  onAdd,
  columns,
  rows,
  rowKey = r => r.id,
  onEdit,
  onDelete,
  filters,
  emptyText = 'No records',
}) {
  return (
    <div>
      <div className={styles.pageHeader}>
        <div>
          <h2>{title}</h2>
          {systemId && <div className={styles.systemId}>{systemId}</div>}
        </div>
        <div className={styles.controls}>
          {filters?.map((f, i) => (
            <select key={i} className={styles.filter} value={f.value} onChange={f.onChange}>
              {f.options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          ))}
          {addLabel && onAdd && <Button onClick={onAdd}>{addLabel}</Button>}
        </div>
      </div>

      <table className={styles.table}>
        <thead>
          <tr>
            {columns.map(c => (
              <th key={c.key} style={c.width ? { width: c.width } : undefined}>{c.label}</th>
            ))}
            {onDelete && <th style={{ width: 80 }}></th>}
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr
              key={rowKey(row)}
              className={onEdit ? styles.clickable : undefined}
              onClick={onEdit ? () => onEdit(row) : undefined}
            >
              {columns.map(c => (
                <td key={c.key} className={c.mono ? styles.mono : undefined}>
                  {c.render ? c.render(row[c.key], row) : (row[c.key] ?? '\u2014')}
                </td>
              ))}
              {onDelete && (
                <td className={styles.actions} onClick={e => e.stopPropagation()}>
                  <Button variant="danger" onClick={() => onDelete(row)}>Delete</Button>
                </td>
              )}
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={columns.length + (onDelete ? 1 : 0)} className={styles.empty}>
                {emptyText}
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/components/DataTable/DataTable.test.jsx 2>&1 | tail -10
```

Expected: 8 tests pass.

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/components/DataTable/
git commit -m "feat(admin-ui): add DataTable shared component

Workhorse table with page header, system-id, filters, column config,
row click, delete, and empty state. V2 design tokens throughout."
```

---

### Task 6: Create SectionLabel component

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/components/SectionLabel/SectionLabel.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/components/SectionLabel/SectionLabel.module.css`

- [ ] **Step 1: Create SectionLabel.module.css**

Create `emcip-admin-ui/src/main/frontend/src/components/SectionLabel/SectionLabel.module.css`:

```css
.label {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-family: var(--font-display);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--accent);
  margin: var(--sp-3) 0 6px;
}

.aside {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.04em;
  text-transform: none;
  color: var(--fg-3);
  font-weight: 400;
}
```

- [ ] **Step 2: Create SectionLabel.jsx**

Create `emcip-admin-ui/src/main/frontend/src/components/SectionLabel/SectionLabel.jsx`:

```jsx
import styles from './SectionLabel.module.css'

export function SectionLabel({ children, aside }) {
  return (
    <div className={styles.label}>
      <span>&mdash; {children} &mdash;</span>
      {aside && <span className={styles.aside}>{aside}</span>}
    </div>
  )
}
```

- [ ] **Step 3: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/components/SectionLabel/
git commit -m "feat(admin-ui): add SectionLabel component

Em-dash-wrapped uppercase gold label with optional mono aside.
Used in modals and detail panels."
```

---

### Task 7: Redesign Groups page

**Files:**
- Rewrite: `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx`
- Rewrite: `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.module.css`

- [ ] **Step 1: Replace Groups.module.css with minimal v2 styles**

The DataTable handles most styling. The Groups page only needs styles for the edit modal's form fields and metadata grid.

Replace `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.module.css`:

```css
.metaGrid {
  display: grid;
  grid-template-columns: 130px 1fr;
  gap: 8px 16px;
  align-items: baseline;
  margin-bottom: var(--sp-4);
}

.metaLabel {
  font-family: var(--font-body);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: var(--fg-3);
}

.metaValue {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
  word-break: break-all;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: var(--sp-2);
}

.field label {
  font-family: var(--font-body);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fg-2);
}

.input {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  width: 100%;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

.checkboxRow {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-body);
  font-size: 12px;
  color: var(--fg-1);
  letter-spacing: 0.03em;
}

.checkboxRow input {
  width: auto;
}
```

- [ ] **Step 2: Rewrite Groups.jsx using DataTable**

Replace `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { groupsApi } from '../../api/groups'
import { tenantsApi } from '../../api/tenants'
import { Badge } from '../../components/Badge/Badge'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import styles from './Groups.module.css'

const LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'STRICT']
const LEVEL_VARIANT = { LOW: 'green', MEDIUM: 'blue', HIGH: 'yellow', STRICT: 'red' }

const COLUMNS = [
  { key: 'name', label: 'Group' },
  { key: 'telegramChatId', label: 'Chat ID', mono: true, width: 180 },
  { key: 'moderationLevel', label: 'Mod', width: 100, render: v => <Badge variant={LEVEL_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'autoRespond', label: 'Auto-respond', width: 120, render: v => <Badge variant={v ? 'green' : 'gray'}>{v ? 'YES' : 'NO'}</Badge> },
  { key: 'description', label: 'Description', render: v => v || '\u2014' },
]

function GroupEditModal({ group, onClose, onSave, tenants }) {
  const isNew = !group
  const [form, setForm] = useState({
    telegramChatId: group?.telegramChatId ?? '',
    name: group?.name ?? '',
    description: group?.description ?? '',
    moderationLevel: group?.moderationLevel ?? 'LOW',
    autoRespond: group?.autoRespond ?? false,
    welcomeMessage: group?.welcomeMessage ?? '',
    tenantId: group?.tenantId ?? '',
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={isNew ? 'Add Group' : `Edit \u00b7 ${group.name}`} onClose={onClose} onSubmit={() => onSave(form)}>
      {!isNew && (
        <>
          <SectionLabel>Details</SectionLabel>
          <div className={styles.metaGrid}>
            <span className={styles.metaLabel}>Chat ID</span>
            <span className={styles.metaValue}>{group.telegramChatId}</span>
            <span className={styles.metaLabel}>Auto-respond</span>
            <span className={styles.metaValue}>{group.autoRespond ? 'Yes' : 'No'}</span>
            {group.tenantId && <>
              <span className={styles.metaLabel}>Tenant</span>
              <span className={styles.metaValue}>{group.tenantId}</span>
            </>}
          </div>
        </>
      )}

      {isNew && (
        <div className={styles.field}>
          <label>Telegram Chat ID</label>
          <input type="number" className={styles.input} value={form.telegramChatId}
            onChange={e => set('telegramChatId', parseInt(e.target.value, 10))} required />
        </div>
      )}

      <div className={styles.field}>
        <label>Name</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required />
      </div>

      <div className={styles.field}>
        <label>Description</label>
        <input type="text" className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} />
      </div>

      <div className={styles.field}>
        <label>Moderation Level</label>
        <select className={styles.input} value={form.moderationLevel}
          onChange={e => set('moderationLevel', e.target.value)}>
          {LEVELS.map(l => <option key={l}>{l}</option>)}
        </select>
      </div>

      <div className={styles.checkboxRow}>
        <input type="checkbox" checked={form.autoRespond}
          onChange={e => set('autoRespond', e.target.checked)} />
        Auto-respond
      </div>

      <div className={styles.field}>
        <label>Welcome Message</label>
        <textarea className={styles.input} value={form.welcomeMessage}
          onChange={e => set('welcomeMessage', e.target.value)} rows={3} />
      </div>

      <div className={styles.field}>
        <label>Tenant</label>
        <select className={styles.input} value={form.tenantId ?? ''}
          onChange={e => set('tenantId', e.target.value || null)}>
          <option value="">None</option>
          {tenants.map(t => (
            <option key={t.id} value={t.id}>{t.name} ({t.id.slice(0, 8)})</option>
          ))}
        </select>
      </div>
    </Modal>
  )
}

export function Groups() {
  const authRequest = useAuthRequest()
  const api = groupsApi(authRequest)
  const [groups, setGroups] = useState([])
  const [modal, setModal] = useState(null)
  const [error, setError] = useState('')
  const [tenants, setTenants] = useState([])
  const [levelFilter, setLevelFilter] = useState('')

  const load = () => api.list().then(setGroups).catch(e => setError(e.message))
  useEffect(() => { load() }, [])
  useEffect(() => { tenantsApi(authRequest).list().then(setTenants).catch(() => {}) }, [])

  const filtered = groups.filter(g => !levelFilter || g.moderationLevel === levelFilter)

  const save = async form => {
    try {
      if (modal === 'add') await api.create(form)
      else await api.update(modal.telegramChatId, form)
      setModal(null)
      load()
    } catch (e) { setError(e.message) }
  }

  const remove = async group => {
    if (!confirm(`Stop watching "${group.name}"?`)) return
    try { await api.remove(group.telegramChatId); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Groups"
        systemId={`\u25C8 groups \u00b7 ${groups.length} watched`}
        addLabel="+ Add Group"
        onAdd={() => setModal('add')}
        columns={COLUMNS}
        rows={filtered}
        rowKey={r => r.telegramChatId ?? r.id}
        onEdit={setModal}
        onDelete={remove}
        filters={[{
          value: levelFilter,
          onChange: e => setLevelFilter(e.target.value),
          options: [
            { value: '', label: 'All moderation levels' },
            ...LEVELS.map(l => ({ value: l, label: l })),
          ],
        }]}
        emptyText="No groups match this filter"
      />

      {modal && (
        <GroupEditModal
          group={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
          tenants={tenants}
        />
      )}
    </>
  )
}
```

- [ ] **Step 3: Run all tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run 2>&1 | tail -20
```

Expected: all tests pass. The Groups page tests may need adjustment if they assert on removed DOM elements (the old hand-built table structure). If the Groups test fails, read the test file and update assertions to match the new DataTable-based structure.

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/Groups/
git commit -m "feat(admin-ui): redesign Groups page with v2 DataTable

Replace hand-built table with shared DataTable component. Add moderation
level filter, system-id line, v2 styled edit modal with metadata grid
and SectionLabel. First page fully on v2 design system."
```

---

### Task 8: Place CLAUDE.md project guidance

**Files:**
- Create: `emcip-admin-ui/CLAUDE.md`

- [ ] **Step 1: Create adapted CLAUDE.md**

Create `emcip-admin-ui/CLAUDE.md` — adapted from the handoff `CLAUDE.md` with production file paths and no references to `design_references/` or the handoff directory.

The file should be the full content of `emcip-admin-ui/design_handoff_emcip_admin/CLAUDE.md` with these changes:

1. Remove the first paragraph ("Drop this file at the **root of `emcip-admin-ui/`**..."). Replace with: "This file provides project-level guidance for the EMCIP Admin UI React app. It codifies the v2 visual + content system."

2. Replace the "Source of truth" table with:

| Concern | Where to look |
|---|---|
| Design tokens (colors, type scale, spacing, radii, shadows) | `src/main/frontend/src/theme/variables.css` |
| Shared components (Button, Badge, Modal, DataTable, SectionLabel) | `src/main/frontend/src/components/` |
| Page implementations | `src/main/frontend/src/pages/*/` |
| Layout (AppShell, Sidebar, SpaceBackground) | `src/main/frontend/src/layout/` |
| Iconography (Unicode glyphs, no icon library) | Sidebar nav definitions in `src/main/frontend/src/layout/Sidebar/Sidebar.jsx`; full table below |
| Voice & copy | section *Content rules* below |

3. In the "component recipes" section, update the DataTable reference: change "See `design_references/ui_kits/admin/DataTable.jsx`" to "See `src/main/frontend/src/components/DataTable/DataTable.jsx`".

4. In the "Reply composer" subsection, change "the **Send reply** action should POST to the moderation-service reply queue" to "the **Send reply** action POSTs to `POST /api/flags/{id}/reply` — see the admin-api FlagController".

5. Keep all 10 hard rules, the token reference table, component recipes, pages route map, iconography table, and content rules exactly as they are.

6. Keep the "What this file does **not** cover" section.

- [ ] **Step 2: Commit**

```bash
git add emcip-admin-ui/CLAUDE.md
git commit -m "docs(admin-ui): add v2 design system CLAUDE.md

Project guidance with 10 hard rules, token reference, component recipes,
icon table, content rules. Adapted from design handoff with production
file paths."
```

---

### Task 9: Delete design handoff directory

**Files:**
- Delete: `emcip-admin-ui/design_handoff_emcip_admin/` (entire directory)

- [ ] **Step 1: Verify all work is done**

Confirm the previous 8 tasks are complete and all tests pass:

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run 2>&1 | tail -20
```

- [ ] **Step 2: Delete the handoff directory**

```bash
rm -rf emcip-admin-ui/design_handoff_emcip_admin/
```

- [ ] **Step 3: Run tests one final time**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run 2>&1 | tail -20
```

Expected: all tests pass. Nothing references the handoff directory.

- [ ] **Step 4: Commit**

```bash
git add -A emcip-admin-ui/design_handoff_emcip_admin/
git commit -m "chore(admin-ui): remove design handoff directory

All design references are now integrated into production code and
the CLAUDE.md project guidance file. The handoff bundle is no longer
needed."
```

---

## Self-Review

**Spec coverage check:**

| Spec section | Task(s) |
|---|---|
| 1. Token System | Task 1 (variables.css + compat aliases) |
| 2. Font Setup | Task 1 (font files + @font-face + index.css typography) |
| 3. Shared Components — Button | Task 2 |
| 3. Shared Components — Badge | Task 3 |
| 3. Shared Components — Modal | Task 4 |
| 3. Shared Components — DataTable | Task 5 |
| 3. Shared Components — SectionLabel | Task 6 |
| 4. Project Guidance | Task 8 |
| 5. Groups Page Redesign | Task 7 |
| 6. Cleanup | Task 9 |
| 7. Testing | Tasks 1, 2, 3, 4, 5, 7, 9 (tests run at each stage) |

**Placeholder scan:** No TBD/TODO found. All steps have concrete code or commands.

**Type consistency check:**
- DataTable props in Task 5 (`title`, `systemId`, `columns`, `rows`, `rowKey`, `onEdit`, `onDelete`, `filters`, `addLabel`, `onAdd`, `emptyText`) match usage in Task 7.
- `SectionLabel` props (`children`, `aside`) in Task 6 match usage in Task 7.
- Badge variant names (`green`, `blue`, `yellow`, `red`, `gray`, `violet`) consistent across Tasks 3 and 7.
- CSS token names in component CSS (Tasks 2–6) match the variable definitions in Task 1.
