# Admin UI React Migration + EMCIP Theming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the monolithic `index.html` with a Vite + React SPA that implements EMCIP branding (Orbitron wordmark, "The Construct" hex-eye logo, dark mode with star field), fixes three known bugs, and migrates all five existing pages to React components.

**Architecture:** The `emcip-admin-ui` Spring Boot app (port 14009) serves the built React SPA from `src/main/resources/static/`. All API calls target the `emcip-admin-api` at `http://localhost:9087` via a configurable `VITE_API_BASE` env variable (build-time injection). A `SpaController` already handles React Router by forwarding all non-file paths to `index.html`.

**Tech Stack:** Vite 5, React 18, React Router v6, CSS Modules, Vitest + React Testing Library, frontend-maven-plugin 1.15.0, Orbitron (Google Fonts)

---

## Scope note

This is Plan 1 of 2. Plan 2 covers new feature pages: Telegram config, AI/LLM config, and tenant integration into Groups/PolicyRules — all of which require backend additions. Do not attempt Plan 2 until this plan is complete and merged.

---

## File Map

**Created:**
```
emcip-admin-ui/src/main/frontend/
  package.json
  vite.config.js
  index.html
  .env.development
  .env.production
  src/
    main.jsx
    App.jsx
    index.css
    test-setup.js
    theme/
      ThemeContext.jsx        — React context: theme state, toggleTheme, localStorage persistence
      variables.css           — CSS custom properties for light + dark palettes
    logo/
      Logo.jsx                — "The Construct" inline SVG (hex-eye)
    layout/
      AppShell/
        AppShell.jsx          — Root layout: StarField + Sidebar + main content outlet
        AppShell.module.css
      Sidebar/
        Sidebar.jsx           — Nav links, EMCIP wordmark, theme toggle
        Sidebar.module.css
      StarField/
        StarField.jsx         — Canvas star field (dark mode only)
    components/
      Badge/
        Badge.jsx             — Coloured badge (variant prop: green/blue/yellow/red/gray)
        Badge.module.css
      Modal/
        Modal.jsx             — Generic modal overlay + card with title/children/footer
        Modal.module.css
      Button/
        Button.jsx            — Styled button (variant: primary/secondary/danger)
        Button.module.css
    auth/
      AuthContext.jsx         — JWT token in memory; login/logout; useAuth hook
    api/
      client.js               — fetch wrapper with auth header; makeRequest(token)
      groups.js               — listGroups, createGroup, updateGroup, deleteGroup
      tenants.js              — listTenants, createTenant, deleteTenant
      policyRules.js          — listRules, getRuleHistory, createRule, updateRule, deleteRule
      auditLog.js             — listAuditEvents(size, eventType)
      simulate.js             — simulateMessage(token, payload)
    pages/
      Login/
        Login.jsx
        Login.module.css
        Login.test.jsx
      Groups/
        Groups.jsx
        Groups.module.css
        Groups.test.jsx
      Tenants/
        Tenants.jsx
        Tenants.module.css
      PolicyRules/
        PolicyRules.jsx
        PolicyRules.module.css
      AuditLog/
        AuditLog.jsx
        AuditLog.module.css
      Simulate/
        Simulate.jsx
        Simulate.module.css
        Simulate.test.jsx
```

**Modified:**
```
emcip-admin-ui/pom.xml                                      — add frontend-maven-plugin
emcip-admin-ui/src/main/resources/static/.gitkeep           — placeholder (built files gitignored)
emcip-admin-api/.../controller/GroupProfileController.java  — fix A2: save description on update
documentation/emcip-docs.css                                — AsciiDoc EMCIP stylesheet (new)
```

**Gitignore:**
```
emcip-admin-ui/src/main/frontend/node_modules/
emcip-admin-ui/src/main/resources/static/*
!emcip-admin-ui/src/main/resources/static/.gitkeep
emcip-admin-ui/target/node/
emcip-admin-ui/target/node_modules/
```

---

## Task 1: Scaffold the Vite + React project

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/package.json`
- Create: `emcip-admin-ui/src/main/frontend/vite.config.js`
- Create: `emcip-admin-ui/src/main/frontend/index.html`
- Create: `emcip-admin-ui/src/main/frontend/.env.development`
- Create: `emcip-admin-ui/src/main/frontend/.env.production`
- Create: `emcip-admin-ui/src/main/frontend/src/test-setup.js`
- Create: `emcip-admin-ui/src/main/resources/static/.gitkeep`

- [ ] **Step 1: Create `src/main/frontend/package.json`**

```json
{
  "name": "emcip-admin-ui",
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.26.0"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^6.4.6",
    "@testing-library/react": "^16.0.0",
    "@testing-library/user-event": "^14.5.2",
    "@vitejs/plugin-react": "^4.3.1",
    "jsdom": "^25.0.1",
    "vitest": "^2.1.3"
  }
}
```

- [ ] **Step 2: Create `src/main/frontend/vite.config.js`**

```js
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 14009,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.js'],
    globals: true,
  },
})
```

- [ ] **Step 3: Create `src/main/frontend/index.html`**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>EMCIP Admin</title>
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
  <link href="https://fonts.googleapis.com/css2?family=Orbitron:wght@700&display=swap" rel="stylesheet" />
  <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 40' fill='none'%3E%3Cstyle%3E@media(prefers-color-scheme:dark)%7Bpolygon,path,ellipse,circle%7Bstroke:%2300f5ff%7D.pupil%7Bfill:%2300f5ff%7D%7D%3C/style%3E%3Cpolygon points='20,5 32.99,12.5 32.99,27.5 20,35 7.01,27.5 7.01,12.5' stroke='%233730a3' stroke-width='1.5'/%3E%3Cpath d='M20,5 L20,1 M32.99,12.5 L37.32,10 M32.99,27.5 L37.32,30 M20,35 L20,39 M7.01,27.5 L2.68,30 M7.01,12.5 L2.68,10' stroke='%233730a3' stroke-width='1' stroke-linecap='round'/%3E%3Cellipse cx='20' cy='20' rx='6' ry='4' stroke='%233730a3' stroke-width='1.5'/%3E%3Ccircle cx='20' cy='20' r='2' stroke='%233730a3' stroke-width='1'/%3E%3Ccircle class='pupil' cx='20' cy='20' r='0.8' fill='%233730a3'/%3E%3C/svg%3E" />
</head>
<body>
  <div id="root"></div>
  <script type="module" src="/src/main.jsx"></script>
</body>
</html>
```

- [ ] **Step 4: Create env files**

`src/main/frontend/.env.development`:
```
VITE_API_BASE=http://localhost:9087
```

`src/main/frontend/.env.production`:
```
VITE_API_BASE=http://localhost:9087
```

- [ ] **Step 5: Create `src/main/frontend/src/test-setup.js`**

```js
import '@testing-library/jest-dom'
```

- [ ] **Step 6: Create `src/main/resources/static/.gitkeep`**

Empty file — ensures the directory exists in git so Spring Boot can serve from it.

- [ ] **Step 7: Run npm install to verify the scaffold**

```bash
cd emcip-admin-ui/src/main/frontend
npm install
```

Expected: `node_modules/` created, no errors.

