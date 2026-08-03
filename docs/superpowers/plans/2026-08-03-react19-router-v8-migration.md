# React 19 + react-router v8 Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the admin-ui frontend to React 19 and react-router v8, and clear the entire `npm audit`, fully absorbing backlog item RT2-015.

**Architecture:** Four sequential, independently-green tasks. (1) Bump React 18→19 while leaving the router untouched — react-router-dom@6's peer (`react >=16.8`) accepts React 19, so this step builds and tests clean on its own. (2) Swap `react-router-dom@6` → `react-router@8` (v8 dropped the `react-router-dom` package; all classic APIs re-export from `react-router`) — a pure module-specifier change across 10 import sites, no API rewrites. (3) Bump the test/build toolchain (vitest 4 / vite 8 / @vitejs/plugin-react 6) and clear the remaining vite/esbuild/postcss advisories, driving `npm audit` to 0. (4) Update the backlog/roadmap trackers.

**Tech Stack:** React 19, react-router 8, Vite 8, Vitest 4, @vitejs/plugin-react 6, @testing-library/react 16.3.2, Node 24 (project has v24.18; vite 8 requires `^20.19 || >=22.12`).

## Global Constraints

- **Working directory:** every `npm` / `npx` / `grep` command in this plan runs from `emcip-admin-ui/src/main/frontend/` unless a repo-root path is given. This is the only frontend package.
- **Migrate only — add no features:** do not adopt React 19 features (Actions, `use`, `ref`-as-prop refactors), data-router / RSC APIs, or router `future` flags. Preserve every route path and component API exactly.
- **Tests are the safety net — never weaken them:** all 22 test files must stay green. When a test breaks, fix the migration cause, not the assertion. Never delete, `.skip`, or loosen a test to make the suite pass.
- **Import from `react-router`, never `react-router-dom`:** after Task 2, `grep -rn "react-router-dom" src/` must return nothing.
- **Version floors (exact target ranges to write into `package.json`):** `react`/`react-dom` `^19.2.8` (satisfies react-router@8 peer `>=19.2.7`), `react-router` `^8.3.0`, `@testing-library/react` `^16.3.2`, `vitest` `^4.1.10`, `vite` `^8.2.0`, `@vitejs/plugin-react` `^6.0.5`.
- **Final acceptance gates (all three, at end of Task 3):** `npm run build` succeeds, `npm test` reports all suites passing, `npm audit` reports `found 0 vulnerabilities`.
- **Commits:** one commit per task, `feat(admin-ui): …` / `docs(…): …`. No Spotless (that is Java-only; the frontend is not Spotless-formatted).

---

### Task 1: Bump React 18 → 19 (router untouched)

Isolate the React major bump. react-router-dom@6.26 stays in place and keeps working (its peer is `react >=16.8`), so this task leaves a fully green tree before any router change.

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/package.json`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: React 19 runtime + `@testing-library/react@16.3.2` in `node_modules`, on which Tasks 2 and 3 depend (react-router@8 peer requires React `>=19.2.7`).

- [ ] **Step 1: Edit `package.json` dependency versions**

In `dependencies`:
```json
"react": "^19.2.8",
"react-dom": "^19.2.8",
```
In `devDependencies`:
```json
"@testing-library/react": "^16.3.2",
```
Leave `react-router-dom: "^6.26.0"` and every other line unchanged.

- [ ] **Step 2: Install**

Run: `npm install`
Expected: completes without `ERESOLVE` peer errors (react-router-dom@6 accepts React 19).

- [ ] **Step 3: Verify the resolved versions**

Run: `npm ls react react-dom @testing-library/react`
Expected: `react@19.2.x`, `react-dom@19.2.x`, `@testing-library/react@16.3.2`, no `UNMET`/`invalid` markers.

- [ ] **Step 4: Run the full test suite**

Run: `npm test`
Expected: all 22 test files pass. `act` is already imported from `@testing-library/react` (verified in `auth/AuthContext.test.jsx` and `pages/Groups/BackfillModal.test.jsx`), so no `react-dom/test-utils` fallout. If a test flags a React-19 `act(...)` warning as a failure, wrap the offending state update in the test's existing `act`/`waitFor` — do not change component behavior.

- [ ] **Step 5: Build**

Run: `npm run build`
Expected: build succeeds, output written to `../resources/static`.

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/package.json emcip-admin-ui/src/main/frontend/package-lock.json
git commit -m "feat(admin-ui): bump React 18 → 19 (RT2-015)"
```

---

### Task 2: Swap react-router-dom@6 → react-router@8

