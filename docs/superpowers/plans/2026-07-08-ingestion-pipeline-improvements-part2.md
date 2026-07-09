# Ingestion Pipeline Improvements — Part 2: Frontend

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a global toast notification system and rework the ingestion dialog for background processing with model warm-up.

**Architecture:** New `ToastProvider` context at the App root, `useToast()` hook for any component. Ingestion modal triggers warm-up on open, submits and closes immediately, fires toasts for lifecycle events. Jobs table auto-polls and fires completion/failure toasts.

**Tech Stack:** React 18, CSS Modules, EMCIP v2 design tokens (brass-and-ink, Cinzel display, no emoji, no rounded corners on data surfaces).

## Global Constraints

- Frontend uses CSS Modules with semantic tokens from `variables.css`.
- No emoji, no icon libraries.
- Design system: EMCIP v2 — brass-and-ink, Cinzel display font for labels, Inter body font.
- No rounded corners on data surfaces.
- Signal tokens: `--signal-ok-*` (green), `--signal-stop-*` (red), `--signal-info-*` (blue), `--signal-warn-*` (yellow).
- Background: `--bg-card: rgba(12, 26, 40, 0.78)` with `backdrop-filter: blur(16px)`.
- Spacing: `--sp-1` (4px) through `--sp-9` (96px).
- `mvn spotless:apply` does not apply to frontend files but run it before commits if Java files changed.

---

### Task 6: Toast Notification System

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/components/Toast/ToastProvider.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/components/Toast/Toast.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/components/Toast/Toast.module.css`
- Create: `emcip-admin-ui/src/main/frontend/src/components/Toast/useToast.js`
- Modify: `emcip-admin-ui/src/main/frontend/src/App.jsx`

**Interfaces:**
- Consumes: Design tokens from `variables.css`, React context API.
- Produces: `<ToastProvider>` wrapping the app, `useToast()` hook returning `{ addToast(type, message, options?) }` — consumed by IngestionModal (Task 8), KnowledgePage (Task 9).

- [ ] **Step 1: Create `useToast.js` hook**

Create `emcip-admin-ui/src/main/frontend/src/components/Toast/useToast.js`:

```javascript
import { useContext } from 'react';
import { ToastContext } from './ToastProvider';

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return context;
}
```

- [ ] **Step 2: Create `Toast.module.css`**

Create `emcip-admin-ui/src/main/frontend/src/components/Toast/Toast.module.css`:

```css
.container {
  position: fixed;
  bottom: var(--sp-5);
  right: var(--sp-5);
  z-index: 9999;
  display: flex;
  flex-direction: column-reverse;
  gap: var(--sp-2);
  pointer-events: none;
}

.toast {
  pointer-events: auto;
  display: flex;
  align-items: flex-start;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid var(--border);
  background: var(--bg-card);
  backdrop-filter: blur(16px);
  min-width: 280px;
  max-width: 420px;
  animation: fadeIn 150ms ease-out;
}

.body {
  flex: 1;
  min-width: 0;
}

.typeLabel {
  font-family: var(--font-display);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-bottom: var(--sp-1);
}

.message {
  font-family: var(--font-body);
  font-size: 13px;
  line-height: 1.4;
  color: var(--fg-1);
  word-break: break-word;
}

.close {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 0;
  flex-shrink: 0;
}

/* Type variants */
.success .typeLabel { color: var(--signal-ok-fg); }
.success .close { color: var(--signal-ok-fg); }
.success { border-color: var(--signal-ok-fg); }

.error .typeLabel { color: var(--signal-stop-fg); }
.error .close { color: var(--signal-stop-fg); }
.error { border-color: var(--signal-stop-fg); }

.info .typeLabel { color: var(--signal-info-fg); }
.info .close { color: var(--signal-info-fg); }
.info { border-color: var(--signal-info-fg); }

.warning .typeLabel { color: var(--signal-warn-fg); }
.warning .close { color: var(--signal-warn-fg); }
.warning { border-color: var(--signal-warn-fg); }

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
```

- [ ] **Step 3: Create `Toast.jsx` component**

Create `emcip-admin-ui/src/main/frontend/src/components/Toast/Toast.jsx`:

```jsx
import { useEffect } from 'react';
import styles from './Toast.module.css';

const TYPE_LABELS = {
  success: 'SUCCESS',
  error: 'ERROR',
  info: 'INFO',
  warning: 'WARNING',
};

const DEFAULT_DURATIONS = {
  success: 5000,
  error: 8000,
  info: 5000,
  warning: 5000,
};