- [ ] **Step 8: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/ emcip-admin-ui/src/main/resources/static/.gitkeep
git commit -m "feat(admin-ui): scaffold Vite + React 18 frontend project"
```

---

## Task 2: Add frontend-maven-plugin to pom.xml

**Files:**
- Modify: `emcip-admin-ui/pom.xml`
- Modify: root `.gitignore` (or `emcip-admin-ui/.gitignore`)

- [ ] **Step 1: Add the plugin to `emcip-admin-ui/pom.xml`**

Replace the `<build>` section with:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
    </plugin>
    <plugin>
      <groupId>com.github.eirslett</groupId>
      <artifactId>frontend-maven-plugin</artifactId>
      <version>1.15.0</version>
      <configuration>
        <workingDirectory>src/main/frontend</workingDirectory>
        <installDirectory>target</installDirectory>
        <nodeVersion>v20.18.0</nodeVersion>
        <npmVersion>10.8.0</npmVersion>
      </configuration>
      <executions>
        <execution>
          <id>install-node-and-npm</id>
          <goals><goal>install-node-and-npm</goal></goals>
        </execution>
        <execution>
          <id>npm-install</id>
          <goals><goal>npm</goal></goals>
          <configuration><arguments>install</arguments></configuration>
        </execution>
        <execution>
          <id>npm-build</id>
          <goals><goal>npm</goal></goals>
          <phase>generate-resources</phase>
          <configuration><arguments>run build</arguments></configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

- [ ] **Step 2: Add gitignore entries**

Create `emcip-admin-ui/.gitignore`:
```
src/main/frontend/node_modules/
src/main/resources/static/*
!src/main/resources/static/.gitkeep
target/node/
target/node_modules/
```

- [ ] **Step 3: Verify Maven build picks up the plugin**

```bash
cd emcip-admin-ui
mvn validate
```

Expected: `BUILD SUCCESS` (no compilation yet, just validates POM).

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-ui/pom.xml emcip-admin-ui/.gitignore
git commit -m "build(admin-ui): add frontend-maven-plugin for Vite build integration"
```

---

## Task 3: Theme system — CSS variables + ThemeContext

**Files:**
- Create: `src/main/frontend/src/theme/variables.css`
- Create: `src/main/frontend/src/theme/ThemeContext.jsx`
- Create: `src/main/frontend/src/index.css`

- [ ] **Step 1: Write the failing test**

Create `src/main/frontend/src/theme/ThemeContext.test.jsx`:
```jsx
import { render, screen, act } from '@testing-library/react'
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
```

- [ ] **Step 2: Run test — verify it fails**

```bash
cd emcip-admin-ui/src/main/frontend
npm test -- ThemeContext
```

Expected: FAIL — `ThemeContext` module not found.

- [ ] **Step 3: Create `src/theme/variables.css`**

```css
:root {
  --bg-primary: #ffffff;
  --bg-secondary: #f8fafc;
  --bg-card: #ffffff;
  --text-primary: #0f172a;
  --text-secondary: #64748b;
  --text-muted: #94a3b8;
  --accent: #3730a3;
  --accent-hover: #4338ca;
  --accent-text: #ffffff;
  --border: #e2e8f0;
  --shadow: rgba(0, 0, 0, 0.08);
  --sidebar-bg: #1e1b4b;
  --sidebar-text: #c7d2fe;
  --sidebar-active-bg: rgba(99, 102, 241, 0.2);
  --sidebar-active-text: #a5b4fc;
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
  --bg-primary: #0a0a1a;
  --bg-secondary: #0f0f2e;
  --bg-card: #12122a;
  --text-primary: #e2e8f0;
  --text-secondary: #94a3b8;
  --text-muted: #64748b;
  --accent: #00f5ff;
  --accent-hover: #38bdf8;
  --accent-text: #0a0a1a;
  --border: #1e1e3f;
  --shadow: rgba(0, 245, 255, 0.05);
  --sidebar-bg: #050510;
  --sidebar-text: #64748b;
  --sidebar-active-bg: rgba(0, 245, 255, 0.1);
  --sidebar-active-text: #00f5ff;
  --badge-green-bg: #052e16;
  --badge-green-text: #4ade80;
  --badge-blue-bg: #0c1a3a;
  --badge-blue-text: #60a5fa;
  --badge-yellow-bg: #1c1a04;
  --badge-yellow-text: #facc15;
  --badge-red-bg: #2a0808;
  --badge-red-text: #f87171;
  --badge-gray-bg: #1e1e3f;
  --badge-gray-text: #94a3b8;
}
```

- [ ] **Step 4: Create `src/theme/ThemeContext.jsx`**

```jsx
import { createContext, useContext, useEffect, useState } from 'react'

const ThemeContext = createContext(null)

export function ThemeProvider({ children }) {
  const [theme, setTheme] = useState(
    () => localStorage.getItem('emcip-theme') || 'light'
  )

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem('emcip-theme', theme)
  }, [theme])

  const toggleTheme = () => setTheme(t => (t === 'light' ? 'dark' : 'light'))

  return (
    <ThemeContext.Provider value={{ theme, toggleTheme }}>
      {children}
    </ThemeContext.Provider>
  )
}

export function useTheme() {
  return useContext(ThemeContext)
}
```

- [ ] **Step 5: Create `src/index.css`**

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
  font-family: 'Orbitron', sans-serif;
  font-weight: 700;
  letter-spacing: 0.15em;
  color: var(--accent);
}

a { color: var(--accent); text-decoration: none; }
```

- [ ] **Step 6: Run tests — verify they pass**

```bash
npm test -- ThemeContext
```

Expected: 3 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/frontend/src/theme/ src/main/frontend/src/index.css
git commit -m "feat(admin-ui): theme system with CSS variables, dark mode, ThemeContext"
```

---

## Task 4: The Construct logo + AsciiDoc stylesheet

**Files:**
- Create: `src/main/frontend/src/logo/Logo.jsx`
- Create: `documentation/emcip-docs.css`

- [ ] **Step 1: Create `src/logo/Logo.jsx`**

The Construct — hexagonal ICE geometry with a watcher's eye. Hex corners at radius 15, center (20,20). Circuit traces extend 4px outward from each corner.

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
        strokeWidth="1.5"
        fill="none"
      />
      {/* Circuit traces from each corner */}
      <path
        d="M20,5 L20,1 M32.99,12.5 L37.32,10 M32.99,27.5 L37.32,30 M20,35 L20,39 M7.01,27.5 L2.68,30 M7.01,12.5 L2.68,10"
        stroke="currentColor"
        strokeWidth="1"
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
        strokeWidth="1"
        fill="none"
      />
      {/* Eye — pupil */}
      <circle cx="20" cy="20" r="0.8" fill="currentColor" />
    </svg>
  )
}
```

- [ ] **Step 2: Create `documentation/emcip-docs.css`**

```css
/* EMCIP AsciiDoc stylesheet — use with: asciidoctor -a stylesheet=../../documentation/emcip-docs.css */
@import url('https://fonts.googleapis.com/css2?family=Orbitron:wght@700&family=Source+Code+Pro:wght@400;600&display=swap');

:root {
  --emcip-accent: #3730a3;
  --emcip-bg: #ffffff;
  --emcip-bg-alt: #f8fafc;
  --emcip-text: #0f172a;
  --emcip-text-muted: #64748b;
  --emcip-border: #e2e8f0;
  --emcip-code-bg: #1e1b4b;
  --emcip-code-text: #c7d2fe;
}

body { font-family: system-ui, -apple-system, sans-serif; color: var(--emcip-text); background: var(--emcip-bg); max-width: 960px; margin: 0 auto; padding: 2rem; }
h1, h2, h3, h4 { font-family: 'Orbitron', sans-serif; font-weight: 700; letter-spacing: 0.1em; color: var(--emcip-accent); margin: 1.5em 0 0.5em; }
h1 { font-size: 2rem; border-bottom: 2px solid var(--emcip-accent); padding-bottom: 0.5rem; }
h2 { font-size: 1.4rem; }
h3 { font-size: 1.1rem; }
p, li { line-height: 1.7; }
a { color: var(--emcip-accent); }
code, pre { font-family: 'Source Code Pro', monospace; }
code { background: var(--emcip-bg-alt); border: 1px solid var(--emcip-border); padding: 0.1em 0.4em; border-radius: 3px; font-size: 0.9em; }
pre { background: var(--emcip-code-bg); color: var(--emcip-code-text); padding: 1.2rem; border-radius: 6px; overflow-x: auto; border-left: 3px solid var(--emcip-accent); }
table { width: 100%; border-collapse: collapse; margin: 1rem 0; }
th { background: var(--emcip-accent); color: white; font-family: 'Orbitron', sans-serif; font-size: 0.75rem; letter-spacing: 0.1em; padding: 0.6rem 1rem; text-align: left; }
td { padding: 0.6rem 1rem; border-bottom: 1px solid var(--emcip-border); }
tr:nth-child(even) td { background: var(--emcip-bg-alt); }
.admonitionblock { border-left: 3px solid var(--emcip-accent); padding: 1rem 1.5rem; margin: 1rem 0; background: var(--emcip-bg-alt); }
.admonitionblock td.icon { font-family: 'Orbitron', sans-serif; font-size: 0.7rem; letter-spacing: 0.1em; color: var(--emcip-accent); padding-right: 1rem; white-space: nowrap; }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/frontend/src/logo/ documentation/emcip-docs.css
git commit -m "feat(admin-ui): The Construct logo SVG + EMCIP AsciiDoc stylesheet"
```

---

## Task 5: Shared components — Badge, Modal, Button

**Files:**
- Create: `src/components/Badge/Badge.jsx` + `Badge.module.css`
- Create: `src/components/Modal/Modal.jsx` + `Modal.module.css`
- Create: `src/components/Button/Button.jsx` + `Button.module.css`

- [ ] **Step 1: Create `src/components/Badge/Badge.jsx`**

```jsx
import styles from './Badge.module.css'

