# SC6b — Audit-Log Filters + Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add operational time-range filters, working pagination, and loading state to the AuditLog page — matching the Decisions page UX pattern.

**Architecture:** The audit-service backend already supports `page`, `size`, `eventType`, `from`, `to` filtering. This work threads `from`/`to` through admin-api (passthrough gateway) and builds the filter/pagination UI in admin-ui. No new backend endpoints or DB changes needed.

**Tech Stack:** Java 21 / Spring Boot 4 / WebFlux (admin-api), React + CSS Modules (admin-ui)

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java` | Modify | Add `from`/`to` Instant params to `listEvents()` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuditController.java` | Modify | Add `from`/`to` request params, pass to client |
| `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/AuditControllerTest.java` | Modify | Add test for `from`/`to` param forwarding |
| `emcip-admin-ui/src/main/frontend/src/api/auditLog.js` | Modify | Add `from`/`to` to `list()` call |
| `emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.jsx` | Modify | Unified filters, time presets, pagination, loading |
| `emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.module.css` | Modify | Add filter row, pagination, select, input styles |

---

### Task 1: admin-api — Add `from`/`to` to AuditServiceClient and AuditController

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuditController.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/AuditControllerTest.java`

- [ ] **Step 1: Update the test to verify `from`/`to` forwarding**

Open `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/AuditControllerTest.java` and add a new test method after the existing one:

```java
@Test
void getEvents_forwardsFromAndToParams() {
    ObjectNode page = JsonNodeFactory.instance.objectNode();
    page.putArray("items");
    page.put("total", 0L);
    page.put("page", 0);
    page.put("size", 50);
    Instant from = Instant.parse("2026-06-14T00:00:00Z");
    Instant to = Instant.parse("2026-06-14T23:59:59Z");
    when(auditServiceClient.listEvents(0, 50, null, from, to)).thenReturn(Mono.just(page));

    webTestClient
            .get()
            .uri("/api/audit/events?from=2026-06-14T00:00:00Z&to=2026-06-14T23:59:59Z")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.total")
            .isEqualTo(0);
}
```

Add the import at the top of the file:
```java
import java.time.Instant;
```

Also update the existing test's mock to match the new 5-param signature:
```java
when(auditServiceClient.listEvents(0, 50, null, null, null)).thenReturn(Mono.just(page));
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=AuditControllerTest -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20`
Expected: Compilation error — `listEvents` still has 3-param signature.

- [ ] **Step 3: Update AuditServiceClient to accept `from`/`to`**

Replace the `listEvents` method in `emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java`:

```java
public Mono<JsonNode> listEvents(int page, int size, String eventType, Instant from, Instant to) {
    return Mono.deferContextual(
            ctx -> {
                String tenantId = ReactorTenantContext.getTenantId(ctx);
                var spec =
                        webClient
                                .get()
                                .uri(
                                        uriBuilder -> {
                                            uriBuilder
                                                    .path("/api/audit/events")
                                                    .queryParam("page", page)
                                                    .queryParam("size", size);
                                            if (eventType != null && !eventType.isBlank()) {
                                                uriBuilder.queryParam("eventType", eventType);
                                            }
                                            if (from != null) {
                                                uriBuilder.queryParam("from", from.toString());
                                            }
                                            if (to != null) {
                                                uriBuilder.queryParam("to", to.toString());
                                            }
                                            return uriBuilder.build();
                                        });
                return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
            });
}
```

Add the import:
```java
import java.time.Instant;
```

- [ ] **Step 4: Update AuditController to accept and forward `from`/`to`**

Replace the `getEvents` method in `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuditController.java`:

```java
@Operation(summary = "List recent audit events, optionally filtered by type and time range")
@GetMapping("/events")
public Mono<JsonNode> getEvents(
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "50") int size,
        @RequestParam(name = "eventType", required = false) String eventType,
        @RequestParam(name = "from", required = false) Instant from,
        @RequestParam(name = "to", required = false) Instant to) {
    return auditServiceClient.listEvents(page, Math.min(size, 200), eventType, from, to);
}
```

Add the import:
```java
import java.time.Instant;
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=AuditControllerTest -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20`
Expected: 2 tests PASS.

- [ ] **Step 6: Run Spotless**

Run: `mvn spotless:apply -pl emcip-admin-api -q`

- [ ] **Step 7: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuditController.java emcip-admin-api/src/test/java/io/emcip/admin/api/controller/AuditControllerTest.java
git commit -m "feat(admin-api): add from/to time-range params to audit events endpoint"
```

