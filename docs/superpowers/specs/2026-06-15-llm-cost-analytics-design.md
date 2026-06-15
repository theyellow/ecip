# #7 — LLM Cost Analytics Dashboard

**Date**: 2026-06-15
**Status**: Draft
**Addresses**: Backlog #7 — LLM cost analytics dashboard

---

## Goal

Give operators visibility into LLM usage and costs via a dedicated Admin UI page. TENANT_ADMIN sees their own tenant's data; ADMIN sees all tenants (with cross-tenant dropdown).

## Current State

| Layer | Has | Missing |
|-------|-----|---------|
| DB | `model_cost_logs` table with full cost/token/latency tracking, tenant_id, indexes | Nothing — schema is complete |
| Repository | `calculateTotalCostForPeriod`, `calculateTotalTokensForModel`, finder queries | Aggregation by model, aggregation by day, totals with call count |
| Service | `CostTrackingService` with `getTotalCostForPeriod`, `getTotalTokensForModel` | Methods for the three new aggregation queries |
| Orchestrator API | `GET /api/costs/summary` (totalCostUsd only) | `/api/costs/totals`, `/api/costs/by-model`, `/api/costs/by-day` |
| Admin API | No cost proxy endpoints | `CostsProxyController` proxying to orchestrator |
| Permissions | `AI_CONFIG_READ`/`AI_CONFIG_WRITE` (ADMIN only) | `COSTS_READ` (ADMIN + TENANT_ADMIN) |
| UI | No cost display anywhere | Costs page with summary cards, bar chart, model breakdown table |

## Design

### 1. Repository — new aggregation queries

Add to `ModelCostLogRepository`:

**By-model aggregation:**
```java
@Query("""
    SELECT m.modelName AS modelName,
           COUNT(m) AS callCount,
           COALESCE(SUM(m.inputTokens), 0) AS inputTokens,
           COALESCE(SUM(m.outputTokens), 0) AS outputTokens,
           COALESCE(SUM(m.totalTokens), 0) AS totalTokens,
           COALESCE(SUM(m.totalCostUsd), 0.0) AS totalCostUsd,
           COALESCE(AVG(m.latencyMs), 0.0) AS avgLatencyMs
    FROM ModelCostLog m
    WHERE m.createdAt BETWEEN :start AND :end AND m.status = 'SUCCESS'
    GROUP BY m.modelName
    ORDER BY COUNT(m) DESC
    """)
List<Object[]> aggregateByModel(@Param("start") Instant start, @Param("end") Instant end);
```

**By-day aggregation:**
```java
@Query("""
    SELECT CAST(m.createdAt AS LocalDate) AS date,
           COALESCE(SUM(m.totalCostUsd), 0.0) AS totalCostUsd,
           COUNT(m) AS callCount,
           COALESCE(SUM(m.totalTokens), 0) AS totalTokens
    FROM ModelCostLog m
    WHERE m.createdAt BETWEEN :start AND :end AND m.status = 'SUCCESS'
    GROUP BY CAST(m.createdAt AS LocalDate)
    ORDER BY CAST(m.createdAt AS LocalDate) ASC
    """)
List<Object[]> aggregateByDay(@Param("start") Instant start, @Param("end") Instant end);
```

**Totals:**
```java
@Query("""
    SELECT COALESCE(SUM(m.totalCostUsd), 0.0) AS totalCostUsd,
           COALESCE(SUM(m.totalTokens), 0) AS totalTokens,
           COUNT(m) AS callCount,
           COALESCE(AVG(m.latencyMs), 0.0) AS avgLatencyMs,
           SUM(CASE WHEN m.status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount,
           SUM(CASE WHEN m.status = 'FAILED' THEN 1 ELSE 0 END) AS failureCount
    FROM ModelCostLog m
    WHERE m.createdAt BETWEEN :start AND :end
    """)
Object[] calculateTotals(@Param("start") Instant start, @Param("end") Instant end);
```

All queries respect the Hibernate `tenantFilter` already defined on `ModelCostLog`.