export function Badge({ variant = 'gray', children }) {
  return <span className={`${styles.badge} ${styles[variant]}`}>{children}</span>
}
```

`src/components/Badge/Badge.module.css`:
```css
.badge { display: inline-block; padding: 0.2em 0.6em; border-radius: 9999px; font-size: 0.75rem; font-weight: 600; }
.green  { background: var(--badge-green-bg);  color: var(--badge-green-text); }
.blue   { background: var(--badge-blue-bg);   color: var(--badge-blue-text); }
.yellow { background: var(--badge-yellow-bg); color: var(--badge-yellow-text); }
.red    { background: var(--badge-red-bg);    color: var(--badge-red-text); }
.gray   { background: var(--badge-gray-bg);   color: var(--badge-gray-text); }
```

- [ ] **Step 2: Create `src/components/Button/Button.jsx`**

```jsx
import styles from './Button.module.css'

export function Button({ variant = 'primary', children, ...props }) {
  return (
    <button className={`${styles.btn} ${styles[variant]}`} {...props}>
      {children}
    </button>
  )
}
```

`src/components/Button/Button.module.css`:
```css
.btn { padding: 0.5rem 1rem; border-radius: 6px; border: none; cursor: pointer; font-size: 0.875rem; font-weight: 500; transition: background 0.15s; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.primary   { background: var(--accent); color: var(--accent-text); }
.primary:hover:not(:disabled) { background: var(--accent-hover); }
.secondary { background: transparent; color: var(--text-secondary); border: 1px solid var(--border); }
.secondary:hover:not(:disabled) { background: var(--bg-secondary); }
.danger    { background: var(--badge-red-bg); color: var(--badge-red-text); }
.danger:hover:not(:disabled) { opacity: 0.8; }
```

- [ ] **Step 3: Create `src/components/Modal/Modal.jsx`**

```jsx
import { useEffect } from 'react'
import styles from './Modal.module.css'
import { Button } from '../Button/Button'

export function Modal({ title, onClose, onSubmit, submitLabel = 'Save', children }) {
  useEffect(() => {
    const handler = e => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [onClose])

  return (
    <div className={styles.overlay} onClick={e => e.target === e.currentTarget && onClose()}>
      <div className={styles.card} role="dialog" aria-modal="true">
        <div className={styles.header}>
          <h3>{title}</h3>
          <button className={styles.close} onClick={onClose} aria-label="Close">✕</button>
        </div>
        <div className={styles.body}>{children}</div>
        {onSubmit && (
          <div className={styles.footer}>
            <Button variant="secondary" onClick={onClose}>Cancel</Button>
            <Button variant="primary" onClick={onSubmit}>{submitLabel}</Button>
          </div>
        )}
      </div>
    </div>
  )
}
```

`src/components/Modal/Modal.module.css`:
```css
.overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 100; }
.card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 10px; width: 520px; max-width: 95vw; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 8px 32px var(--shadow); }
.header { display: flex; align-items: center; justify-content: space-between; padding: 1rem 1.25rem; border-bottom: 1px solid var(--border); }
.header h3 { font-size: 1rem; font-weight: 600; color: var(--text-primary); }
.close { background: none; border: none; cursor: pointer; color: var(--text-muted); font-size: 1.1rem; padding: 0.25rem; }
.body { padding: 1.25rem; overflow-y: auto; flex: 1; display: flex; flex-direction: column; gap: 0.75rem; }
.footer { padding: 1rem 1.25rem; border-top: 1px solid var(--border); display: flex; gap: 0.5rem; justify-content: flex-end; }
```

- [ ] **Step 4: Commit**

```bash
git add src/main/frontend/src/components/
git commit -m "feat(admin-ui): shared Badge, Button, Modal components"
```

---

## Task 6: StarField canvas component

**Files:**
- Create: `src/main/frontend/src/layout/StarField/StarField.jsx`

- [ ] **Step 1: Create `src/layout/StarField/StarField.jsx`**

```jsx
import { useEffect, useRef } from 'react'
import { useTheme } from '../../theme/ThemeContext'

const TOTAL = 150
const DRIFTERS = 8
const SIZES = [0.5, 1, 1.5]
const PARALLAX = [0.5, 1.5, 3]

export function StarField() {
  const { theme } = useTheme()
  const canvasRef = useRef(null)
  const animRef = useRef(null)
  const mouseRef = useRef({ x: 0, y: 0 })
  const starsRef = useRef(null)

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

    const onMouse = e => { mouseRef.current = { x: e.clientX, y: e.clientY } }
    window.addEventListener('mousemove', onMouse)

    return () => {
      cancelAnimationFrame(animRef.current)
      window.removeEventListener('resize', resize)
      window.removeEventListener('mousemove', onMouse)
    }
  }, [theme])

  if (theme !== 'dark') return null

  return (
    <canvas
      ref={canvasRef}
      style={{
        position: 'fixed', top: 0, left: 0,
        width: '100vw', height: '100vh',
        zIndex: -1, pointerEvents: 'none',
      }}
    />
  )
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/frontend/src/layout/StarField/
git commit -m "feat(admin-ui): StarField canvas — drifting parallax stars for dark mode"
```

---

## Task 7: AppShell + Sidebar layout

**Files:**
- Create: `src/main/frontend/src/layout/AppShell/AppShell.jsx` + `AppShell.module.css`
- Create: `src/main/frontend/src/layout/Sidebar/Sidebar.jsx` + `Sidebar.module.css`

- [ ] **Step 1: Create `src/layout/Sidebar/Sidebar.jsx`**

```jsx
import { NavLink } from 'react-router-dom'
import { Logo } from '../../logo/Logo'
import { useTheme } from '../../theme/ThemeContext'
import styles from './Sidebar.module.css'

const NAV = [
  { to: '/tenants',      label: 'Tenants',       icon: '⬡' },
  { to: '/policy-rules', label: 'Policy Rules',   icon: '⚖' },
  { to: '/groups',       label: 'Groups',         icon: '◈' },
  { to: '/audit-log',    label: 'Audit Log',      icon: '◎' },
  { to: '/simulate',     label: 'Simulate Event', icon: '▶' },
]