export default function Toast({ id, type, message, duration, onDismiss }) {
  const effectiveDuration = duration ?? DEFAULT_DURATIONS[type] ?? 5000;

  useEffect(() => {
    const timer = setTimeout(() => onDismiss(id), effectiveDuration);
    return () => clearTimeout(timer);
  }, [id, effectiveDuration, onDismiss]);

  return (
    <div className={`${styles.toast} ${styles[type] || ''}`}>
      <div className={styles.body}>
        <div className={styles.typeLabel}>{TYPE_LABELS[type] || type}</div>
        <div className={styles.message}>{message}</div>
      </div>
      <button className={styles.close} onClick={() => onDismiss(id)}>
        &#x2715;
      </button>
    </div>
  );
}
```

- [ ] **Step 4: Create `ToastProvider.jsx`**

Create `emcip-admin-ui/src/main/frontend/src/components/Toast/ToastProvider.jsx`:

```jsx
import { createContext, useCallback, useState } from 'react';
import Toast from './Toast';
import styles from './Toast.module.css';

export const ToastContext = createContext(null);

const MAX_VISIBLE = 5;
let nextId = 0;

export default function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);

  const addToast = useCallback((type, message, options = {}) => {
    const id = ++nextId;
    const toast = { id, type, message, duration: options.duration };
    setToasts(prev => {
      const next = [...prev, toast];
      return next.length > MAX_VISIBLE ? next.slice(-MAX_VISIBLE) : next;
    });
    return id;
  }, []);

  const dismissToast = useCallback(id => {
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={{ addToast }}>
      {children}
      <div className={styles.container}>
        {toasts.map(toast => (
          <Toast key={toast.id} {...toast} onDismiss={dismissToast} />
        ))}
      </div>
    </ToastContext.Provider>
  );
}
```

- [ ] **Step 5: Wrap App with `ToastProvider`**

Modify `emcip-admin-ui/src/main/frontend/src/App.jsx`.

Add import:
```javascript
import ToastProvider from './components/Toast/ToastProvider';
```

Wrap the App's return value. The current structure is:
```jsx
<BrowserRouter>
  <ThemeProvider>
    <AuthProvider>
      <SpaceBackground>
        <AuthGate />
      </SpaceBackground>
    </AuthProvider>
  </ThemeProvider>
</BrowserRouter>
```

Add `<ToastProvider>` inside `<AuthProvider>`, wrapping `<SpaceBackground>`:
```jsx
<BrowserRouter>
  <ThemeProvider>
    <AuthProvider>
      <ToastProvider>
        <SpaceBackground>
          <AuthGate />
        </SpaceBackground>
      </ToastProvider>
    </AuthProvider>
  </ThemeProvider>
</BrowserRouter>
```

- [ ] **Step 6: Verify by running the dev server**

```bash
cd emcip-admin-ui/src/main/frontend && npm run build
```
Expected: Build succeeds with no errors.

- [ ] **Step 7: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/components/Toast/ToastProvider.jsx \
        emcip-admin-ui/src/main/frontend/src/components/Toast/Toast.jsx \
        emcip-admin-ui/src/main/frontend/src/components/Toast/Toast.module.css \
        emcip-admin-ui/src/main/frontend/src/components/Toast/useToast.js \
        emcip-admin-ui/src/main/frontend/src/App.jsx
git commit -m "feat(admin-ui): add global toast notification system"
```

---

### Task 7: Knowledge API — Warm-Up Method

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/api/knowledge.js`

**Interfaces:**
- Consumes: Admin API `POST /api/ai/warm-up` (from Part 1, Task 2). Authenticated `request()` function.
- Produces: `warmUp(taskTypes)` method on the knowledge API — consumed by IngestionModal (Task 8).

- [ ] **Step 1: Add `warmUp` method to `knowledge.js`**

Add after the `jobs()` method (around line 33) in `emcip-admin-ui/src/main/frontend/src/api/knowledge.js`:

```javascript
  warmUp: (taskTypes) =>
    request('/api/ai/warm-up', {
      method: 'POST',
      body: JSON.stringify({ taskTypes }),
    }),
```

- [ ] **Step 2: Verify build**

```bash
cd emcip-admin-ui/src/main/frontend && npm run build
```
Expected: Build succeeds.

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/api/knowledge.js
git commit -m "feat(admin-ui): add warm-up API method"
```

---

