# Simulate Page: Two-Column Pipeline Trace — Design

**Backlog item:** #41a
**Date:** 2026-06-17
**Size:** M

---

## Summary

Replace the current single-column Simulate page with a two-column layout: compose form on the left, pipeline trace on the right. The simulate endpoint becomes synchronous — it publishes the event then polls the audit log until all four pipeline stages are recorded, then returns the aggregated trace in one response. The frontend reveals all stages simultaneously when the response arrives.

---

## Problem

The existing Simulate page has a form that publishes a test message and shows a raw JSON dump. There is no visibility into what actually happened to the message as it travelled through the pipeline. Additionally, `AuditEventConsumer` has a latent bug: `correlationId` is set to each event's own `eventId` rather than the upstream `sourceEventId`, so correlation across pipeline stages has never worked.

---

## Data Flow

Every downstream event schema already carries `sourceEventId` = the originating `TelegramMessageEvent.eventId`. After the correlationId fix, the audit log can be queried by `correlationId = sim-uuid` to retrieve all four stages:

```
POST /api/simulate/message
  └─ SimulationService publishes TelegramMessageEvent (eventId = sim-uuid)
       └─ audit: correlationId = sim-uuid, eventType = TelegramMessage

intent-classifier → messages.classified (IntentClassifiedEvent.sourceEventId = sim-uuid)
       └─ audit: correlationId = sim-uuid, eventType = IntentClassified

policy-engine → policies.decisions (PolicyDecisionEvent.sourceEventId = sim-uuid)
       └─ audit: correlationId = sim-uuid, eventType = PolicyDecision

moderation-service → moderation.flags (ModerationFlagEvent.sourceEventId = sim-uuid)
       └─ audit: correlationId = sim-uuid, eventType = ModerationFlag
```

`SimulationService` polls `AuditServiceClient.findByCorrelationId(eventId)` every 500 ms (max 15 s) until all four `eventType` values are present, then returns the aggregated trace. If the timeout is reached, whatever stages arrived are returned with `partial: true`.

---

## Backend Changes

### emcip-audit-service

**1. `AuditEventConsumer` — fix correlationId**

Add a `correlationIdFn` parameter to `processAuditEvent()`. Each handler passes the appropriate function:

| Handler | correlationIdFn |
|---|---|
| `handleTelegramMessage` | `e -> e.eventId()` (root — own ID is correct) |
| `handleIntentClassified` | `e -> e.sourceEventId()` |
| `handlePolicyDecision` | `e -> e.sourceEventId()` |
| `handleResponseGenerated` | `e -> e.sourceEventId()` |
| `handleModerationFlag` | `e -> e.sourceEventId()` |

**2. `AuditEventRepository` — add derived method**

```java
Flux<AuditEventEntity> findByCorrelationId(String correlationId);
```

**3. `AuditController` — add correlationId filter**

Add optional `correlationId` query param to `GET /api/audit/events`. When present, delegates to `findByCorrelationId` (no date-range filter; simulation results arrive within seconds). Existing parameters unchanged.

### emcip-admin-api

**4. `AuditServiceClient` — add findByCorrelationId**

```java
public Mono<JsonNode> findByCorrelationId(String correlationId)
```

Calls `GET /api/audit/events?correlationId={id}&size=20`. Reuses existing circuit breaker + retry. Falls back to empty items node on error (same pattern as `listEvents`).

**5. `SimulationService` — synchronous polling**

After publishing, use `Flux.interval(Duration.ofMillis(500))` to poll `findByCorrelationId(eventId)`. Stop when the items array contains all four expected `eventType` values or after 30 ticks (15 s). Map each matching audit item to a typed stage. Return a new `SimulateTraceResult` record:

```java
record SimulateTraceResult(
    String eventId,
    String topic,
    boolean partial,
    List<TraceStage> stages
) {}

record TraceStage(
    String stage,       // PUBLISH | CLASSIFIER | POLICY | MODERATION
    Map<String, Object> data
) {}
```

Expected `eventType` → stage mapping:

| eventType | stage | data fields |
|---|---|---|
| `TelegramMessage` | `PUBLISH` | `topic`, `eventId` |
| `IntentClassified` | `CLASSIFIER` | `intent`, `confidence`, `matchedRules` |
| `PolicyDecision` | `POLICY` | `policyId`, `decision`, `actions`, `reason` |
| `ModerationFlag` | `MODERATION` | `flagType`, `severity`, `reason` |