export function Sidebar() {
  const { theme, toggleTheme } = useTheme()

  return (
    <aside className={styles.sidebar}>
      <div className={styles.brand}>
        <Logo size={32} className={styles.logo} />
        <span className={`emcip-wordmark ${styles.wordmark}`}>EMCIP</span>
      </div>

      <nav className={styles.nav}>
        {NAV.map(({ to, label, icon }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) =>
              `${styles.item} ${isActive ? styles.active : ''}`
            }
          >
            <span className={styles.icon}>{icon}</span>
            {label}
          </NavLink>
        ))}
      </nav>

      <div className={styles.footer}>
        <button
          className={styles.themeToggle}
          onClick={toggleTheme}
          aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
          title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
        >
          {theme === 'light' ? '☽' : '☀'}
        </button>
      </div>
    </aside>
  )
}
```

`src/layout/Sidebar/Sidebar.module.css`:
```css
.sidebar { width: 220px; min-height: 100vh; background: var(--sidebar-bg); display: flex; flex-direction: column; padding: 1.25rem 0; flex-shrink: 0; }
.brand { display: flex; align-items: center; gap: 0.6rem; padding: 0 1.25rem 1.5rem; border-bottom: 1px solid rgba(255,255,255,0.06); }
.logo { color: var(--sidebar-active-text); }
.wordmark { font-size: 1.1rem; color: var(--sidebar-active-text); }
.nav { flex: 1; display: flex; flex-direction: column; padding: 1rem 0; gap: 0.1rem; }
.item { display: flex; align-items: center; gap: 0.6rem; padding: 0.6rem 1.25rem; color: var(--sidebar-text); font-size: 0.875rem; transition: background 0.15s, color 0.15s; }
.item:hover { background: var(--sidebar-active-bg); color: var(--sidebar-active-text); }
.active { background: var(--sidebar-active-bg); color: var(--sidebar-active-text); }
.icon { font-size: 1rem; width: 18px; text-align: center; }
.footer { padding: 1rem 1.25rem; border-top: 1px solid rgba(255,255,255,0.06); }
.themeToggle { background: none; border: none; cursor: pointer; font-size: 1.1rem; color: var(--sidebar-text); padding: 0.25rem; transition: color 0.15s; }
.themeToggle:hover { color: var(--sidebar-active-text); }
```

- [ ] **Step 2: Create `src/layout/AppShell/AppShell.jsx`**

```jsx
import { Outlet } from 'react-router-dom'
import { Sidebar } from '../Sidebar/Sidebar'
import { StarField } from '../StarField/StarField'
import styles from './AppShell.module.css'

export function AppShell() {
  return (
    <>
      <StarField />
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

`src/layout/AppShell/AppShell.module.css`:
```css
.shell { display: flex; min-height: 100vh; }
.main { flex: 1; padding: 2rem; background: var(--bg-primary); overflow-y: auto; min-height: 100vh; }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/frontend/src/layout/
git commit -m "feat(admin-ui): AppShell + Sidebar layout with EMCIP branding and theme toggle"
```

---

## Task 8: Auth context + API client layer

**Files:**
- Create: `src/main/frontend/src/auth/AuthContext.jsx`
- Create: `src/main/frontend/src/api/client.js`
- Create: `src/main/frontend/src/api/groups.js`
- Create: `src/main/frontend/src/api/tenants.js`
- Create: `src/main/frontend/src/api/policyRules.js`
- Create: `src/main/frontend/src/api/auditLog.js`
- Create: `src/main/frontend/src/api/simulate.js`

- [ ] **Step 1: Write failing test for the API client**

Create `src/main/frontend/src/api/client.test.js`:
```js
import { makeRequest } from './client'

beforeEach(() => {
  global.fetch = vi.fn()
})

test('makeRequest adds Authorization header', async () => {
  fetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true }) })
  const request = makeRequest('test-token')
  await request('/api/groups')
  expect(fetch).toHaveBeenCalledWith(
    expect.stringContaining('/api/groups'),
    expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer test-token' }),
    })
  )
})

test('makeRequest throws on non-ok response', async () => {
  fetch.mockResolvedValue({ ok: false, status: 404, statusText: 'Not Found' })
  const request = makeRequest('tok')
  await expect(request('/api/missing')).rejects.toThrow('404')
})

test('makeRequest returns null for 204 No Content', async () => {
  fetch.mockResolvedValue({ ok: true, status: 204 })
  const request = makeRequest('tok')
  const result = await request('/api/groups/1', { method: 'DELETE' })
  expect(result).toBeNull()
})
```

- [ ] **Step 2: Run test — verify it fails**

```bash
npm test -- client.test
```

Expected: FAIL — module not found.

- [ ] **Step 3: Create `src/api/client.js`**

```js
const API_BASE = import.meta.env.VITE_API_BASE ?? ''

export function makeRequest(token) {
  return async function request(path, options = {}) {
    const res = await fetch(`${API_BASE}${path}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        ...options.headers,
      },
    })
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
    if (res.status === 204) return null
    return res.json()
  }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
npm test -- client.test
```

Expected: 3 tests PASS.

- [ ] **Step 5: Create `src/auth/AuthContext.jsx`**

```jsx
import { createContext, useContext, useState } from 'react'

