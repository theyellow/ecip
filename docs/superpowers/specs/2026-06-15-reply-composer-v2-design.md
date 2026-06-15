# #41b — Decisions Reply Composer v2

**Date**: 2026-06-15
**Status**: Draft
**Addresses**: Backlog #41 sub-item (b) — Decisions reply composer v2

---

## Goal

Upgrade the reply composer in the Flag Detail modal from a basic 2-mode textarea to a polished 4-mode composer with mode hints, convenience templates, character counter, and structured footer. Extract into its own component for maintainability.

## Current State

| Aspect | Has | Missing |
|--------|-----|---------|
| Modes | 2 (Group, DM) via SegmentedControl | Quote-reply, Silent note |
| Mode hints | None | One-liner describing selected mode |
| Templates | None | Chip-row with convenience phrases |
| Character counter | Silent `maxLength={4096}` | Visible `{n} / 4096 · MODE` counter |
| Footer | Send button on left | Discard + Send/Save note, counter on left |
| Backend | GROUP and DM targets | NOTE target (skip TDLib, audit-only) |
| Component structure | All inline in `Flags.jsx` (~400 lines) | Extracted `ReplyComposer` component |

## Design

### 1. Backend — Silent note support

Add a `NOTE` code path in `FlagService.reply()`. When `target` equals `"NOTE"`:

1. Skip TDLib send entirely (no `sendAndAudit` call)
2. Publish an `OPERATOR_NOTE` audit event to `audit.events` Kafka topic with the note text in details
3. Return `ReplyResponse` with `messageId: 0`, `target: "NOTE"`, `markedActioned: false`

The audit event structure:

```json
{
  "eventType": "OPERATOR_NOTE",
  "action": "ADD_NOTE",
  "sourceService": "admin-api",
  "resourceId": "<flagId>",
  "outcome": "SUCCESS",
  "details": {
    "target": "NOTE",
    "noteText": "<the note text>",
    "chatId": "<from flag metadata>"
  }
}
```

The `FlagService.reply()` method intercepts `NOTE` after fetching the flag metadata (it needs `chatId` for the audit event) but before resolving accounts (no account needed for notes).

No changes to `FlagController`, `ReplyRequest`, or `ReplyResponse` records — `"NOTE"` is just a new valid value for the existing `target` field.

### 2. Frontend — ReplyComposer extraction

Extract all reply state and UI from `FlagDetailModal` in `Flags.jsx` into a new `ReplyComposer` component.

**New files:**
- `pages/Flags/ReplyComposer.jsx`
- `pages/Flags/ReplyComposer.module.css`

**Props:**

```js
{
  flagId: string,       // flag ID for API calls
  api: object,          // flags API object (has .reply())
  onActioned: function, // callback when user confirms "mark as actioned"
}
```

**State moved out of Flags.jsx:**

`replyText`, `replyTarget`, `replyToOriginal`, `prefixModerator`, `replySending`, `replyError`, `replySuccess`, `accounts`, `selectedAccountId`, `promptActioned`

**Flags.jsx change:** The reply section JSX (lines ~254–317) is replaced with:

```jsx
{showReply && (
  <ReplyComposer
    flagId={flag.id}
    api={api}
    onActioned={() => onStatusChange(flag.id, 'ACTIONED')}
  />
)}
```

### 3. Frontend — 4-mode SegmentedControl

| Mode | Value | API mapping | Behavior |
|------|-------|-------------|----------|
| Group | `GROUP` | `{ target: 'GROUP' }` | Send to group chat |
| Quote | `QUOTE` | `{ target: 'GROUP', replyToOriginal: true }` | Reply to original message in group (UI-only preset) |
| DM | `DM` | `{ target: 'DM' }` | Direct message to sender |
| Note | `NOTE` | `{ target: 'NOTE' }` | Internal note, not sent to Telegram |

`QUOTE` is a frontend-only mode. When sending, it maps to `target: 'GROUP'` with `replyToOriginal: true` forced on.

