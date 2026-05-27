# Flag-Detail Operator Reply — Design Spec

> Backlog item #23 — Phase 1 only. Phase 2 (AI-research prompt interface) deferred until items #26/#27 are complete.

**Goal:** Allow an operator to send a direct response to a flagged Telegram message from the flag detail modal in the admin UI.

**Approach:** Hybrid synchronous send + async audit (Approach C). The admin-api calls tdlib-adapter's internal HTTP API to send the message, giving the operator instant feedback. An audit event is published to Kafka after successful send.

---

## 1. TDLib Adapter — Send Message Endpoint

New internal endpoint:

```
POST /internal/send-message/{accountId}
→ 201 Created  { "success": true, "messageId": 12345 }
→ 400 Bad Request (missing fields, account not authorized)
→ 500 Internal Server Error (TDLib failure)
```

Request body:

```json
{
  "chatId": -100123456,
  "text": "Please review the community guidelines.",
  "replyToMessageId": 4567,
  "recipientUserId": 98765
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `chatId` | long | yes | Target group chat ID |
| `text` | string | yes | Message text to send |
| `replyToMessageId` | long | no | If > 0, link as reply to this message |
| `recipientUserId` | long | no | If set, send as DM to this user instead of to the group |

Logic:
- If `recipientUserId` is set → `TdApi.CreatePrivateChat` to open/get a private chat, then send there
- Otherwise → send to `chatId` (the group)
- If `replyToMessageId` > 0 → set TDLib's reply-to input parameter
- Uses existing `TdLibClient.sendRequest()` which includes per-API-ID rate limiting

---

## 2. Admin API — Reply Endpoint

New endpoint:

```
POST /api/flags/{id}/reply
→ 201 Created  { "messageId": 12345, "target": "GROUP", "markedActioned": false }
→ 400 Bad Request (validation error, no account watching chat)
→ 404 Not Found (flag not found)
→ 409 Conflict (multiple accounts, selection required)
→ 502 Bad Gateway (tdlib-adapter unreachable or send failed)
```

Request body:

```json
{
  "text": "Please review the community guidelines.",
  "target": "GROUP",
  "replyToOriginal": true,
  "prefixModerator": false,
  "accountId": null
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | string | yes | Message text (max 4096 chars, Telegram limit) |
| `target` | string | yes | `"GROUP"` or `"DM"` |
| `replyToOriginal` | boolean | yes | Link as reply to the original flagged message |
| `prefixModerator` | boolean | yes | Prepend `"[Moderator]: "` to message text |
| `accountId` | UUID | no | Which Telegram account to send from |

### Flow

1. Load the flag (PolicyDecision) from policy-engine — extract `chatId`, `senderId`, `telegramMessageId` from metadata.
2. Query `AccountWatchedGroup` for all tenant accounts watching that `chatId`.
3. **Account selection:**
   - If `accountId` provided → validate it watches the chat, use it.
   - If omitted and exactly one account → auto-select.
   - If omitted and multiple accounts → return `409` with `{ "accounts": [{ "id": "...", "displayName": "...", "phoneNumber": "..." }, ...] }`.
4. Build tdlib-adapter request:
   - If `target=DM` → set `recipientUserId` (parsed from `senderId` field, e.g. `"user:12345"` → `12345`).
   - If `replyToOriginal=true` → set `replyToMessageId` from metadata's `telegramMessageId`.
   - If `prefixModerator=true` → prepend `"[Moderator]: "` to text.
5. Call `POST /internal/send-message/{accountId}` on tdlib-adapter via existing WebClient (with circuit breaker).
6. On success → publish `OPERATOR_REPLY` audit event to `audit.events`.
7. Return `201` with result.

---

## 3. Admin UI — Reply Panel in Flag Detail Modal

Add a collapsible "Reply" section at the bottom of the existing flag detail modal.

### Collapsed state
- Toggle button: `▸ Reply` (same `sessionToggle` pattern as Telegram account Advanced section)

### Expanded state
- **Textarea**: message text input (resizable, placeholder: "Type your response...")
- **Options row** (inline, flex):
  - Target toggle: `Group` / `DM` (two buttons, one active — default: Group)
  - Checkbox: "Reply to original" (default: checked)
  - Checkbox: "Prefix [Moderator]" (default: unchecked)
- **Account dropdown**: hidden by default. Appears only after a 409 response (multiple accounts). Shows `displayName (phoneNumber)` for each.
- **Send button**: primary style

### After successful send
- Green "Sent!" inline confirmation
- Prompt: "Mark as actioned?" with `Yes` / `No` buttons
- `Yes` → calls existing `updateStatus(id, 'ACTIONED')`, updates status badge in modal
- `No` → dismisses prompt, status unchanged

### Error handling
- Red inline error message below the Send button
- On 409 → account dropdown appears, operator picks, re-sends

---

## 4. Data Prerequisite — telegramMessageId in Flag Metadata

The policy engine's `PolicyEvaluationService` currently copies `messageText`, `chatId`, `senderId` into the PolicyDecision metadata JSON. It does not include `telegramMessageId`.

**Change required:** Add one line to `PolicyEvaluationService` metadata builder:

```java
if (params.containsKey("telegramMessageId")) meta.put("telegramMessageId", params.get("telegramMessageId"));
```

The `telegramMessageId` is already present on `TelegramMessageEvent` (field added in the original event schema). However, the intent-classifier's `IntentClassificationService` currently only passes `chatId`, `senderId`, `messageText` in the parameters map — it does **not** include `telegramMessageId`.

**Two changes required:**

1. **Intent-classifier** (`IntentClassificationService.java:99`): Add `"telegramMessageId", message.telegramMessageId()` to the parameters map.
2. **Policy-engine** (`PolicyEvaluationService.java`): Add `if (params.containsKey("telegramMessageId")) meta.put("telegramMessageId", params.get("telegramMessageId"));` to the metadata builder.

No schema changes — `metadata` is already a JSON column on `policy_decisions`.

---

## 5. Audit Trail

On successful reply, admin-api publishes to `audit.events`:

```json
{
  "eventType": "OPERATOR_REPLY",
  "action": "SEND_MESSAGE",
  "sourceService": "admin-api",
  "resourceId": "<flag-id>",
  "outcome": "SUCCESS",
  "details": {
    "target": "GROUP",
    "chatId": -100123456,
    "accountId": "<uuid>",
    "telegramMessageId": 12345,
    "replyToOriginal": true,
    "prefixModerator": false
  }
}
```

Message text is **not** included in the audit event to avoid storing sensitive content. The `telegramMessageId` provides a reference.

Uses the existing `AuditEvent` schema and Kafka publishing pattern from admin-api.

---

## 6. What This Does NOT Cover

- **Phase 2: AI-research prompt interface** — chat-style UI backed by LLM for drafting responses. Depends on items #26 (topic clustering / RAG) and #27 (deep research agent). Separate spec when prerequisites are ready.
- **Media/file replies** — text only for Phase 1.
- **Editing or deleting sent messages** — out of scope.
- **Bulk replies** — one flag at a time.
- **Message templates** — operator types freeform text. Templates could be a future enhancement.

---

## Services Modified

| Service | Change |
|---------|--------|
| `emcip-tdlib-adapter` | New `POST /internal/send-message/{accountId}` endpoint |
| `emcip-admin-api` | New `POST /api/flags/{id}/reply` endpoint, new service method, API client method |
| `emcip-admin-ui` | Reply panel in flag detail modal, new API method |
| `emcip-intent-classifier` | Pass `telegramMessageId` through parameters map |
| `emcip-policy-engine` | Add `telegramMessageId` to PolicyDecision metadata |
| `emcip-core` | No changes (event schemas unchanged) |