### Task 8: Background Ingestion — IngestionModal Rework

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.module.css`

**Interfaces:**
- Consumes: `api.warmUp(['EMBED', 'EXTRACT'])` (from Task 7), `api.ingestUrl()` / `api.ingestUpload()` (existing), `useToast().addToast()` (from Task 6), `onClose` and `onJobCreated` props.
- Produces: Reworked modal that warms up on open, submits and closes immediately, fires toasts.

- [ ] **Step 1: Rewrite `IngestionModal.jsx`**

Replace the entire file content of `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.jsx`:

```jsx
import { useCallback, useEffect, useState } from 'react';
import Modal from '../../components/Modal/Modal';
import Button from '../../components/Button/Button';
import SegmentedControl from '../../components/SegmentedControl/SegmentedControl';
import { useToast } from '../../components/Toast/useToast';
import styles from './IngestionModal.module.css';

const WARM_UP_TIMEOUT_MS = 15000;

export default function IngestionModal({ api, tenants, onClose, onJobCreated }) {
  const [mode, setMode] = useState('url');
  const [url, setUrl] = useState('');
  const [file, setFile] = useState(null);
  const [tenantId, setTenantId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [warmUpState, setWarmUpState] = useState('loading'); // loading | ready | failed
  const [warmUpLatency, setWarmUpLatency] = useState(null);
  const { addToast } = useToast();

  // Warm up models on mount
  useEffect(() => {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), WARM_UP_TIMEOUT_MS);

    api
      .warmUp(['EMBED', 'EXTRACT'])
      .then(data => {
        const results = data.results || {};
        const allReady = Object.values(results).every(r => r.ready);
        const maxLatency = Math.max(
          ...Object.values(results).map(r => r.latencyMs || 0)
        );
        if (allReady) {
          setWarmUpState('ready');
          setWarmUpLatency(maxLatency);
        } else {
          setWarmUpState('failed');
          addToast('warning', 'Model warm-up failed — ingestion may be slow');
        }
      })
      .catch(() => {
        setWarmUpState('failed');
        addToast('warning', 'Model warm-up failed — ingestion may be slow');
      })
      .finally(() => clearTimeout(timeout));

    return () => {
      controller.abort();
      clearTimeout(timeout);
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const canSubmit =
    warmUpState !== 'loading' &&
    !submitting &&
    (mode === 'url' ? url.trim() : file) &&
    tenantId;

  const handleSubmit = useCallback(async () => {
    setSubmitting(true);
    try {
      if (mode === 'url') {
        await api.ingestUrl(url, tenantId);
      } else {
        await api.ingestUpload(file, tenantId);
      }
      const sourceRef = mode === 'url' ? url : file.name;
      addToast('info', `Document submitted: ${sourceRef}`);
      onJobCreated();
      onClose();
    } catch (err) {
      addToast('error', `Submission failed: ${err.message || 'Unknown error'}`);
      setSubmitting(false);
    }
  }, [mode, url, file, tenantId, api, addToast, onJobCreated, onClose]);

  return (
    <Modal title="Add Document" onClose={onClose}>
      <div className={styles.form}>
        <SegmentedControl
          value={mode}
          onChange={setMode}
          options={[
            { value: 'url', label: 'URL' },
            { value: 'file', label: 'File' },
          ]}
        />

        {mode === 'url' ? (
          <input
            className={styles.input}
            type="url"
            placeholder="https://example.com/document.pdf"
            value={url}
            onChange={e => setUrl(e.target.value)}
          />
        ) : (
          <input
            className={styles.input}
            type="file"
            accept=".pdf,.txt,.html,.docx,.doc,.odt,.rtf"
            onChange={e => setFile(e.target.files?.[0] || null)}
          />
        )}

        <select
          className={styles.input}
          value={tenantId}
          onChange={e => setTenantId(e.target.value)}
        >
          <option value="">Select tenant...</option>
          {tenants.map(t => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>

        <div className={styles.warmUpStatus}>
          {warmUpState === 'loading' && (
            <span className={styles.warmUpLoading}>Preparing models...</span>
          )}
          {warmUpState === 'ready' && (
            <span className={styles.warmUpReady}>
              Models ready ({warmUpLatency}ms)
            </span>
          )}
        </div>
      </div>

      <div className={styles.footer}>
        <Button variant="secondary" onClick={onClose}>
          Cancel
        </Button>
        <Button
          variant="primary"
          onClick={handleSubmit}
          disabled={!canSubmit}
        >
          {submitting ? 'Submitting...' : 'Submit'}
        </Button>
      </div>
    </Modal>
  );
}
```

- [ ] **Step 2: Update `IngestionModal.module.css`**

Read the current CSS file first, then update it. The new CSS should include:

```css
.form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  padding: var(--sp-4) 0;
}

.input {
  width: 100%;
  padding: var(--sp-2) var(--sp-3);
  background: var(--bg-input);
  border: 1px solid var(--border);
  color: var(--fg-1);
  font-family: var(--font-body);
  font-size: 14px;
}

.input::placeholder {
  color: var(--fg-3);
}

.footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-3);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--border);
}

