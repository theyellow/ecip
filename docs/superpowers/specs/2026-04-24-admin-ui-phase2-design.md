# EMCIP Admin UI — Phase 2 Design Spec

**Date:** 2026-04-24
**Author:** ben + Claude
**Branch:** fix/emcip-core-test-coverage → new feature branches
**Priority order:** Bug fixes → Theming & Branding → New Feature Pages

---

## Overview

This spec covers three incremental phases of Admin UI improvement:

1. **Phase A — Bug fixes & missing fields** (fast wins, shippable alone)
2. **Phase B — Theming & branding** (EMCIP visual identity, dark mode)
3. **Phase C — New feature pages** (Telegram config, AI/LLM config, tenant integration)

All UI communicates exclusively with the admin-api (API Gateway pattern). Admin-api proxies to internal services as needed.

---

## Phase A — Bug Fixes & Missing Fields

### A1. Simulator 405 error

**Root cause:** `index.html` line 918 calls `/api/simulate/message` as a relative URL, bypassing `API_BASE` (`http://localhost:9087`). The request hits the static file server which returns 405.

**Fix:** Prefix with `API_BASE`:
```javascript
fetch(API_BASE + '/api/simulate/message', { ... })
```

### A2. Group description not saved on update

**Root cause:** `GroupProfileController.java` PUT handler maps 4 fields but silently drops `description`:
```java
existing.setName(update.getName());
existing.setModerationLevel(update.getModerationLevel());
existing.setAutoRespond(update.isAutoRespond());
existing.setWelcomeMessage(update.getWelcomeMessage());
// MISSING: existing.setDescription(update.getDescription());
```

**Fix:** Add `existing.setDescription(update.getDescription());`

### A3. Tenant missing fields in UI

**Situation:** `Tenant` entity has `description` (String) and `llmModelOverride` (String) fields not exposed in the UI.

**Fix:**
- Add `description` textarea to tenant create modal
- Add `llmModelOverride` text input (with hint: e.g. `gpt-4o`, `claude-3-5-sonnet`) to tenant create modal
- Add `description` column to tenant table (truncated, optional)
- Include both fields in create POST payload

### A4. Groups: id + name combined display in selectors

**Situation:** Groups table correctly shows chatId and name separately. However, any dropdown/selector elsewhere (policy rule targeting, simulation target) should display `"Group Name (−1001234567890)"` for clarity.

**Fix:** No table change needed. When groups are used as selector options elsewhere, format as `${group.name} (${group.telegramChatId})`.

---

## Phase B — Theming & Branding

### B1. EMCIP Visual Identity

**Wordmark:** `font-family: 'Orbitron', sans-serif; font-weight: 700; letter-spacing: 0.15em`
Loaded from Google Fonts (single import). Same class reused across UI and documentation.

**Symbol — "The Construct":**
Inline SVG: hexagonal outline → inner eye (ellipse + iris circle) → 6 circuit traces radiating from hex corners.
*Inspired by the ICE geometry of Neuromancer / the Grail network of Otherland.*

- Light mode: `stroke: #3730a3` (deep indigo), `fill: none`
- Dark mode: `stroke: #00f5ff` (cold cyan) + `filter: drop-shadow(0 0 4px #00f5ff)` glow

### B2. CSS Variable System

All colors move to CSS custom properties. No hardcoded color values in component styles.

**Light mode (`:root`):**
```css
--bg-primary: #ffffff;
--bg-secondary: #f8fafc;
--bg-card: #ffffff;
--text-primary: #0f172a;
--text-secondary: #64748b;
--accent: #3730a3;
--accent-hover: #4338ca;
--border: #e2e8f0;
--sidebar-bg: #1e1b4b;
--sidebar-text: #c7d2fe;
--sidebar-active: #6366f1;
```

**Dark mode (`[data-theme="dark"]`):**
```css
--bg-primary: #0a0a1a;
--bg-secondary: #0f0f2e;
--bg-card: #12122a;
--text-primary: #e2e8f0;
--text-secondary: #94a3b8;
--accent: #00f5ff;
--accent-hover: #38bdf8;
--border: #1e1e3f;
--sidebar-bg: #050510;
--sidebar-text: #94a3b8;
--sidebar-active: #00f5ff;
```

### B3. Dark Mode Toggle

Small icon button in sidebar footer. SVG swaps between sun (light) and moon (dark). Preference persists to `localStorage` key `emcip-theme`. Applied to `<html data-theme="...">` on load.

### B4. Star Field (dark mode only)

`<canvas id="stars">` fixed behind all content, `z-index: -1`, visible only when `[data-theme="dark"]`.

