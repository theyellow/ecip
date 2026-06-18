# US-26.7 Bulk Backfill — Design Spec

**Date:** 2026-06-18
**Status:** Approved
**Backlog item:** #26.7 — depends on 26.4 (knowledge extraction pipeline) ✅

---

## Goal

Operator-triggered historical backfill: fetch past messages from a watched Telegram group and push them through the knowledge extraction pipeline. Per-group, per-account, bounded by a date window the operator selects.

---

## Architecture

```
Admin UI (Groups page)
  → BackfillModal: account picker + date preset
  → POST /api/groups/{chatId}/backfill  { accountId, fromDate }
  → [poll GET /api/groups/{chatId}/backfill/{backfillId} every 2 s]

Admin-API  (thin proxy — no new DB schema)
  → GroupProfileController: two new endpoints, proxied to knowledge-engine
  → knowledgeWebClient (already configured)

Knowledge-Engine  (orchestrator — completes existing stubs)
  → BackfillController: POST + GET status
  → BackfillService: async fetch-publish loop
  → TdlibAdapterClient: new WebClient pointing at tdlib-adapter
  → KnowledgeEventPublisher: emits BACKFILL_PROGRESS events (already exists)

TDLib-Adapter  (new internal endpoint)
  → InternalController: GET /internal/chat-history/{accountId}/{chatId}
  → Pages backward through GetChatHistory until fromDate

Knowledge-Engine consumer  (unchanged)
  → KnowledgeMessageConsumer already consumes knowledge.raw.messages
  → Backfill messages flow through the same extraction pipeline as live messages
```

---

## Component Specifications

### 1. TDLib-Adapter — `GET /internal/chat-history/{accountId}/{chatId}`

**New endpoint in `InternalController`:**

```
GET /internal/chat-history/{accountId}/{chatId}
    ?fromDate={epochSeconds}
    &limit=100
    &offsetMessageId={lastMessageId}   (0 = start from newest)

200: {
  messages: [ TelegramMessageEvent... ],
  hasMore: boolean,
  lastMessageId: long
}
404: account not found or not connected
```