### 4. Frontend — Mode hints

A mono one-liner below the SegmentedControl, describing the active mode:

| Mode | Hint |
|------|------|
| GROUP | "Posts in the group chat" |
| QUOTE | "Replies to the original message in the group" |
| DM | "Sends a direct message to the sender" |
| NOTE | "Internal note — not sent to Telegram" |

Styled: `font-family: var(--font-mono)`, `font-size: 11px`, `color: var(--fg-3)`.

### 5. Frontend — Chip-row templates

Hardcoded convenience phrases displayed as a horizontal chip row below the mode hint:

```js
const TEMPLATES = [
  'Thank you for reporting.',
  'No action needed.',
  'This has been reviewed.',
  'Warning issued.',
]
```

Each chip: mono 11px, transparent background, `1px solid var(--border)`, hover → gold border + gold text. Clicking a chip **replaces** the textarea content. A dashed `Clear` ghost chip resets the textarea to empty.

### 6. Frontend — Character counter and footer

**Footer layout** (flex row, justify space-between):

- **Left:** `{n} / 4096 · {MODE}` — mono 11px, `var(--fg-3)`. MODE shows the display label (Group, Quote, DM, Note).
- **Right:** `Discard` (secondary button, clears text and resets to GROUP mode) + `Send reply` (primary button, disabled until text present).
- In NOTE mode, the primary button reads `Save note` instead of `Send reply`.

**After send:** Primary button text briefly shows `Sent` (or `Saved` for notes), then the existing "Mark as actioned?" prompt appears with Yes/No buttons.

### 7. Frontend — Options and account selector

The existing checkboxes and account selector move into the composer:

- **Reply to original** checkbox: visible in GROUP and DM modes, hidden in QUOTE (implicit) and NOTE (irrelevant).
- **Prefix [Moderator]** checkbox: visible in GROUP, QUOTE, and DM modes, hidden in NOTE.
- **Account selector**: appears only on 409 conflict, same as current behavior. Hidden in NOTE mode.

### 8. CSS (ReplyComposer.module.css)

New classes:

| Class | Purpose |
|-------|---------|
| `.composer` | Flex column container with gap |
| `.modeHint` | Mono hint text below SegmentedControl |
| `.chipRow` | Flex row of template chips |
| `.chip` | Individual chip: mono, border, hover gold |
| `.chipGhost` | Dashed border variant for Clear |
| `.options` | Flex row for checkboxes |
| `.footer` | Flex row, space-between, align center |
| `.charCounter` | Mono counter text |
| `.textarea` | Reply textarea (migrated from Flags.module.css `.replyTextarea`) |
| `.success` | Green success text |
| `.alertBanner` | Error banner (same pattern as existing) |

Existing `.replySection`, `.replyOptions`, `.replyTextarea`, `.replyActions`, `.replySuccess` classes in `Flags.module.css` become unused and are removed.

## Affected files

| File | Change |
|------|--------|
| `emcip-admin-api/.../service/FlagService.java` | Add NOTE code path in `reply()` |
| `emcip-admin-api/.../service/FlagServiceTest.java` or `FlagControllerTest.java` | Add test for NOTE target |
| `emcip-admin-ui/.../pages/Flags/ReplyComposer.jsx` | New — extracted + upgraded composer |
| `emcip-admin-ui/.../pages/Flags/ReplyComposer.module.css` | New — composer styles |
| `emcip-admin-ui/.../pages/Flags/Flags.jsx` | Remove reply state/handler/JSX, render ReplyComposer |
| `emcip-admin-ui/.../pages/Flags/Flags.module.css` | Remove unused reply classes |

## Not in scope

- Configurable per-tenant templates (backend storage, CRUD, management UI) — follow-up
- Reply history / note history display on the flag
- WebSocket real-time reply status updates
- Quote-reply as a distinct backend behavior (currently maps to GROUP + replyToOriginal)