---

### Task 2: admin-ui — Update API layer to pass `from`/`to`

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/api/auditLog.js`

- [ ] **Step 1: Update `auditLog.js` to accept and pass `from`/`to`**

Replace the full contents of `emcip-admin-ui/src/main/frontend/src/api/auditLog.js`:

```js
export function auditLogApi(request) {
  return {
    list: (page = 0, size = 50, eventType = '', from = null, to = null) => {
      const params = new URLSearchParams({ page, size })
      if (eventType) params.set('eventType', eventType)
      if (from) params.set('from', from)
      if (to) params.set('to', to)
      return request(`/api/audit/events?${params}`)
    },
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/api/auditLog.js
git commit -m "feat(admin-ui): add from/to params to audit log API"
```

---

### Task 3: admin-ui — Rewrite AuditLog page with filters, pagination, loading

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.module.css`

- [ ] **Step 1: Add styles to AuditLog.module.css**

Append the following to the end of `emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.module.css`:

```css
/* Page header */
.pageHeader {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--sp-5);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--rule);
}

.systemId {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.10em;
  text-transform: uppercase;
  color: var(--fg-3);
  margin-top: 6px;
}

.filters {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}

.select {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

/* Filter row */
.filterRow {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: var(--sp-4);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--rule);
}

.filterInput {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  outline: none;
  min-width: 120px;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.filterInput:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

/* Table */
.tableWrapper {
  overflow-x: auto;
}

.table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 0;
}

.table th {
  padding: 10px 16px;
  text-align: left;
  font-family: var(--font-body);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fg-3);
  background: var(--accent-soft);
  border-bottom: 1px solid var(--rule);
}

.table td {
  padding: 10px 16px;
  border-bottom: 1px solid var(--rule);
  font-size: 13px;
  color: var(--fg-1);
}

.table tr:hover td {
  background: var(--accent-soft);
}

.clickableRow {
  cursor: pointer;
}

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

/* Alert banner */
.alertBanner {
  color: var(--signal-stop-fg);
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.25);
  padding: 8px 12px;
  font-family: var(--font-mono);
  font-size: 12px;
  margin-bottom: var(--sp-3);
}

/* Pagination bar */
.pagination {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  justify-content: flex-end;
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--rule);
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}
```

- [ ] **Step 2: Rewrite AuditLog.jsx with unified filters, pagination, and loading**

Replace the full contents of `emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { auditLogApi } from '../../api/auditLog'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import styles from './AuditLog.module.css'

const EVENT_TYPES = ['', 'MESSAGE_RECEIVED', 'MESSAGE_CLASSIFIED', 'POLICY_DECISION', 'MODERATION_ACTION']
const OUTCOME_VARIANT = { OK: 'green', BLOCK: 'red' }

const TIME_PRESETS = [
  { value: '24h', label: 'Last 24 hours' },
  { value: '10m', label: 'Last 10 min' },
  { value: '1h', label: 'Last hour' },
  { value: '8h', label: 'Last 8 hours' },
  { value: '48h', label: 'Last 48 hours' },
  { value: '72h', label: 'Last 72 hours' },
  { value: 'custom', label: 'Custom range\u2026' },
  { value: '', label: 'All time' },
]

const PRESET_MS = {
  '10m': 10 * 60 * 1000,
  '1h': 60 * 60 * 1000,
  '8h': 8 * 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '48h': 48 * 60 * 60 * 1000,
  '72h': 72 * 60 * 60 * 1000,
}

function presetToRange(preset) {
  const ms = PRESET_MS[preset]
  if (ms) {
    return { from: new Date(Date.now() - ms).toISOString(), to: null }
  }
  return { from: null, to: null }
}

function CopyButton({ text }) {
  const [copied, setCopied] = useState(false)
  const handle = () => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }
  return (
    <button className={`${styles.copyBtn}${copied ? ' ' + styles.copied : ''}`} onClick={handle}>
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}

function parseDetails(raw) {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) } catch { return raw }
}

function DetailsModal({ event, onClose }) {
  const parsedDetails = parseDetails(event.details)
  const prettyDetails = parsedDetails != null ? JSON.stringify(parsedDetails, null, 2) : null
  const rawEvent = JSON.stringify(event, null, 2)

  return (
    <Modal title="Audit Event Details" onClose={onClose}>
      <div className={styles.metaGrid}>
        <span className={styles.metaLabel}>Timestamp</span>
        <span className={styles.metaValue}>{event.createdAt ? new Date(event.createdAt).toLocaleString() : '\u2014'}</span>
        <span className={styles.metaLabel}>Event Type</span>
        <span className={styles.metaValue}>{event.eventType ?? '\u2014'}</span>
        <span className={styles.metaLabel}>Source</span>
        <span className={styles.metaValue}>{event.sourceService ?? '\u2014'}</span>
        <span className={styles.metaLabel}>Action</span>
        <span className={styles.metaValue}>{event.action ?? '\u2014'}</span>
        <span className={styles.metaLabel}>Resource</span>
        <span className={styles.metaValue}>{event.resourceId ?? '\u2014'}</span>
        <span className={styles.metaLabel}>Outcome</span>
        <span className={styles.metaValue}>{event.outcome ?? '\u2014'}</span>
      </div>

      {prettyDetails != null && (
        <>
          <SectionLabel aside={<CopyButton text={prettyDetails} />}>Details</SectionLabel>
          <div className={styles.jsonBlock}>
            <pre>{prettyDetails}</pre>
          </div>
        </>
      )}

      <SectionLabel aside={<CopyButton text={rawEvent} />}>Raw Event</SectionLabel>
      <div className={styles.jsonBlock}>
        <pre>{rawEvent}</pre>
      </div>
    </Modal>
  )
}

export function AuditLog() {
  const api = auditLogApi(useAuthRequest())
  const [events, setEvents] = useState([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState(null)

  const [filters, setFilters] = useState({
    page: 0,
    size: 50,
    eventType: '',
    actionFilter: '',
    timePreset: '24h',
    customFrom: '',
    customTo: '',
  })

  const setFilter = (key, value) =>
    setFilters(f => ({ ...f, [key]: value, ...(key !== 'page' ? { page: 0 } : {}) }))

  useEffect(() => {
    const { page, size, eventType, timePreset, customFrom, customTo } = filters
    const computedRange =
      timePreset === 'custom'
        ? {
            from: customFrom ? new Date(customFrom).toISOString() : null,
            to: customTo ? new Date(customTo).toISOString() : null,
          }
        : presetToRange(timePreset)

    setLoading(true)
    api
      .list(page, size, eventType, computedRange.from, computedRange.to)
      .then(data => {
        setEvents(data?.items ?? [])
        setTotal(data?.total ?? 0)
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [filters])

  const displayEvents = filters.actionFilter
    ? events.filter(e => e.action === filters.actionFilter)
    : events

  const totalPages = Math.max(1, Math.ceil(total / filters.size))

  return (
    <>
      <div className={styles.pageHeader}>
        <div>
          <h2>Audit Log</h2>
          <div className={styles.systemId}>{'\u25CE'} audit-service {'\u00b7'} {total} events</div>
        </div>
        <div className={styles.filters}>
          <select value={filters.eventType} onChange={e => setFilter('eventType', e.target.value)} className={styles.select}>
            {EVENT_TYPES.map(t => <option key={t} value={t}>{t || 'All types'}</option>)}
          </select>
          <select value={String(filters.size)} onChange={e => setFilter('size', Number(e.target.value))} className={styles.select}>
            {[10, 25, 50, 100, 200].map(n => <option key={n} value={String(n)}>{n}</option>)}
          </select>
        </div>
      </div>

      <div className={styles.filterRow}>
        <select value={filters.timePreset} onChange={e => setFilter('timePreset', e.target.value)} className={styles.select}>
          {TIME_PRESETS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        {filters.timePreset === 'custom' && <>
          <input type="datetime-local" className={styles.filterInput} value={filters.customFrom} onChange={e => setFilter('customFrom', e.target.value)} />
          <input type="datetime-local" className={styles.filterInput} value={filters.customTo} onChange={e => setFilter('customTo', e.target.value)} />
        </>}
        <select value={filters.actionFilter} onChange={e => setFilter('actionFilter', e.target.value)} className={styles.select}>
          <option value="">All actions</option>
          <option value="CLASSIFY">CLASSIFY</option>
          <option value="POLICY_DECISION">POLICY_DECISION</option>
          <option value="MODERATION_ACTION">MODERATION_ACTION</option>
          <option value="SEND_MESSAGE">SEND_MESSAGE</option>
        </select>
      </div>

      {error && (
        <p role="alert" className={styles.alertBanner}>{error}</p>
      )}

      <div className={styles.tableWrapper}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Event Type</th>
              <th>Action</th>
              <th>Resource</th>
              <th>Outcome</th>
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--fg-3)', padding: 'var(--sp-5)' }}>Loading{'\u2026'}</td></tr>
            )}
            {!loading && displayEvents.length === 0 && !error && (
              <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--fg-3)', padding: 'var(--sp-5)' }}>No audit events found</td></tr>
            )}
            {!loading && displayEvents.map((e, i) => (
              <tr key={e.eventId ?? i} className={styles.clickableRow} onClick={() => setSelected(e)}>
                <td className={styles.mono}>{e.createdAt ? new Date(e.createdAt).toLocaleString() : '\u2014'}</td>
                <td>{e.eventType ?? '\u2014'}</td>
                <td>{e.action ?? '\u2014'}</td>
                <td className={styles.mono}>{e.resourceId ? e.resourceId.slice(0, 8) + '\u2026' : '\u2014'}</td>
                <td>{e.outcome ? <Badge variant={OUTCOME_VARIANT[e.outcome] ?? 'gray'}>{e.outcome}</Badge> : '\u2014'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className={styles.pagination}>
        <Button variant="secondary" disabled={filters.page === 0} onClick={() => setFilters(f => ({ ...f, page: f.page - 1 }))}>
          {'\u2190'} Prev
        </Button>
        <span>Page {filters.page + 1} of {totalPages} {'\u00a0\u00b7\u00a0'} {total} total</span>
        <Button variant="secondary" disabled={filters.page + 1 >= totalPages} onClick={() => setFilters(f => ({ ...f, page: f.page + 1 }))}>
          Next {'\u2192'}
        </Button>
      </div>

      {selected && <DetailsModal event={selected} onClose={() => setSelected(null)} />}
    </>
  )
}
```

- [ ] **Step 3: Verify the UI builds**

Run: `cd emcip-admin-ui/src/main/frontend && npm run build 2>&1 | tail -10`
Expected: Build succeeds with no errors.

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.jsx emcip-admin-ui/src/main/frontend/src/pages/AuditLog/AuditLog.module.css
git commit -m "feat(admin-ui): audit log — operational time filters, pagination, loading state"
```

---

### Task 4: Spotless + Final Verification

**Files:**
- All modified files from Tasks 1-3

- [ ] **Step 1: Run Spotless across affected modules**

Run: `mvn spotless:apply -pl emcip-admin-api -q`

If any files changed:
```bash
git add -A && git commit -m "style: apply spotless"
```

- [ ] **Step 2: Run admin-api tests**

Run: `cd emcip-admin-api && mvn test -pl . -q 2>&1 | tail -20`
Expected: All tests PASS.

- [ ] **Step 3: Run admin-ui build**

Run: `cd emcip-admin-ui/src/main/frontend && npm run build 2>&1 | tail -10`
Expected: Build succeeds.

- [ ] **Step 4: Final commit if any remaining changes**

```bash
git status
# If clean, nothing to do. If files changed, commit with appropriate message.
```