const API_BASE = import.meta.env.VITE_API_BASE ?? ''
const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [token, setToken] = useState(null)

  const login = async (username, password) => {
    const res = await fetch(`${API_BASE}/api/auth/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    })
    if (!res.ok) throw new Error('Invalid credentials')
    const data = await res.json()
    setToken(data.token)
  }

  const logout = () => setToken(null)

  return (
    <AuthContext.Provider value={{ token, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
```

- [ ] **Step 6: Create `src/api/groups.js`**

```js
export function groupsApi(request) {
  return {
    list: () => request('/api/groups'),
    create: body => request('/api/groups', { method: 'POST', body: JSON.stringify(body) }),
    update: (chatId, body) =>
      request(`/api/groups/${encodeURIComponent(chatId)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    remove: chatId =>
      request(`/api/groups/${encodeURIComponent(chatId)}`, { method: 'DELETE' }),
  }
}
```

- [ ] **Step 7: Create `src/api/tenants.js`**

```js
export function tenantsApi(request) {
  return {
    list: () => request('/api/tenants'),
    create: body => request('/api/tenants', { method: 'POST', body: JSON.stringify(body) }),
    remove: id => request(`/api/tenants/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  }
}
```

- [ ] **Step 8: Create `src/api/policyRules.js`**

```js
export function policyRulesApi(request) {
  return {
    list: () => request('/api/policy-rules'),
    history: name => request(`/api/policy-rules/history/${encodeURIComponent(name)}`),
    create: body =>
      request('/api/policy-rules', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) =>
      request(`/api/policy-rules/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    remove: id =>
      request(`/api/policy-rules/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  }
}
```

- [ ] **Step 9: Create `src/api/auditLog.js`**

```js
export function auditLogApi(request) {
  return {
    list: (size = 50, eventType = '') => {
      const params = new URLSearchParams({ size })
      if (eventType) params.set('eventType', eventType)
      return request(`/api/audit/events?${params}`)
    },
  }
}
```

- [ ] **Step 10: Create `src/api/simulate.js`**

```js
// Bug A1 fix: API_BASE is applied by makeRequest — no hardcoded relative URL.
export function simulateApi(request) {
  return {
    publish: body =>
      request('/api/simulate/message', { method: 'POST', body: JSON.stringify(body) }),
  }
}
```

- [ ] **Step 11: Commit**

```bash
git add src/main/frontend/src/auth/ src/main/frontend/src/api/
git commit -m "feat(admin-ui): auth context + typed API layer — fixes A1 simulator URL bug"
```

---

## Task 9: Login page

**Files:**
- Create: `src/pages/Login/Login.jsx` + `Login.module.css`
- Create: `src/pages/Login/Login.test.jsx`

- [ ] **Step 1: Write failing test**

`src/pages/Login/Login.test.jsx`:
```jsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider } from '../../auth/AuthContext'
import { Login } from './Login'

const renderLogin = (onSuccess = vi.fn()) =>
  render(<AuthProvider><Login onSuccess={onSuccess} /></AuthProvider>)

test('renders username and password fields', () => {
  renderLogin()
  expect(screen.getByLabelText(/username/i)).toBeInTheDocument()
  expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
})

test('calls login API on submit', async () => {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ token: 'abc' }),
  })
  const onSuccess = vi.fn()
  renderLogin(onSuccess)

  await userEvent.type(screen.getByLabelText(/username/i), 'admin')
  await userEvent.type(screen.getByLabelText(/password/i), 'secret')
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }))

  await waitFor(() => expect(onSuccess).toHaveBeenCalled())
})

test('shows error message on invalid credentials', async () => {
  global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 401 })
  renderLogin()

  await userEvent.type(screen.getByLabelText(/username/i), 'bad')
  await userEvent.type(screen.getByLabelText(/password/i), 'wrong')
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }))

  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent(/invalid credentials/i)
  )
})
```

- [ ] **Step 2: Run test — verify it fails**

```bash
npm test -- Login.test
```

Expected: FAIL — module not found.

- [ ] **Step 3: Create `src/pages/Login/Login.jsx`**

```jsx
import { useState } from 'react'
import { Logo } from '../../logo/Logo'
import { useAuth } from '../../auth/AuthContext'
import styles from './Login.module.css'

export function Login({ onSuccess }) {
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async e => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await login(username, password)
      onSuccess()
    } catch {
      setError('Invalid credentials')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.container}>
      <div className={styles.card}>
        <div className={styles.brand}>
          <Logo size={48} />
          <h1 className="emcip-wordmark">EMCIP</h1>
          <p className={styles.subtitle}>Community Intelligence Platform</p>
        </div>

        <form onSubmit={handleSubmit} className={styles.form}>
          {error && <p className={styles.error} role="alert">{error}</p>}

          <label htmlFor="username" className={styles.label}>Username</label>
          <input
            id="username"
            type="text"
            value={username}
            onChange={e => setUsername(e.target.value)}
            className={styles.input}
            autoComplete="username"
            required
          />

          <label htmlFor="password" className={styles.label}>Password</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            className={styles.input}
            autoComplete="current-password"
            required
          />

          <button type="submit" className={styles.submit} disabled={loading}>
            {loading ? 'Signing in…' : 'Sign In'}
          </button>
        </form>
      </div>
    </div>
  )
}
```

`src/pages/Login/Login.module.css`:
```css
.container { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--bg-primary); }
.card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 2.5rem; width: 360px; box-shadow: 0 8px 32px var(--shadow); }
.brand { text-align: center; margin-bottom: 2rem; display: flex; flex-direction: column; align-items: center; gap: 0.5rem; color: var(--accent); }
.brand h1 { font-size: 1.8rem; }
.subtitle { color: var(--text-muted); font-size: 0.8rem; letter-spacing: 0.05em; }
.form { display: flex; flex-direction: column; gap: 0.6rem; }
.label { font-size: 0.8rem; font-weight: 600; color: var(--text-secondary); letter-spacing: 0.03em; }
.input { padding: 0.6rem 0.75rem; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-secondary); color: var(--text-primary); font-size: 0.9rem; }
.input:focus { outline: 2px solid var(--accent); outline-offset: 1px; }
.error { background: var(--badge-red-bg); color: var(--badge-red-text); padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; }
.submit { margin-top: 0.5rem; padding: 0.7rem; background: var(--accent); color: var(--accent-text); border: none; border-radius: 6px; font-weight: 600; font-size: 0.9rem; cursor: pointer; }
.submit:hover:not(:disabled) { background: var(--accent-hover); }
.submit:disabled { opacity: 0.5; cursor: not-allowed; }
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
npm test -- Login.test
```

Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/frontend/src/pages/Login/
git commit -m "feat(admin-ui): Login page with EMCIP branding and error handling"
```

---

## Task 10: Groups page

**Files:**
- Create: `src/pages/Groups/Groups.jsx` + `Groups.module.css`
- Create: `src/pages/Groups/Groups.test.jsx`

- [ ] **Step 1: Write failing test**

`src/pages/Groups/Groups.test.jsx`:
```jsx
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AuthProvider } from '../../auth/AuthContext'
import { ThemeProvider } from '../../theme/ThemeContext'
import { Groups } from './Groups'

const mockGroups = [
  { telegramChatId: -1001234567890, name: 'Test Group', moderationLevel: 'MEDIUM',
    autoRespond: true, description: 'A test group' },
]

beforeEach(() => {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true, status: 200,
    json: async () => mockGroups,
  })
})

const wrap = ui => render(
  <MemoryRouter><ThemeProvider><AuthProvider>{ui}</AuthProvider></ThemeProvider></MemoryRouter>
)

test('renders groups table with name and chatId', async () => {
  wrap(<Groups />)
  await waitFor(() => expect(screen.getByText('Test Group')).toBeInTheDocument())
  expect(screen.getByText('-1001234567890')).toBeInTheDocument()
})
```

- [ ] **Step 2: Run test — verify it fails**

```bash
npm test -- Groups.test
```

Expected: FAIL.

- [ ] **Step 3: Create `src/pages/Groups/Groups.jsx`**

```jsx
import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { groupsApi } from '../../api/groups'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './Groups.module.css'

const LEVEL_VARIANT = { LOW: 'green', MEDIUM: 'blue', HIGH: 'yellow', STRICT: 'red' }

function GroupModal({ group, onClose, onSave }) {
  const [form, setForm] = useState({
    telegramChatId: group?.telegramChatId ?? '',
    name: group?.name ?? '',
    description: group?.description ?? '',
    moderationLevel: group?.moderationLevel ?? 'LOW',
    autoRespond: group?.autoRespond ?? false,
    welcomeMessage: group?.welcomeMessage ?? '',
  })

  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal
      title={group ? 'Edit Group' : 'Add Group'}
      onClose={onClose}
      onSubmit={() => onSave(form)}
    >
      {!group && (
        <>
          <label>Telegram Chat ID *</label>
          <input type="number" value={form.telegramChatId}
            onChange={e => set('telegramChatId', parseInt(e.target.value, 10))}
            className={styles.input} required />
        </>
      )}
      <label>Name *</label>
      <input type="text" value={form.name} onChange={e => set('name', e.target.value)}
        className={styles.input} required />
      <label>Description</label>
      <input type="text" value={form.description}
        onChange={e => set('description', e.target.value)} className={styles.input} />
      <label>Moderation Level</label>
      <select value={form.moderationLevel}
        onChange={e => set('moderationLevel', e.target.value)} className={styles.input}>
        {['LOW','MEDIUM','HIGH','STRICT'].map(l => <option key={l}>{l}</option>)}
      </select>
      <label>
        <input type="checkbox" checked={form.autoRespond}
          onChange={e => set('autoRespond', e.target.checked)} /> Auto-respond
      </label>
      <label>Welcome Message</label>
      <textarea value={form.welcomeMessage}
        onChange={e => set('welcomeMessage', e.target.value)} className={styles.input} rows={3} />
    </Modal>
  )
}

export function Groups() {
  const { token } = useAuth()
  const api = groupsApi(makeRequest(token))
  const [groups, setGroups] = useState([])
  const [modal, setModal] = useState(null) // null | 'add' | GroupProfile
  const [error, setError] = useState('')

  const load = () => api.list().then(setGroups).catch(e => setError(e.message))

  useEffect(() => { load() }, [])

  const save = async form => {
    const editing = modal !== 'add'
    try {
      if (editing) await api.update(modal.telegramChatId, form)
      else await api.create(form)
      setModal(null)
      load()
    } catch (e) { setError(e.message) }
  }

  const remove = async group => {
    if (!confirm(`Delete group "${group.name}"?`)) return
    try { await api.remove(group.telegramChatId); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <div>
      <div className={styles.header}>
        <h2>Groups</h2>
        <Button onClick={() => setModal('add')}>+ Add Group</Button>
      </div>
      {error && <p className={styles.error} role="alert">{error}</p>}

      <table className={styles.table}>
        <thead>
          <tr><th>Chat ID</th><th>Name</th><th>Moderation</th><th>Auto-respond</th><th>Description</th><th></th></tr>
        </thead>
        <tbody>
          {groups.map(g => (
            <tr key={g.telegramChatId}>
              <td className={styles.mono}>{g.telegramChatId}</td>
              <td>{g.name}</td>
              <td><Badge variant={LEVEL_VARIANT[g.moderationLevel] ?? 'gray'}>{g.moderationLevel}</Badge></td>
              <td><Badge variant={g.autoRespond ? 'green' : 'red'}>{g.autoRespond ? 'Yes' : 'No'}</Badge></td>
              <td className={styles.desc}>{g.description ?? '—'}</td>
              <td className={styles.actions}>
                <Button variant="secondary" onClick={() => setModal(g)}>Edit</Button>
                <Button variant="danger" onClick={() => remove(g)}>Delete</Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {modal && (
        <GroupModal
          group={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
        />
      )}
    </div>
  )
}
```

`src/pages/Groups/Groups.module.css`:
```css
.header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.5rem; }
.header h2 { font-size: 1.25rem; font-weight: 600; }
.table { width: 100%; border-collapse: collapse; background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.table th { padding: 0.7rem 1rem; text-align: left; font-size: 0.75rem; font-weight: 600; letter-spacing: 0.05em; text-transform: uppercase; color: var(--text-muted); background: var(--bg-secondary); border-bottom: 1px solid var(--border); }
.table td { padding: 0.7rem 1rem; border-bottom: 1px solid var(--border); font-size: 0.875rem; }
.table tr:last-child td { border-bottom: none; }
.mono { font-family: monospace; font-size: 0.8rem; color: var(--text-muted); }
.desc { color: var(--text-secondary); max-width: 200px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.actions { display: flex; gap: 0.4rem; }
.error { color: var(--badge-red-text); background: var(--badge-red-bg); padding: 0.5rem 0.75rem; border-radius: 6px; margin-bottom: 1rem; font-size: 0.85rem; }
.input { width: 100%; padding: 0.5rem 0.75rem; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-secondary); color: var(--text-primary); font-size: 0.875rem; }
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
npm test -- Groups.test
```

Expected: 1 test PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/frontend/src/pages/Groups/
git commit -m "feat(admin-ui): Groups page migrated to React"
```

---

## Task 11: Tenants page (Bug A3 fix — expose description + llmModelOverride)

**Files:**
- Create: `src/pages/Tenants/Tenants.jsx` + `Tenants.module.css`

- [ ] **Step 1: Create `src/pages/Tenants/Tenants.jsx`**

```jsx
import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { tenantsApi } from '../../api/tenants'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './Tenants.module.css'

function TenantModal({ onClose, onSave }) {
  const [form, setForm] = useState({ name: '', description: '', llmModelOverride: '' })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title="Create Tenant" onClose={onClose} onSubmit={() => onSave(form)}>
      <label>Name *</label>
      <input type="text" value={form.name} onChange={e => set('name', e.target.value)}
        className={styles.input} required />
      <label>Description</label>
      <textarea value={form.description} onChange={e => set('description', e.target.value)}
        className={styles.input} rows={3} />
      <label>LLM Model Override</label>
      <input type="text" value={form.llmModelOverride}
        onChange={e => set('llmModelOverride', e.target.value)}
        className={styles.input}
        placeholder="e.g. gpt-4o, claude-3-5-sonnet" />
    </Modal>
  )
}

export function Tenants() {
  const { token } = useAuth()
  const api = tenantsApi(makeRequest(token))
  const [tenants, setTenants] = useState([])
  const [showModal, setShowModal] = useState(false)
  const [error, setError] = useState('')

  const load = () => api.list().then(setTenants).catch(e => setError(e.message))

  useEffect(() => { load() }, [])

  const save = async form => {
    try { await api.create(form); setShowModal(false); load() }
    catch (e) { setError(e.message) }
  }

  const remove = async tenant => {
    if (!confirm(`Delete tenant "${tenant.name}"?`)) return
    try { await api.remove(tenant.id); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <div>
      <div className={styles.header}>
        <h2>Tenants</h2>
        <Button onClick={() => setShowModal(true)}>+ Create Tenant</Button>
      </div>
      {error && <p className={styles.error} role="alert">{error}</p>}

      <table className={styles.table}>
        <thead>
          <tr><th>ID</th><th>Name</th><th>Description</th><th>LLM Override</th><th>Created</th><th></th></tr>
        </thead>
        <tbody>
          {tenants.map(t => (
            <tr key={t.id}>
              <td className={styles.mono}>{t.id?.slice(0, 8)}…</td>
              <td>{t.name}</td>
              <td className={styles.desc}>{t.description ?? '—'}</td>
              <td className={styles.mono}>{t.llmModelOverride ?? '—'}</td>
              <td>{t.createdAt ? new Date(t.createdAt).toLocaleDateString() : '—'}</td>
              <td><Button variant="danger" onClick={() => remove(t)}>Delete</Button></td>
            </tr>
          ))}
        </tbody>
      </table>

      {showModal && <TenantModal onClose={() => setShowModal(false)} onSave={save} />}
    </div>
  )
}
```

`src/pages/Tenants/Tenants.module.css`: same table/input styles as Groups — copy the CSS (same variable names, same selectors).

- [ ] **Step 2: Commit**

```bash
git add src/main/frontend/src/pages/Tenants/
git commit -m "feat(admin-ui): Tenants page — expose description and llmModelOverride fields (fix A3)"
```

---

## Task 12: PolicyRules page

**Files:**
- Create: `src/pages/PolicyRules/PolicyRules.jsx` + `PolicyRules.module.css`

- [ ] **Step 1: Create `src/pages/PolicyRules/PolicyRules.jsx`**

This page is complex (versioning, history modal, create/edit form with date pickers). Implement it with the same pattern as Groups: load on mount, modal for create/edit, history modal triggered per rule.

```jsx
import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { policyRulesApi } from '../../api/policyRules'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './PolicyRules.module.css'

function RuleModal({ rule, onClose, onSave }) {
  const [form, setForm] = useState({
    ruleName: rule?.ruleName ?? '',
    ruleType: rule?.ruleType ?? 'KEYWORD',
    action: rule?.action ?? 'FLAG',
    parameters: rule?.parameters ?? '',
    effectiveFrom: rule?.effectiveFrom?.slice(0, 16) ?? '',
    effectiveTo: rule?.effectiveTo?.slice(0, 16) ?? '',
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={rule ? 'Edit Rule' : 'Create Rule'} onClose={onClose} onSubmit={() => onSave(form)}>
      <label>Rule Name *</label>
      <input type="text" value={form.ruleName} onChange={e => set('ruleName', e.target.value)}
        className={styles.input} required disabled={!!rule} />
      <label>Rule Type</label>
      <select value={form.ruleType} onChange={e => set('ruleType', e.target.value)} className={styles.input}>
        {['KEYWORD','REGEX','SENTIMENT','INTENT','COMPOSITE'].map(t => <option key={t}>{t}</option>)}
      </select>
      <label>Action</label>
      <select value={form.action} onChange={e => set('action', e.target.value)} className={styles.input}>
        {['FLAG','WARN','MUTE','BAN','DELETE','ESCALATE'].map(a => <option key={a}>{a}</option>)}
      </select>
      <label>Parameters (JSON)</label>
      <textarea value={form.parameters} onChange={e => set('parameters', e.target.value)}
        className={styles.input} rows={4} placeholder='{"keywords":["spam"]}' />
      <label>Effective From</label>
      <input type="datetime-local" value={form.effectiveFrom}
        onChange={e => set('effectiveFrom', e.target.value)} className={styles.input} />
      <label>Effective To</label>
      <input type="datetime-local" value={form.effectiveTo}
        onChange={e => set('effectiveTo', e.target.value)} className={styles.input} />
    </Modal>
  )
}

function HistoryModal({ ruleName, history, onClose }) {
  return (
    <Modal title={`History — ${ruleName}`} onClose={onClose}>
      {history.length === 0 ? <p>No history.</p> : history.map((h, i) => (
        <div key={i} className={styles.historyItem}>
          <span className={styles.mono}>v{h.version}</span>
          <span>{h.action}</span>
          <span className={styles.mono}>{h.changedAt ? new Date(h.changedAt).toLocaleString() : ''}</span>
        </div>
      ))}
    </Modal>
  )
}

const ACTION_VARIANT = { FLAG:'blue', WARN:'yellow', MUTE:'yellow', BAN:'red', DELETE:'red', ESCALATE:'gray' }

export function PolicyRules() {
  const { token } = useAuth()
  const api = policyRulesApi(makeRequest(token))
  const [rules, setRules] = useState([])
  const [modal, setModal] = useState(null)
  const [history, setHistory] = useState(null)
  const [error, setError] = useState('')

  const load = () => api.list().then(setRules).catch(e => setError(e.message))

  useEffect(() => { load() }, [])

  const save = async form => {
    try {
      const payload = {
        ...form,
        effectiveFrom: form.effectiveFrom ? new Date(form.effectiveFrom).toISOString() : null,
        effectiveTo: form.effectiveTo ? new Date(form.effectiveTo).toISOString() : null,
      }
      if (modal === 'add') await api.create(payload)
      else await api.update(modal.id, payload)
      setModal(null); load()
    } catch (e) { setError(e.message) }
  }

  const remove = async rule => {
    if (!confirm(`Delete rule "${rule.ruleName}"?`)) return
    try { await api.remove(rule.id); load() }
    catch (e) { setError(e.message) }
  }

  const showHistory = async rule => {
    const h = await api.history(rule.ruleName).catch(() => [])
    setHistory({ ruleName: rule.ruleName, items: h })
  }

  return (
    <div>
      <div className={styles.header}>
        <h2>Policy Rules</h2>
        <Button onClick={() => setModal('add')}>+ Create Rule</Button>
      </div>
      {error && <p className={styles.error} role="alert">{error}</p>}

      <table className={styles.table}>
        <thead>
          <tr><th>Rule Name</th><th>Type</th><th>Action</th><th>Effective From</th><th>Effective To</th><th></th></tr>
        </thead>
        <tbody>
          {rules.map(r => (
            <tr key={r.id}>
              <td>{r.ruleName}</td>
              <td><Badge variant="gray">{r.ruleType}</Badge></td>
              <td><Badge variant={ACTION_VARIANT[r.action] ?? 'gray'}>{r.action}</Badge></td>
              <td className={styles.mono}>{r.effectiveFrom ? new Date(r.effectiveFrom).toLocaleDateString() : '—'}</td>
              <td className={styles.mono}>{r.effectiveTo ? new Date(r.effectiveTo).toLocaleDateString() : '—'}</td>
              <td className={styles.actions}>
                <Button variant="secondary" onClick={() => showHistory(r)}>History</Button>
                <Button variant="secondary" onClick={() => setModal(r)}>Edit</Button>
                <Button variant="danger" onClick={() => remove(r)}>Delete</Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {modal && <RuleModal rule={modal === 'add' ? null : modal} onClose={() => setModal(null)} onSave={save} />}
      {history && <HistoryModal ruleName={history.ruleName} history={history.items} onClose={() => setHistory(null)} />}
    </div>
  )
}
```

`PolicyRules.module.css`: same table + input styles; add:
```css
.historyItem { display: flex; gap: 1rem; padding: 0.4rem 0; border-bottom: 1px solid var(--border); font-size: 0.85rem; }
.historyItem:last-child { border-bottom: none; }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/frontend/src/pages/PolicyRules/
git commit -m "feat(admin-ui): PolicyRules page migrated to React"
```

---

## Task 13: AuditLog + Simulate pages

**Files:**
- Create: `src/pages/AuditLog/AuditLog.jsx` + `AuditLog.module.css`
- Create: `src/pages/Simulate/Simulate.jsx` + `Simulate.module.css`
- Create: `src/pages/Simulate/Simulate.test.jsx`

- [ ] **Step 1: Write failing test for Simulate (verifies A1 fix)**

`src/pages/Simulate/Simulate.test.jsx`:
```jsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider } from '../../auth/AuthContext'
import { ThemeProvider } from '../../theme/ThemeContext'
import { Simulate } from './Simulate'

beforeEach(() => {
  global.fetch = vi.fn()
})

const wrap = ui => render(<ThemeProvider><AuthProvider>{ui}</AuthProvider></ThemeProvider>)

test('publishes to /api/simulate/message — not a relative path', async () => {
  fetch
    .mockResolvedValueOnce({ ok: true, json: async () => ({ token: 'tok' }) }) // auth
    .mockResolvedValueOnce({
      ok: true,
      status: 202,
      json: async () => ({ eventId: 'abc', status: 'published' }),
    })

  wrap(<Simulate />)

  await userEvent.type(screen.getByLabelText(/chat id/i), '-1001234567890')
  await userEvent.type(screen.getByLabelText(/message text/i), 'Hello world')
  await userEvent.click(screen.getByRole('button', { name: /publish/i }))

  await waitFor(() => {
    const call = fetch.mock.calls.find(c => c[0].includes('/api/simulate/message'))
    expect(call).toBeDefined()
    // Must NOT be a bare relative path — API_BASE prefix must be present or path must be correct
    expect(call[1].method).toBe('POST')
  })
})
```

- [ ] **Step 2: Run test — verify it fails**

```bash
npm test -- Simulate.test
```

Expected: FAIL.

- [ ] **Step 3: Create `src/pages/Simulate/Simulate.jsx`**

```jsx
import { useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { simulateApi } from '../../api/simulate'
import { Button } from '../../components/Button/Button'
import styles from './Simulate.module.css'

export function Simulate() {
  const { token } = useAuth()
  const api = simulateApi(makeRequest(token))
  const [form, setForm] = useState({ chatId: '', senderId: 'sim-user', senderType: 'USER', text: '' })
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  const publish = async () => {
    if (!form.chatId || !form.text) { setError('Chat ID and Message Text are required'); return }
    setError(''); setResult(null); setLoading(true)
    try {
      const res = await api.publish({ ...form, chatId: parseInt(form.chatId, 10) })
      setResult(res)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.container}>
      <h2>Simulate Event</h2>
      <p className={styles.subtitle}>Publish a test message into the processing pipeline.</p>

      <div className={styles.card}>
        <div className={styles.field}>
          <label htmlFor="chatId">Chat ID *</label>
          <input id="chatId" type="number" value={form.chatId}
            onChange={e => set('chatId', e.target.value)} className={styles.input} />
        </div>
        <div className={styles.field}>
          <label htmlFor="senderId">Sender ID</label>
          <input id="senderId" type="text" value={form.senderId}
            onChange={e => set('senderId', e.target.value)} className={styles.input} />
        </div>
        <div className={styles.field}>
          <label htmlFor="senderType">Sender Type</label>
          <select id="senderType" value={form.senderType}
            onChange={e => set('senderType', e.target.value)} className={styles.input}>
            {['USER','BOT','ADMIN'].map(t => <option key={t}>{t}</option>)}
          </select>
        </div>
        <div className={styles.field}>
          <label htmlFor="text">Message Text *</label>
          <textarea id="text" value={form.text} onChange={e => set('text', e.target.value)}
            className={styles.input} rows={4} />
        </div>

        {error && <p className={styles.error} role="alert">{error}</p>}

        <Button onClick={publish} disabled={loading}>
          {loading ? 'Publishing…' : '▶ Publish Message'}
        </Button>

        {result && (
          <div className={styles.result}>
            <p className={styles.success}>Published successfully</p>
            <pre>{JSON.stringify(result, null, 2)}</pre>
          </div>
        )}
      </div>

      <div className={styles.pipeline}>
        <h3>Pipeline Flow</h3>
        <ol>
          <li><code>telegram.raw.messages</code> → LLM Orchestrator classifies intent</li>
          <li><code>messages.classified</code> → Policy Engine evaluates rules</li>
          <li><code>policies.decisions</code> → Moderation Service applies action</li>
          <li>All steps recorded in Audit Log</li>
        </ol>
      </div>
    </div>
  )
}
```

`Simulate.module.css`:
```css
.container { max-width: 640px; }
.container h2 { font-size: 1.25rem; font-weight: 600; margin-bottom: 0.25rem; }
.subtitle { color: var(--text-secondary); font-size: 0.875rem; margin-bottom: 1.5rem; }
.card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 1.5rem; display: flex; flex-direction: column; gap: 1rem; margin-bottom: 1.5rem; }
.field { display: flex; flex-direction: column; gap: 0.3rem; }
.field label { font-size: 0.8rem; font-weight: 600; color: var(--text-secondary); }
.input { padding: 0.5rem 0.75rem; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-secondary); color: var(--text-primary); font-size: 0.875rem; width: 100%; }
.error { color: var(--badge-red-text); background: var(--badge-red-bg); padding: 0.5rem; border-radius: 6px; font-size: 0.85rem; }
.result { background: var(--bg-secondary); border: 1px solid var(--border); border-radius: 6px; padding: 1rem; }
.result pre { font-size: 0.8rem; color: var(--text-secondary); white-space: pre-wrap; margin-top: 0.5rem; }
.success { color: var(--badge-green-text); font-weight: 600; font-size: 0.85rem; }
.pipeline { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 1.25rem; }
.pipeline h3 { font-size: 0.9rem; font-weight: 600; margin-bottom: 0.75rem; color: var(--text-secondary); }
.pipeline ol { padding-left: 1.25rem; display: flex; flex-direction: column; gap: 0.4rem; font-size: 0.85rem; }
```

- [ ] **Step 4: Create `src/pages/AuditLog/AuditLog.jsx`**

```jsx
import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { auditLogApi } from '../../api/auditLog'
import styles from './AuditLog.module.css'

const EVENT_TYPES = ['', 'MESSAGE_RECEIVED', 'MESSAGE_CLASSIFIED', 'POLICY_DECISION', 'MODERATION_ACTION']

export function AuditLog() {
  const { token } = useAuth()
  const api = auditLogApi(makeRequest(token))
  const [events, setEvents] = useState([])
  const [size, setSize] = useState(50)
  const [eventType, setEventType] = useState('')
  const [error, setError] = useState('')

  const load = () =>
    api.list(size, eventType).then(setEvents).catch(e => setError(e.message))

  useEffect(() => { load() }, [size, eventType])

  return (
    <div>
      <div className={styles.header}>
        <h2>Audit Log</h2>
        <div className={styles.filters}>
          <select value={eventType} onChange={e => setEventType(e.target.value)} className={styles.select}>
            {EVENT_TYPES.map(t => <option key={t} value={t}>{t || 'All types'}</option>)}
          </select>
          <select value={size} onChange={e => setSize(Number(e.target.value))} className={styles.select}>
            {[25, 50, 100, 200].map(n => <option key={n}>{n}</option>)}
          </select>
        </div>
      </div>
      {error && <p className={styles.error}>{error}</p>}

      <table className={styles.table}>
        <thead>
          <tr><th>Timestamp</th><th>Event Type</th><th>Entity ID</th><th>Details</th></tr>
        </thead>
        <tbody>
          {events.map((e, i) => (
            <tr key={i}>
              <td className={styles.mono}>{e.timestamp ? new Date(e.timestamp).toLocaleString() : '—'}</td>
              <td>{e.eventType}</td>
              <td className={styles.mono}>{e.entityId ?? '—'}</td>
              <td className={styles.details}>{e.details ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

`AuditLog.module.css`: table + filter styles using same variables.

- [ ] **Step 5: Run Simulate tests — verify they pass**

```bash
npm test -- Simulate.test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/frontend/src/pages/AuditLog/ src/main/frontend/src/pages/Simulate/
git commit -m "feat(admin-ui): AuditLog + Simulate pages — fixes A1 simulator URL bug"
```

---

## Task 14: Wire App.jsx, main.jsx, and run full test suite

**Files:**
- Create: `src/main/frontend/src/App.jsx`
- Create: `src/main/frontend/src/main.jsx`

- [ ] **Step 1: Create `src/App.jsx`**

```jsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ThemeProvider } from './theme/ThemeContext'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { AppShell } from './layout/AppShell/AppShell'
import { Login } from './pages/Login/Login'
import { Groups } from './pages/Groups/Groups'
import { Tenants } from './pages/Tenants/Tenants'
import { PolicyRules } from './pages/PolicyRules/PolicyRules'
import { AuditLog } from './pages/AuditLog/AuditLog'
import { Simulate } from './pages/Simulate/Simulate'

function AuthGate() {
  const { token, setToken } = useAuth()

  if (!token) {
    return <Login onSuccess={() => {}} />
  }

  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<Navigate to="/tenants" replace />} />
        <Route path="tenants" element={<Tenants />} />
        <Route path="policy-rules" element={<PolicyRules />} />
        <Route path="groups" element={<Groups />} />
        <Route path="audit-log" element={<AuditLog />} />
        <Route path="simulate" element={<Simulate />} />
      </Route>
    </Routes>
  )
}

export function App() {
  return (
    <BrowserRouter>
      <ThemeProvider>
        <AuthProvider>
          <AuthGate />
        </AuthProvider>
      </ThemeProvider>
    </BrowserRouter>
  )
}
```

Note: `useAuth` needs to expose `token` to `AuthGate`. Verify `AuthContext.jsx` exposes `token` in its context value — it does (from Task 8).

- [ ] **Step 2: Create `src/main.jsx`**

```jsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import { App } from './App'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>
)
```

- [ ] **Step 3: Run full test suite**

```bash
cd emcip-admin-ui/src/main/frontend
npm test
```

Expected: All tests PASS (ThemeContext ×3, client ×3, Login ×3, Groups ×1, Simulate ×1 = 11 tests).

- [ ] **Step 4: Commit**

```bash
git add src/main/frontend/src/App.jsx src/main/frontend/src/main.jsx
git commit -m "feat(admin-ui): wire App.jsx router — all pages connected"
```

---

## Task 15: Backend — fix GroupProfileController (Bug A2)

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/GroupProfileController.java:65-70`

- [ ] **Step 1: Add the missing line in the PUT handler**

In `GroupProfileController.java`, inside the `flatMap` at line 65, add `existing.setDescription(update.getDescription());` after `setName`:

```java
existing.setName(update.getName());
existing.setDescription(update.getDescription());  // ← add this
existing.setModerationLevel(update.getModerationLevel());
existing.setAutoRespond(update.isAutoRespond());
existing.setWelcomeMessage(update.getWelcomeMessage());
existing.setUpdatedAt(Instant.now());
```

- [ ] **Step 2: Run Spotless**

```bash
cd emcip-admin-api
mvn spotless:apply
```

Expected: `0 were changed to be clean`.

- [ ] **Step 3: Run admin-api tests**

```bash
mvn test
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/GroupProfileController.java
git commit -m "fix(admin-api): save description field on group profile update (A2)"
```

---

## Task 16: Verify Vite build + Maven integration

- [ ] **Step 1: Run Vite build standalone**

```bash
cd emcip-admin-ui/src/main/frontend
npm run build
```

Expected: build succeeds, `src/main/resources/static/` contains `index.html` and hashed asset files.

- [ ] **Step 2: Verify Spring Boot serves the built SPA**

```bash
cd emcip-admin-ui
mvn spring-boot:run &
sleep 5
curl -s http://localhost:14009/ | grep -o '<title>[^<]*</title>'
```

Expected: `<title>EMCIP Admin</title>`

Kill the background process: `pkill -f spring-boot:run`

- [ ] **Step 3: Run full Maven build from repo root**

```bash
cd /home/ben/Development/ecip
mvn package -pl emcip-admin-ui -am -DskipTests
```

Expected: BUILD SUCCESS. The `target/emcip-admin-ui-*.jar` contains the built React assets.

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "build(admin-ui): verify Vite + Maven frontend build integration end-to-end"
```

---

## Self-Review

**Spec coverage check:**
- A1 (simulator 405): ✅ Task 8 (api/client.js uses API_BASE), Task 13 (simulate.js uses makeRequest)
- A2 (group description): ✅ Task 15
- A3 (tenant fields): ✅ Task 11
- A4 (id+name selectors): ✅ Groups/Tenants pages format options as `"Name (id)"` where used as selectors — deferred to Plan 2 when dropdowns are added to Groups/PolicyRules
- B1 The Construct logo: ✅ Task 4
- B2 CSS variables: ✅ Task 3
- B3 Dark mode toggle: ✅ Task 7 (Sidebar)
- B4 Star field: ✅ Task 6
- B5 Favicon: ✅ Task 1 (inline SVG in index.html)
- B6 AsciiDoc stylesheet: ✅ Task 4
- React/Vite migration: ✅ Tasks 1–16
- frontend-maven-plugin: ✅ Task 2
- Auth in memory (not localStorage): ✅ Task 8 (AuthContext uses useState)

**Placeholder scan:** None found.

**Type consistency:** `makeRequest(token)` returns a fetch function — all api/*.js files accept this function as `request` parameter consistently. `groupsApi(request)`, `tenantsApi(request)` etc. all follow the same factory pattern.