**Behaviour:**
- Calls `TdApi.GetChatHistory(chatId, offsetMessageId, offset=0, limit, onlyLocal=false)` via `TdLibClient.sendRequest()`
- Wraps in `Mono.create()` following the existing `GetChat` pattern in `InternalController`
- Filters out messages with `date < fromDate`; sets `hasMore = false` if any filtered message was older than `fromDate` (i.e., we've reached the cutoff)
- Returns only text messages for now (same filter as `KnowledgeMessageConsumer`)
- Uses existing rate-limiting on `TdLibClient` (30 req/s by default)

**Response record (new, in tdlib-adapter):**
```java
public record ChatHistoryResponse(
    List<TelegramMessageEvent> messages,
    boolean hasMore,
    long lastMessageId
) {}
```

---

### 2. Knowledge-Engine — BackfillService (complete existing stub)

**New dependency — `TdlibAdapterClient`** (new Spring `@Component`, `WebClient`-based):
```java
// application.yml: service.tdlib-adapter.url (already present for push-watched-groups)
Mono<ChatHistoryResponse> getChatHistory(UUID accountId, long chatId,
                                          long fromDateEpoch, long offsetMessageId);
```

**`BackfillStatus` record gains fields:**
```java
public record BackfillStatus(
    UUID backfillId,
    long chatId,
    BackfillState status,       // RUNNING | COMPLETED | FAILED
    int processed,
    long fromDate,              // epoch seconds
    Instant startedAt,
    @Nullable String errorMessage
) {}
// total is omitted — Telegram doesn't expose message count upfront
// UI shows running count, not a percentage bar
```

**`BackfillService.triggerBackfill(UUID accountId, long chatId, long fromDate, String tenantId)`:**
1. Generate `backfillId` (UUID), insert `RUNNING` status into `ConcurrentHashMap<UUID, BackfillStatus>`
2. Return `backfillId` immediately (202 pattern)
3. Launch async loop on a virtual-thread executor (`Executors.newVirtualThreadPerTaskExecutor()`):
   ```
   offsetMessageId = 0
   processed = 0
   loop:
     page = tdlibAdapterClient.getChatHistory(accountId, chatId, fromDate, offsetMessageId)
     for each message in page.messages:
       publish to kafka:knowledge.raw.messages (with tenant header)
       processed++
     emit BACKFILL_PROGRESS(backfillId, chatId, processed, tenantId)
     if not page.hasMore: break
     offsetMessageId = page.lastMessageId
   update status → COMPLETED
   ```
4. On any exception: update status → `FAILED` with `e.getMessage()`

**`BackfillController` (complete existing stub):**
```
POST /api/knowledge/backfill
Body: { accountId, chatId, tenantId, fromDate }
→ 202: { backfillId, status: "RUNNING" }

GET /api/knowledge/backfill/status?backfillId={id}
→ 200: BackfillStatus
→ 404: { error: "Backfill job not found" }
```

---

### 3. Admin-API — `GroupProfileController` (two new endpoints)

**No new service, no new DB schema.** Both endpoints proxy to knowledge-engine via existing `knowledgeWebClient`.

```
POST /api/groups/{chatId}/backfill
Body: { accountId: UUID, fromDate: String (ISO-8601) }
→ 202: { backfillId: UUID }
→ 503: if knowledge-engine unreachable (circuit breaker)

GET /api/groups/{chatId}/backfill/{backfillId}
→ 200: BackfillStatus
→ 404: if backfillId not found
```

**ISO-8601 → epoch conversion** happens in admin-api before forwarding (parse `fromDate`, convert to `epochSecond`, include in proxy request body).

**Request record (admin-api):**
```java
public record BackfillRequest(UUID accountId, String fromDate) {}
```

**Date preset → ISO-8601 conversion** is done in the UI before the POST.

---

### 4. Admin-UI — Groups Page

**New: `▶` backfill action button** on each row in the groups table (alongside the existing edit/delete actions).

**New: `BackfillModal` component** (`BackfillModal.jsx` + `BackfillModal.module.css`):

```
┌─ BACKFILL GROUP ──────────────────────────────────┐
│ — SELECT ACCOUNT —                                  │
│ [dropdown: watcher accounts for this group]         │
│                                                     │
│ — DATE RANGE —                                      │
│ [Last 7 days] [Last 30 days] [Last 3 months]        │
│ [Last 6 months] [Last year] [Custom ▸]              │
│                              ↓ (if Custom selected) │
│                [From: date input]                   │
│                                                     │
│  [Cancel]                    [▶ Start Backfill]     │
└─────────────────────────────────────────────────────┘
```

**Progress state (after submit):**
```
┌─ BACKFILL GROUP ──────────────────────────────────┐
│ ⟳ Processing…  847 messages ingested               │
│                                                     │
│                              [Close] (disabled)     │
└─────────────────────────────────────────────────────┘
```
- Poll `GET /api/groups/{chatId}/backfill/{backfillId}` every 2 s
- Show running `processed` count
- `COMPLETED`: "✓ Done — {N} messages ingested." + enabled Close button
- `FAILED`: error text in red + enabled Close button

**Accounts** populated by the existing `api.watchers(chatId)` call (already used in the Watchers modal). If the list is empty, the modal shows "No watcher accounts are connected to this group" and the submit button stays disabled.

**New API methods in `groups.js`:**
```js
backfill: (chatId, body) =>
  request(`/api/groups/${chatId}/backfill`, { method: 'POST', body }),
backfillStatus: (chatId, backfillId) =>
  request(`/api/groups/${chatId}/backfill/${backfillId}`),
```

---

## Data Flow

```
Operator clicks ▶ on group row
  → BackfillModal renders (fetches watchers)
  → Operator selects account + date preset
  → Submit: POST /api/groups/{chatId}/backfill { accountId, fromDate }
  → admin-api converts fromDate → epochSeconds, proxies to knowledge-engine
  → knowledge-engine: store RUNNING status, launch async loop
  → loop: GET /internal/chat-history/{accountId}/{chatId}?fromDate=...&limit=100
  → tdlib-adapter: GetChatHistory via TdLibClient
  → knowledge-engine: for each message → publish to knowledge.raw.messages
  → KnowledgeMessageConsumer: extracts entities, stores in knowledge graph
  → loop continues until hasMore=false
  → status → COMPLETED
  → UI poll returns COMPLETED → modal shows "Done"
```

---

## Error Handling

| Scenario | Behaviour |
|---|---|
| Account not connected in tdlib-adapter | 404 from tdlib-adapter → BackfillService sets FAILED("Account not connected") |
| Knowledge-engine unreachable | Circuit breaker in admin-api → 503 to UI → BackfillModal shows error |
| TDLib rate limit hit | TdLibClient's Resilience4j limiter queues requests; backfill slows naturally |
| Knowledge-engine restart mid-backfill | In-memory status lost; operator sees 404 on next poll → UI shows "Job not found, you can re-trigger" |
| chatId not in watched groups | 404 from tdlib-adapter getChatHistory (account not watching that chat) |

---

## Testing

**TDLib-adapter:**
- Unit test: `getChatHistory` returns `hasMore=false` when page contains message older than `fromDate`
- Unit test: `getChatHistory` returns `hasMore=true` when all messages are within range and page is full

**Knowledge-engine BackfillService:**
- Unit test: messages published to `knowledge.raw.messages` for each page (mock `TdlibAdapterClient` and `KafkaTemplate`)
- Unit test: status transitions `RUNNING → COMPLETED` after last page
- Unit test: status transitions `RUNNING → FAILED` on tdlib-adapter error

**Admin-api:**
- Unit test: `POST /api/groups/{chatId}/backfill` proxies correct body to knowledge-engine (ISO-8601 → epoch)
- Unit test: `GET /api/groups/{chatId}/backfill/{id}` returns knowledge-engine response

**Admin-ui:**
- Unit test: BackfillModal renders account dropdown and preset chips
- Unit test: submit button disabled until account + date preset selected
- Unit test: polling state shows processed count
- Unit test: COMPLETED state enables Close button

---

## File Map

| File | Action |
|---|---|
| `emcip-tdlib-adapter/.../controller/InternalController.java` | Add `getChatHistory` endpoint + `ChatHistoryResponse` record |
| `emcip-tdlib-adapter/.../controller/InternalControllerTest.java` | Add tests for new endpoint |
| `emcip-knowledge-engine/.../service/BackfillService.java` | Complete stub: async loop, virtual threads, progress events |
| `emcip-knowledge-engine/.../service/TdlibAdapterClient.java` | New WebClient wrapper |
| `emcip-knowledge-engine/.../controller/BackfillController.java` | Complete stub: POST + GET status |
| `emcip-knowledge-engine/.../controller/BackfillControllerTest.java` | Add/complete tests |
| `emcip-knowledge-engine/src/main/resources/application.yml` | Add `service.tdlib-adapter.url` if not present |
| `emcip-admin-api/.../controller/GroupProfileController.java` | Add backfill trigger + status proxy endpoints |
| `emcip-admin-api/.../controller/GroupProfileControllerTest.java` | Add tests for new endpoints |
| `emcip-admin-ui/.../pages/Groups/BackfillModal.jsx` | New component |
| `emcip-admin-ui/.../pages/Groups/BackfillModal.module.css` | New styles |
| `emcip-admin-ui/.../pages/Groups/BackfillModal.test.jsx` | New tests |
| `emcip-admin-ui/.../pages/Groups/Groups.jsx` | Add backfill button + wire BackfillModal |
| `emcip-admin-ui/.../api/groups.js` | Add `backfill` and `backfillStatus` methods |