**6. `SimulateController` — updated response**

```json
{
  "eventId": "sim-uuid",
  "partial": false,
  "stages": [
    { "stage": "PUBLISH",    "data": { "topic": "telegram.raw.messages", "eventId": "sim-uuid" } },
    { "stage": "CLASSIFIER", "data": { "intent": "SPAM", "confidence": 0.95, "matchedRules": ["SPAM"] } },
    { "stage": "POLICY",     "data": { "policyId": "no-spam", "decision": "BLOCK", "actions": ["BLOCK"], "reason": "..." } },
    { "stage": "MODERATION", "data": { "flagType": "SPAM", "severity": "HIGH", "reason": "..." } }
  ]
}
```

---

## Frontend Changes

### Layout

`Simulate.jsx` becomes a two-column CSS grid (`1fr 1fr`, gap `var(--sp-5)`):

- **Left column:** existing compose form card, unchanged
- **Right column:** `PipelineTrace` — a card always visible, showing 4 stage rows

### PipelineTrace Component

Extracted to `pages/Simulate/PipelineTrace.jsx` + `PipelineTrace.module.css`.

Props: `result` (null | SimulateTraceResult), `loading` (boolean).

**Idle state** (`result === null && !loading`): all 4 stage rows rendered with dimmed dot (`var(--border-strong)`) and stage name only, no data lines. No text below section label.

**Loading state** (`loading === true`): add a mono line below the section label: `▶ waiting for pipeline…` in `var(--fg-3)`. Stage rows remain in idle state.

**Result state** (`result !== null`): all stages reveal simultaneously. Missing stages (partial timeout) show `— timed out —` in `var(--signal-warn-fg)`.

### Stage Row Anatomy

```
● INTENT CLASSIFIER
  intent-classifier · messages.classified
  SPAM · 95% confidence
```

- Dot + stage name: display font, uppercase, 10px, tracked 0.18em
- Source line: mono 11px, `var(--fg-3)`
- Data line(s): mono 12px, `var(--fg-2)`

### Dot Color Mapping

| Stage | Color |
|---|---|
| PUBLISH | `--signal-ok-fg` (green — always succeeded if we reached it) |
| CLASSIFIER | `--accent` (gold — neutral, always produces a classification) |
| POLICY — BLOCK/MODERATE | `--signal-stop-fg` (red) |
| POLICY — REACT/SUMMARIZE | `--signal-info-fg` (blue) |
| POLICY — OBSERVE / other | `--signal-mute-fg` (gray) |
| MODERATION — HIGH | `--signal-stop-fg` (red) |
| MODERATION — MEDIUM | `--signal-warn-fg` (yellow) |
| MODERATION — LOW | `--signal-ok-fg` (green) |

### simulate.js

No change. Same `publish()` call; response shape is richer.

### Button State

While awaiting: button reads `Publishing…`, disabled (existing behaviour). Right column shows the loading state described above.

---

## Files Changed

| Service | File | Change |
|---|---|---|
| audit-service | `AuditEventConsumer.java` | Add `correlationIdFn` param; fix all handlers |
| audit-service | `AuditEventRepository.java` | Add `findByCorrelationId` |
| audit-service | `AuditController.java` | Add `correlationId` query param |
| admin-api | `AuditServiceClient.java` | Add `findByCorrelationId` method |
| admin-api | `SimulationService.java` | Polling logic + new result records |
| admin-api | `SimulateController.java` | Updated response mapping |
| admin-ui | `Simulate.jsx` | Two-column grid, PipelineTrace integration |
| admin-ui | `Simulate.module.css` | Grid layout + trace styles |
| admin-ui | `PipelineTrace.jsx` | New component (extracted) |
| admin-ui | `PipelineTrace.module.css` | Stage row styles |

---

## Out of Scope

- SSE / WebSocket streaming (option C from brainstorm — not needed with synchronous polling)
- Animated stage-by-stage reveal (user selected instant reveal)
- Retry UI (the 15 s timeout with `partial: true` is sufficient for a debug tool)