### 2. CostTrackingService — new methods

```java
public Map<String, Object> getTotals(Instant start, Instant end)
public List<Map<String, Object>> getByModel(Instant start, Instant end)
public List<Map<String, Object>> getByDay(Instant start, Instant end)
```

Each method calls the corresponding repository query and maps `Object[]` results into named `Map<String, Object>` entries. The service enables the tenant filter on the EntityManager before querying (same pattern as other tenant-filtered services in the orchestrator).

### 3. OrchestratorController — new endpoints

All under `/api/costs`, all accept `from` and `to` as ISO 8601 `Instant` query parameters.

**`GET /api/costs/totals?from=&to=`**
```json
{
  "totalCostUsd": 0.0,
  "totalTokens": 120000,
  "callCount": 210,
  "avgLatencyMs": 795.0,
  "successCount": 205,
  "failureCount": 5,
  "from": "2026-06-01T00:00:00Z",
  "to": "2026-06-15T23:59:59Z"
}
```

**`GET /api/costs/by-model?from=&to=`**
```json
[
  {
    "modelName": "qwen3-30b-a3b",
    "callCount": 142,
    "inputTokens": 60000,
    "outputTokens": 25000,
    "totalTokens": 85000,
    "totalCostUsd": 0.0,
    "avgLatencyMs": 812.0
  }
]
```

**`GET /api/costs/by-day?from=&to=`**
```json
[
  { "date": "2026-06-14", "totalCostUsd": 0.0, "callCount": 47, "totalTokens": 28000 },
  { "date": "2026-06-15", "totalCostUsd": 0.0, "callCount": 63, "totalTokens": 35000 }
]
```

The existing `GET /api/costs/summary` stays unchanged (backwards compatible).

### 4. Admin API — CostsProxyController

New controller at `/api/costs` following the `AIProxyController` pattern:

```java
@RestController
@RequestMapping("/api/costs")
@PreAuthorize("hasAuthority('COSTS_READ')")
@Tag(name = "Costs", description = "Proxy to llm-orchestrator cost analytics")
public class CostsProxyController {
    // GET /api/costs/totals?from=&to=  → orchestrator /api/costs/totals?from=&to=
    // GET /api/costs/by-model?from=&to= → orchestrator /api/costs/by-model?from=&to=
    // GET /api/costs/by-day?from=&to=   → orchestrator /api/costs/by-day?from=&to=
}
```

Three read-only GET endpoints, raw `String` passthrough with circuit breaker + retry (same resilience stack as other proxy controllers). Query params (`from`, `to`) are forwarded as-is.

### 5. Permissions

Add `COSTS_READ` to both roles in `permissions.js`:

```js
ADMIN: [...existing, 'COSTS_READ'],
TENANT_ADMIN: [...existing, 'COSTS_READ'],
```

This is a client-side nav guard. Server-side, the orchestrator's Hibernate tenant filter ensures TENANT_ADMIN only sees their own data. ADMIN sees all tenants or can filter via the existing tenant dropdown (which sets the `X-Tenant-Id` header).

### 6. UI — API layer (costs.js)

```js
export function costsApi(request) {
  return {
    totals: (from, to) => request(`/api/costs/totals?from=${from}&to=${to}`),
    byModel: (from, to) => request(`/api/costs/by-model?from=${from}&to=${to}`),
    byDay: (from, to) => request(`/api/costs/by-day?from=${from}&to=${to}`),
  }
}
```

### 7. UI — Costs page

**Route:** `/costs`
**Sidebar:** `{ to: '/costs', label: 'LLM Costs', icon: '\u229B', permission: 'COSTS_READ' }` — inserted after AI Config in the NAV array.

**Component:** `Costs` in `pages/Costs/Costs.jsx`

#### Page structure

**Page header:**
```
LLM COSTS
✦ llm-orchestrator · model_cost_logs
```
Right side: time preset selector (reuse TIME_PRESETS + presetToRange pattern from Decisions page).

**Summary cards row** — 4 cards in a horizontal flex row:

| Card | Value | Format |
|------|-------|--------|
| Total Cost | `$X.XX` | 2 decimal places, `$` prefix |
| Total Tokens | `120K` or `1.2M` | K/M suffix for readability |
| Calls | `205 / 5` | success count / failure count |
| Avg Latency | `795ms` | integer, `ms` suffix |

Each card: `var(--bg-card)` background, `1px solid var(--border)`, no border-radius. Value in mono 24px, label in mono 10px uppercase tracked.

**CSS bar chart** — one bar per day (or per hour if range < 24h, based on the number of data points):

- Container: full width, fixed height 160px
- Bars: `var(--accent)` background, width proportional to container / data point count, height proportional to max value in the series
- X-axis labels: date in mono 10px, rotated if needed
- Hover: native `title` attribute showing exact cost and call count
- Empty state: centered mono text "No data for this period"
- The bar chart shows call count by default (most useful for local LiteLLM where costs are $0.00). Cost is shown as a secondary label in the tooltip.

**Model breakdown table** — standard DataTable pattern:

| Column | Align | Format |
|--------|-------|--------|
| Model | left | text |
| Calls | right | mono integer |
| Input Tokens | right | mono with K/M suffix |
| Output Tokens | right | mono with K/M suffix |
| Total Tokens | right | mono with K/M suffix |
| Cost | right | mono `$X.XXXX` |
| Avg Latency | right | mono `Xms` |

Sorted by call count descending. Empty state: "No LLM calls recorded for this period."

#### State

```js
const [timePreset, setTimePreset] = useState('30d')
const [customFrom, setCustomFrom] = useState('')
const [customTo, setCustomTo] = useState('')
const [totals, setTotals] = useState(null)
const [byModel, setByModel] = useState([])
const [byDay, setByDay] = useState([])
const [loading, setLoading] = useState(true)
const [error, setError] = useState('')
```

Data is fetched on mount and when time range changes. All three API calls fire in parallel (`Promise.all`).

### 8. CSS (Costs.module.css)

New classes needed:

| Class | Purpose |
|-------|---------|
| `.summaryRow` | Flex row for the 4 summary cards |
| `.summaryCard` | Individual card: bg-card, border, padding |
| `.summaryValue` | Large mono value (24px) |
| `.summaryLabel` | Small uppercase mono label (10px) |
| `.chartContainer` | Bar chart outer container, fixed height 160px |
| `.chartBar` | Individual bar, accent background |
| `.chartLabel` | X-axis date label |
| `.chartEmpty` | Empty state centered text |

All existing table/page styles from other pages are reused via CSS module composition or shared patterns.

## Affected files

| File | Change |
|------|--------|
| `emcip-llm-orchestrator/.../repository/ModelCostLogRepository.java` | Add 3 aggregation queries |
| `emcip-llm-orchestrator/.../service/CostTrackingService.java` | Add 3 methods mapping query results |
| `emcip-llm-orchestrator/.../controller/OrchestratorController.java` | Add 3 GET endpoints under `/api/costs` |
| `emcip-admin-api/.../controller/CostsProxyController.java` | New — proxy 3 GET endpoints to orchestrator |
| `emcip-admin-ui/.../auth/permissions.js` | Add `COSTS_READ` to both roles |
| `emcip-admin-ui/.../layout/Sidebar/Sidebar.jsx` | Add LLM Costs nav entry |
| `emcip-admin-ui/.../App.jsx` | Add `/costs` route |
| `emcip-admin-ui/.../api/costs.js` | New — API module |
| `emcip-admin-ui/.../pages/Costs/Costs.jsx` | New — page component |
| `emcip-admin-ui/.../pages/Costs/Costs.module.css` | New — page styles |

## Not in scope

- Budget alerts or spending thresholds
- CSV/PDF export
- Per-conversation cost drill-down
- Real-time streaming updates
- Chart library dependency (pure CSS bars)
- Cost tracking for the new `chat()` endpoint (separate concern — currently only `LlmCallService` tracks costs)