.warmUpStatus {
  min-height: 20px;
  font-size: 12px;
  font-family: var(--font-body);
}

.warmUpLoading {
  color: var(--signal-info-fg);
}

.warmUpReady {
  color: var(--signal-ok-fg);
}
```

- [ ] **Step 3: Verify build**

```bash
cd emcip-admin-ui/src/main/frontend && npm run build
```
Expected: Build succeeds.

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.module.css
git commit -m "feat(admin-ui): rework ingestion modal for background processing with warm-up"
```

---

### Task 9: Ingestion Jobs — Auto-Polling with Toast Notifications

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx`

**Interfaces:**
- Consumes: `api.jobs(page, 20)` (existing), `useToast().addToast()` (from Task 6).
- Produces: Auto-polling jobs table that fires completion/failure toasts when job status transitions.

- [ ] **Step 1: Add auto-polling and toast notifications to `KnowledgePage.jsx`**

Add import at the top of the file:
```javascript
import { useToast } from '../../components/Toast/useToast';
```

Inside the component function, add:
```javascript
const { addToast } = useToast();
```

Add a ref to track previously seen job statuses (for transition detection):
```javascript
const prevJobStatuses = useRef({});
```

Add import for `useRef`:
```javascript
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
```

Replace the existing `loadJobs` callback and its useEffect with:

```javascript
const loadJobs = useCallback(async () => {
  setJobsLoading(true);
  try {
    const data = await api.jobs(page, 20);
    const rows = (data.content || []).map(j => ({
      ...j,
      tenantId: tenants.find(t => t.id === j.tenantId)?.name || j.tenantId,
      createdAt: new Date(j.createdAt).toLocaleString(),
    }));

    // Detect status transitions and fire toasts
    const prev = prevJobStatuses.current;
    for (const job of data.content || []) {
      const oldStatus = prev[job.id];
      if (oldStatus && oldStatus !== job.status) {
        if (job.status === 'COMPLETED') {
          addToast(
            'success',
            `Ingestion complete: ${job.sourceRef} — ${job.chunkCount || 0} chunks`
          );
        } else if (job.status === 'FAILED') {
          addToast(
            'error',
            `Ingestion failed: ${job.sourceRef} — ${job.errorMessage || 'Unknown error'}`
          );
        }
      }
    }

    // Update tracked statuses
    const newStatuses = {};
    for (const job of data.content || []) {
      newStatuses[job.id] = job.status;
    }
    prevJobStatuses.current = newStatuses;

    setJobs(rows);
    setTotalPages(data.totalPages || 1);
  } catch {
    // silently handled — job list will show stale data
  } finally {
    setJobsLoading(false);
  }
}, [api, page, tenants, addToast]);
```

Add auto-polling useEffect:
```javascript
// Auto-poll when any visible job is QUEUED or RUNNING
useEffect(() => {
  if (activeTab !== 'jobs') return;

  const hasActiveJobs = jobs.some(
    j => j.status === 'QUEUED' || j.status === 'RUNNING'
  );
  if (!hasActiveJobs) return;

  const interval = setInterval(loadJobs, 5000);
  return () => clearInterval(interval);
}, [activeTab, jobs, loadJobs]);
```

Note: The existing useEffect that triggers `loadJobs()` on tab switch stays unchanged. The new polling useEffect runs alongside it.

There is one subtlety: the `jobs` array used in the polling check has display-mapped `tenantId` and `createdAt`. The status field should still be the raw status string. To ensure the raw status is available, add `rawStatus: j.status` to the mapped row:

```javascript
const rows = (data.content || []).map(j => ({
  ...j,
  rawStatus: j.status,
  tenantId: tenants.find(t => t.id === j.tenantId)?.name || j.tenantId,
  createdAt: new Date(j.createdAt).toLocaleString(),
}));
```

And update the polling check:
```javascript
const hasActiveJobs = jobs.some(
  j => j.rawStatus === 'QUEUED' || j.rawStatus === 'RUNNING'
);
```

- [ ] **Step 2: Verify build**

```bash
cd emcip-admin-ui/src/main/frontend && npm run build
```
Expected: Build succeeds.

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx
git commit -m "feat(admin-ui): auto-poll ingestion jobs with toast notifications"
```