v8 removed the `react-router-dom` package and re-exports the classic APIs from `react-router`. The app uses only classic APIs (`BrowserRouter`, `Routes`, `Route`, `Navigate`, `NavLink`, `Outlet`, `useNavigate`, `useParams`, `MemoryRouter`), so this is a package swap plus a specifier rewrite — no API changes, no `future` flags.

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/package.json`
- Modify (specifier `'react-router-dom'` → `'react-router'`, nothing else on the line):
  - `src/App.jsx:2` — `BrowserRouter, Routes, Route, Navigate`
  - `src/layout/AppShell/AppShell.jsx:1` — `Outlet`
  - `src/layout/Sidebar/Sidebar.jsx:2` — `NavLink`
  - `src/layout/Sidebar/Sidebar.test.jsx:3` — `MemoryRouter`
  - `src/pages/IntentRules/IntentRules.jsx:2` — `useNavigate`
  - `src/pages/IntentSignalConfig/IntentSignalConfig.jsx:2` — `useNavigate`
  - `src/pages/Research/ResearchPage.jsx:2` — `useNavigate`
  - `src/pages/Research/ResearchPage.test.jsx:2` — `MemoryRouter`
  - `src/pages/Research/SessionDetailPage.jsx:2` — `useNavigate, useParams`
  - `src/pages/Groups/Groups.test.jsx:2` — `MemoryRouter`

**Interfaces:**
- Consumes: React 19 from Task 1 (react-router@8 peer `react >=19.2.7`).
- Produces: `react-router@8` as the single routing package; all imports resolve from `'react-router'`.

- [ ] **Step 1: Edit `package.json` — replace the router dependency**

In `dependencies`, remove `"react-router-dom": "^6.26.0",` and add:
```json
"react-router": "^8.3.0",
```

- [ ] **Step 2: Install**

Run: `npm install`
Expected: no `ERESOLVE`; `npm ls react-router` shows `react-router@8.3.x` and `react-router-dom` is gone.

- [ ] **Step 3: Rewrite the 10 import specifiers**

In each of the 10 files listed under **Files**, change only the module string on the import line:
```diff
-from 'react-router-dom'
+from 'react-router'
```
Leave the named imports (`{ … }`) exactly as they are.

- [ ] **Step 4: Verify no stale specifier remains in source**

Run: `grep -rn "react-router-dom" src/`
Expected: no output. (A leftover match in `../resources/static/assets/*.js` is a stale build artifact — ignore it; it is regenerated by `npm run build` with `emptyOutDir`.)

- [ ] **Step 5: Run the full test suite**

Run: `npm test`
Expected: all 22 suites pass — the router-based tests (`Sidebar`, `Groups`, `ResearchPage`, and every suite that renders `<App/>` or a `MemoryRouter`) exercise the new import path.

- [ ] **Step 6: Build**

Run: `npm run build`
Expected: build succeeds with no unresolved-import errors.

- [ ] **Step 7: Routing smoke check**

Run: `npm run dev` (starts on port 14009), then in a browser confirm the app loads, redirects `/` → `/telegram`, and sidebar navigation between at least two routes (e.g. `/telegram` → `/research` → a `/research/:id` deep link) works. Stop the dev server (Ctrl-C) when done. If a browser is unavailable, the passing router tests from Step 5 plus the clean build are the fallback evidence.

- [ ] **Step 8: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/package.json emcip-admin-ui/src/main/frontend/package-lock.json emcip-admin-ui/src/main/frontend/src
git commit -m "feat(admin-ui): migrate react-router-dom@6 → react-router@8 (RT2-015)"
```

---

### Task 3: Bump test/build toolchain + drive `npm audit` to 0

Clears the remaining advisories (vite / esbuild / @vitest/mocker / vite-node / vitest / postcss). `vite` is not a direct dependency today; add it explicitly so the `dev`/`build` scripts pin it, and bump `@vitejs/plugin-react` (requires vite ^8) and `vitest` (accepts vite ^8) — the whole set lands on vite 8.

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/package.json`

**Interfaces:**
- Consumes: React 19 + react-router 8 from Tasks 1–2 (unchanged here).
- Produces: `npm audit` = 0; the acceptance gate for the whole migration.

- [ ] **Step 1: Edit `package.json` `devDependencies`**

Set/add:
```json
"@vitejs/plugin-react": "^6.0.5",
"vite": "^8.2.0",
"vitest": "^4.1.10",
```
Leave `jsdom`, `@testing-library/jest-dom`, `@testing-library/user-event`, and the Task-1 `@testing-library/react` line unchanged.

- [ ] **Step 2: Clean reinstall to fully re-resolve transitives**

Run: `rm -rf node_modules package-lock.json && npm install`
Rationale: a fresh resolve is what pulls the fixed `postcss`/`esbuild` transitives that a delta install can leave pinned to the old vulnerable versions.

- [ ] **Step 3: Audit gate**

Run: `npm audit`
Expected: `found 0 vulnerabilities`.
If anything remains, run `npm audit` and inspect: if it is a non-breaking transitive (e.g. a stray `postcss`), run `npm audit fix` (NOT `--force`) and re-run `npm audit` to confirm 0. Do not accept any residual vulnerability.

- [ ] **Step 4: Run the full test suite on Vitest 4**

Run: `npm test`
Expected: all 22 suites pass. The `test` block lives in `vite.config.js` (`environment: 'jsdom'`, `setupFiles: ['./src/test-setup.js']`, `globals: true`) and is Vitest-4 compatible as written. If Vitest 4 surfaces a config or API change (e.g. a renamed option), adjust `vite.config.js` — never weaken a test. Fix migration breakage only.

- [ ] **Step 5: Build on Vite 8**

Run: `npm run build`
Expected: build succeeds, output to `../resources/static`.

- [ ] **Step 6: Final combined gate**

Run: `npm run build && npm test && npm audit`
Expected: build OK, all suites green, `found 0 vulnerabilities` — all three in one pass.

- [ ] **Step 7: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/package.json emcip-admin-ui/src/main/frontend/package-lock.json
git commit -m "feat(admin-ui): bump vitest 4 / vite 8 / plugin-react 6 — npm audit clean (RT2-015)"
```

---

### Task 4: Mark RT2-015 delivered in trackers

Record that RT2-015 shipped — expanded from a plain `npm audit fix` into the full React 19 + react-router v8 upgrade — and note that P2.7's remaining scope is now just the U-NEW-1/2/3 hygiene edits.

**Files:**
- Modify: `docs/superpowers/BACKLOG.md` (RT2-015 row, line ~53)
- Modify: `documentation/ROADMAP.md` (P2.7 row, line ~139)

**Interfaces:**
- Consumes: the merged migration (audit = 0).
- Produces: docs consistent with the delivered state.

- [ ] **Step 1: Update `docs/superpowers/BACKLOG.md`**

Change the RT2-015 row status from `⏳` to `✅` and broaden its description to reflect the actual delivery. From:
```
| RT2-015 | `npm audit fix` (esbuild/vite/vitest) | MEDIUM | P2.7 | XS | ⏳ |
```
to:
```
| RT2-015 | React 19 + react-router v8 upgrade → npm audit 0 (esbuild/vite/vitest/react-router) | MEDIUM | P2.7 | S | ✅ |
```
Leave the U-NEW-1/2/3 row (`⏳ **next**`) as-is — it is still the remaining P2.7 work.

- [ ] **Step 2: Update `documentation/ROADMAP.md`**

In the P2.7 row (line ~139), mark RT2-015 as delivered and scope the remaining P2.7 work to U-NEW-1/2/3. Change the trailing status/label so RT2-015 no longer reads as pending — e.g. append to the row note: "RT2-015 ✅ delivered (React 19 + react-router v8, audit 0); P2.7 remaining = U-NEW-1/2/3." Keep the row's overall `⏳ **next**` state since the U-NEW hygiene items remain.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/BACKLOG.md documentation/ROADMAP.md
git commit -m "docs(p2.7): mark RT2-015 delivered (React 19 + react-router v8 upgrade)"
```

---

## Self-Review

**Spec coverage:**
- Bump react/react-dom → 19 → Task 1. ✅
- Swap react-router-dom → react-router@8 + rewrite 10 import sites → Task 2 (all 10 files enumerated). ✅
- testing-library → 16.3.2 → Task 1 Step 1. ✅
- Clear entire audit (vitest 4 / vite 8 / plugin-react 6 / postcss) → Task 3, gated at Step 3/6. ✅
- Verification gate (build clean, tests green, audit 0, routing smoke) → Task 2 Step 7 (routing smoke) + Task 3 Step 6 (combined build/test/audit gate). ✅
- Absorb RT2-015 / P2.7 shrinks to U-NEW → Task 4. ✅
- Out of scope (no new React 19 features, no U-NEW edits) → Global Constraints. ✅

**Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every edit is a concrete diff or exact version string. ✅

**Type/name consistency:** Version strings match the Global Constraints floors across all tasks (`^19.2.8`, `^8.3.0`, `^16.3.2`, `^4.1.10`, `^8.2.0`, `^6.0.5`). The 10 import-site paths in Task 2 match the grep-verified source. ✅

**Ordering note:** Each task ends green. After T1: React 19, tests/build green, router advisories still open. After T2: router 8, router advisories cleared, tests/build green. After T3: audit = 0. T4 is docs-only.
