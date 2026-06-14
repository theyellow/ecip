# SC6b — Audit-Log Page: Filters + Pagination

**Date**: 2026-06-14
**Status**: Draft
**Addresses**: SC6b (backlog), same UX pattern as Decisions page (PR #117)

---

## Goal

Bring the AuditLog page to feature parity with the Decisions page filter/pagination UX, using operational time presets suited to audit data (minutes/hours instead of days/months).

## Current State

| Layer | Has | Missing |
|-------|-----|---------|
| audit-service | `page`, `size`, `eventType`, `from`, `to`, tenant-scoped queries, `PageResponse` | Nothing |
| admin-api AuditServiceClient | `page`, `size`, `eventType` | `from`, `to` params |
| admin-api AuditController | `page`, `size`, `eventType` | `from`, `to` params |
| admin-ui API (`auditLog.js`) | `page`, `size`, `eventType` | `from`, `to` params |
| admin-ui AuditLog page | Event type filter, page size selector, detail modal | Pagination controls, time range filters, loading state |

## Design

### 1. admin-api — AuditServiceClient

Add `from` and `to` as optional `Instant` query parameters to `listEvents()`:

```java
public Mono<JsonNode> listEvents(int page, int size, String eventType, Instant from, Instant to)
```

Append `&from={iso}&to={iso}` to the WebClient URI when non-null. Audit-service already supports these params.

### 2. admin-api — AuditController

Add `from` and `to` as optional `@RequestParam` on `GET /api/audit/events`:

```java
@GetMapping("/events")
public Mono<JsonNode> listEvents(
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "50") int size,
    @RequestParam(required = false) String eventType,
    @RequestParam(required = false) Instant from,
    @RequestParam(required = false) Instant to)
```

Pass through to `AuditServiceClient.listEvents(page, size, eventType, from, to)`.

### 3. admin-ui — API layer (`auditLog.js`)

Update `list()` to accept and pass `from`/`to`:

```js
list(page, size, eventType, from, to)
```

Append `&from={iso}&to={iso}` to the query string when provided.

### 4. admin-ui — AuditLog page

#### Unified filter state

Replace scattered `useState` calls with a single `filters` object (same pattern as Decisions):

```js
const [filters, setFilters] = useState({
  page: 0, size: 50, eventType: '',
  timePreset: '24h', customFrom: '', customTo: ''
})
```

Default preset is `24h` (last 24 hours) — the most common operational view.

#### Time presets (operational)

| Label | Value | Duration |
|-------|-------|----------|
| Last 10 min | `10m` | 10 minutes |
| Last hour | `1h` | 1 hour |
| Last 8 hours | `8h` | 8 hours |
| Last 24 hours | `24h` | 24 hours |
| Last 48 hours | `48h` | 48 hours |
| Last 72 hours | `72h` | 72 hours |
| Custom | `custom` | User picks from/to |
| All time | `all` | No time filter |

When a preset is selected, compute `from = now - duration` and `to = now` at fetch time (not at selection time, so refreshes use fresh timestamps).

#### Custom date/time picker

When `timePreset === 'custom'`, show two `<input type="datetime-local">` fields for from/to. Convert to ISO-8601 UTC before sending to the API.

#### Pagination controls

Same as Decisions page:
- **Prev** / **Next** buttons
- Page indicator: `Page {page + 1} of {Math.ceil(total / size)}`
- Prev disabled when `page === 0`
- Next disabled when `(page + 1) * size >= total`
- Changing any filter resets page to 0

#### Loading state

Show "Loading..." in the table body while fetching (same as Decisions).

#### Event type filter

Already exists — keep as-is but wire through the unified `filters` object.

#### Action filter

Keep as client-side post-filter within the loaded page. Action is not a separate DB column — it's part of the event payload. Acceptable since it filters within the current page.

#### Page size selector

Already exists — keep as-is but wire through `filters` object. Options: 10, 25, 50, 100, 200.

## Filter row layout

```
[ Time: ▼ Last 24 hours ] [ Event Type: ▼ All ] [ Action: ▼ All ] [ Page Size: ▼ 50 ]
[ From: [datetime-local] ] [ To: [datetime-local] ]  ← only visible when Custom selected
```

## Not in scope

- No backend changes to audit-service (already complete)
- No new DB columns or indexes
- No tenant filter dropdown (tenant comes from auth context)
- No export/download feature
- No changes to the detail modal (already works well)

## Affected files

| File | Change |
|------|--------|
| `emcip-admin-api/.../client/AuditServiceClient.java` | Add `from`/`to` params |
| `emcip-admin-api/.../controller/AuditController.java` | Add `from`/`to` params |
| `emcip-admin-ui/.../api/auditLog.js` | Add `from`/`to` to `list()` |
| `emcip-admin-ui/.../pages/AuditLog/AuditLog.jsx` | Filters, pagination, loading |
| `emcip-admin-ui/.../pages/AuditLog/AuditLog.module.css` | Filter row styling (if needed) |