- ~150 static stars, 3 size tiers (0.5px, 1px, 1.5px), random opacity 0.4–1.0
- ~8 stars drift at very slow speed (0.01–0.05px/frame)
- Subtle mouse parallax: star layers shift ±5px on mouse move (3 depth layers)
- No external libraries. Pure canvas 2D API.
- Canvas redraws only on resize or theme change — does not impact UI performance.

### B5. Favicon

Inline SVG favicon in `<head>`:
```html
<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,...">
```
The Construct symbol (hex-eye) at 32×32. Uses `@media (prefers-color-scheme: dark)` inside the SVG to switch stroke from indigo to cyan. No separate file needed.

### B6. AsciiDoc Documentation Stylesheet

File: `documentation/emcip-docs.css`

- Imports Orbitron from Google Fonts
- Applies EMCIP CSS variable palette to AsciiDoc-generated HTML
- Covers: headings (Orbitron), body (system-ui), code blocks (monospace, dark bg), admonitions (accent border)
- Usage: `asciidoctor -a stylesheet=../../documentation/emcip-docs.css your-doc.adoc`

---

## Phase C — New Feature Pages

### C1. Telegram Configuration Page

**Nav item:** "Telegram" (satellite dish icon)

**Connection Status card:**
- Fields: status badge (CONNECTED / DISCONNECTED / PENDING), phone number, last seen timestamp
- "Reconnect" button → `POST /api/telegram/reconnect` (admin-api → tdlib-adapter)

**Credentials card:**
- Phone Number (text)
- API ID (number)
- API Hash (text)
- Session String (monospace textarea, collapsible for visual cleanliness)
- Save → `PUT /api/telegram/config`
- Backend stores in DB; tdlib-adapter reads on startup/reconnect

**Auth flow placeholder:**
- "Request Auth Code" button present but disabled with tooltip "Coming in next phase"
- Reserves the UI surface for future live auth flow

**Backend additions (admin-api):**
- `GET /api/telegram/config` — returns current credentials (session string masked)
- `PUT /api/telegram/config` — saves credentials
- `GET /api/telegram/status` — returns connection status from tdlib-adapter
- `POST /api/telegram/reconnect` — triggers reconnect in tdlib-adapter

### C2. AI / LLM Configuration Page

**Nav item:** "AI Config" (brain/circuit icon)

**Models section:**
- Table: name, provider, endpoint, active (badge)
- Add / Edit (modal) / Delete
- Admin-api proxy: `GET /api/ai/models` → orchestrator `GET /api/models`
- Admin-api proxy: `POST /api/ai/models` → orchestrator `POST /api/models`

**Prompt Templates section:**
- Table: name, type, text preview (truncated)
- Add / Edit (full textarea modal) / Delete
- Admin-api proxy: `GET /api/ai/templates` → orchestrator `GET /api/templates`
- Admin-api proxy: `POST /api/ai/templates` → orchestrator `POST /api/templates`

**Deferred:** Cost summary / analytics (separate phase).

**Backend additions (admin-api):**
- `GET|POST|PUT|DELETE /api/ai/models[/{id}]` — proxy to orchestrator
- `GET|POST|PUT|DELETE /api/ai/templates[/{id}]` — proxy to orchestrator

### C3. Tenant Integration in Existing Pages

**Groups create/edit modal:**
- Add "Tenant" dropdown → `GET /api/tenants` → options formatted as `"Tenant Name (uuid-short)"`
- If `GroupProfile` entity already has a tenant FK: wire it. If not: add Liquibase migration + JPA field.

**Policy Rules create/edit modal:**
- Same tenant dropdown pattern
- Verify if `PolicyRule` entity has tenant association; add if missing.

**Constraint:** No backend schema changes without confirming FK existence first. Implementation step must read the entities before writing migrations.

---

## Architecture Notes

- **Single origin:** All UI calls go to admin-api only. Orchestrator and tdlib-adapter are internal.
- **Auth:** JWT from `POST /api/auth/token`. All new endpoints follow existing JWT filter pattern.
- **No new JS frameworks:** Everything stays in the existing vanilla JS + HTML approach.
- **Spotless:** `mvn spotless:apply` before every commit on Java changes.
- **Liquibase only:** Any schema changes via Liquibase changesets, never Flyway or DDL-auto.

---

## Out of Scope

- Live Telegram auth flow (phone → code → session) — Phase C+1
- Cost analytics / LLM usage dashboard — separate phase
- Multi-tenant data isolation enforcement — separate architectural concern
- Mobile/responsive layout — not required
