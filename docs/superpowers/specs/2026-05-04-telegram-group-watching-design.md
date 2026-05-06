# Telegram Group Watching — Design Spec

**Date:** 2026-05-04
**Status:** Approved
**Scope:** emcip-tdlib-adapter, emcip-admin-api, emcip-admin-ui

> **Pre-implementation risk:** Multi-account authentication has only been tested with a single account. The first implementation task must verify that two accounts can authenticate and receive messages independently before building the group watching layer on top.

---

## Context

EMCIP can authenticate multiple Telegram user-bot accounts (phone → code → session). Once authenticated, each TdLibClient receives every update from every chat the user-bot is a member of — messages are published to Kafka indiscriminately. There is no concept of "watched groups": all chats flow through, with no filtering and no deduplication when multiple accounts are in the same group.

This spec adds:
- Per-account group discovery (list groups the bot is already a member of via TDLib)
- Operator-controlled watch list (subscribe/unsubscribe groups per account)
- Message filtering at the source (tdlib-adapter only publishes from watched groups)
- Deduplication (if two accounts watch the same group, one message reaches Kafka)
- Join by invite link (best-effort — ships only if straightforward)

---

## Data Model

### New table: `account_watched_groups`

```sql
account_watched_groups
───────────────────────────────────────────────────────────
account_id        UUID    NOT NULL  FK → telegram_accounts.id
group_profile_id  BIGINT  NOT NULL  FK → group_profiles.id
created_at        TIMESTAMP NOT NULL
PRIMARY KEY (account_id, group_profile_id)
```

### `group_profiles` — add unique constraint

`telegram_chat_id` already exists on this table. Add a `UNIQUE` constraint (or index) so upsert-by-chat-id is safe and deterministic.

### `telegram_accounts` — no schema changes

---

## Architecture

### Watching a group (runtime flow)

```
Admin UI                 Admin API              TdLib Adapter
   │                         │                       │
   │ POST /accounts/{id}/watch (chatId, title)        │
   │────────────────────────▶│                       │
   │                         │ upsert GroupProfile   │
   │                         │ insert watched row    │
   │                         │                       │
   │                         │ POST /internal/watched-groups/{accountId}
   │                         │ { chatIds: [...] }    │
   │                         │──────────────────────▶│
   │                         │                       │ updates in-memory set
   │◀────────────────────────│                       │
```

### Message filtering (runtime)

For every `UpdateNewMessage` received by a `TdLibClient`:

1. Look up `watchedChatIds.get(accountId)` — the in-memory `Set<Long>` for this account
2. If the set is empty or does not contain `message.chatId` → drop silently (no Kafka publish)
3. Check dedup cache: `get-if-absent("chatId:messageId")` with 60-second TTL
4. If already present (another account published this message) → drop silently
5. Otherwise → publish to `telegram.raw.messages`

### Startup sync

`TelegramSessionResumeRunner` (admin-api), after initialising each active account:
1. Fetches `account_watched_groups` rows for that account
2. Resolves `telegram_chat_id` values from the joined `GroupProfile` rows
3. Pushes the full set to `POST /internal/watched-groups/{accountId}` on tdlib-adapter

---

## emcip-tdlib-adapter

### `TdLibClientManager` changes

- Adds `ConcurrentMap<UUID, Set<Long>> watchedChatIds`
- Method `updateWatchedChats(UUID accountId, Set<Long> chatIds)` — replaces the set atomically
- On `removeClient(accountId)` — also clears the watched set
- Passes `watchedChatIds` reference to `TelegramUpdateHandler`

### New controller: `InternalController`

```
POST /internal/watched-groups/{accountId}
     Body: { "chatIds": [123456789, 987654321] }
     — calls TdLibClientManager.updateWatchedChats(accountId, chatIds)
     — returns 204

GET  /internal/chats/{accountId}
     — calls TdApi.GetChats + TdApi.LoadChats on the account's TdLibClient
     — filters to type group / supergroup / channel only (excludes direct messages)
     — returns list of { chatId, title, memberCount, type }
```

Security: these endpoints are internal only, protected by the existing service token filter (`ServiceTokenAuthenticationFilter`).

### `TelegramUpdateHandler` changes

- Receives `watchedChatIds` map (`ConcurrentMap<UUID, Set<Long>>`) injected from `TdLibClientManager`
- `registerOn(TdLibClient client)` captures `client.getAccountId()` in a lambda when registering `UpdateNewMessage`:
  ```java
  UUID accountId = client.getAccountId();
  client.registerUpdateHandler("UpdateNewMessage", update -> handleNewMessage(accountId, update));
  ```
  This gives `handleNewMessage` the accountId needed to look up the watched set.
- `handleNewMessage(UUID accountId, TdApi.Update update)`: guard before publishing — check `watchedChatIds.getOrDefault(accountId, Set.of()).contains(message.chatId)`; skip if false

### `TelegramEventPublisher` changes

- Add `com.github.ben-manes.caffeine:caffeine` dependency to `emcip-tdlib-adapter/pom.xml`
- Adds `Cache<String, Boolean> deduplicationCache` (Caffeine, 60s expiry, max 10,000 entries)
- `publishMessage`: compute key `chatId + ":" + messageId`; use `cache.get(key, k -> { publish(); return true; })` pattern — first caller publishes, subsequent callers within the TTL skip

---

## emcip-admin-api

### New endpoints (added to `TelegramAccountController`)

```
GET  /api/telegram/accounts/{id}/chats
     — proxies GET /internal/chats/{id} on tdlib-adapter
     — returns discovered groups the bot is a member of
     — response: [{ chatId, title, memberCount, type }]

GET  /api/telegram/accounts/{id}/watched
     — queries account_watched_groups JOIN group_profiles for this account
     — response: [{ chatId, groupProfileId, name, moderationLevel }]

POST /api/telegram/accounts/{id}/watch
     Body: { "chatId": 123456789, "title": "My Group", "memberCount": 42 }
     — upserts GroupProfile: custom @Query on GroupProfileRepository:
         INSERT INTO group_profiles (telegram_chat_id, name, ...) VALUES (...)
         ON CONFLICT (telegram_chat_id) DO NOTHING
         followed by SELECT ... WHERE telegram_chat_id = :chatId
       (standard R2dbcEntityTemplate.insert() does not support ON CONFLICT)
     — inserts account_watched_groups row via R2dbcEntityTemplate.insert() (PK violation = already watching, ignore)
     — fetches full watched chat ID set for account, pushes to tdlib-adapter
     — returns 201 with the GroupProfile

DELETE /api/telegram/accounts/{id}/watch/{chatId}
     — resolves GroupProfile by telegram_chat_id
     — deletes account_watched_groups row
     — does NOT delete GroupProfile (other accounts may reference it)
     — fetches updated set, pushes to tdlib-adapter
     — returns 204
```

### Optional: join by invite link

```
POST /api/telegram/accounts/{id}/join
     Body: { "inviteLink": "https://t.me/+abc123" }
     — proxies TdApi.JoinChatByInviteLink on tdlib-adapter
     — on success: calls the watch flow above for the resulting chatId
     — returns 201 with GroupProfile
```

Implement this endpoint only if tdlib-adapter can expose `JoinChatByInviteLink` cleanly (a simple pass-through endpoint). If the TDLib response does not cleanly return a chat ID synchronously, defer this endpoint.

### `TelegramSessionResumeRunner` changes

After each successful `POST /api/auth/{id}/initialize`:
1. Query `account_watched_groups JOIN group_profiles` for this account
2. Extract `telegram_chat_id` values
3. `POST /internal/watched-groups/{accountId}` with the set (may be empty — that is valid)

### Liquibase migration (`008-account-watched-groups.xml`)

```xml
<!-- 1. Unique constraint on group_profiles.telegram_chat_id -->
<addUniqueConstraint tableName="group_profiles"
    columnNames="telegram_chat_id"
    constraintName="uq_group_profiles_telegram_chat_id"/>

<!-- 2. account_watched_groups table -->
<createTable tableName="account_watched_groups">
    <column name="account_id" type="UUID"><constraints nullable="false"/></column>
    <column name="group_profile_id" type="BIGINT"><constraints nullable="false"/></column>
    <column name="created_at" type="TIMESTAMP"><constraints nullable="false"/></column>
</createTable>
<addPrimaryKey tableName="account_watched_groups"
    columnNames="account_id,group_profile_id"
    constraintName="pk_account_watched_groups"/>
<addForeignKeyConstraint
    baseTableName="account_watched_groups" baseColumnNames="account_id"
    referencedTableName="telegram_accounts" referencedColumnNames="id"
    constraintName="fk_awg_account"/>
<addForeignKeyConstraint
    baseTableName="account_watched_groups" baseColumnNames="group_profile_id"
    referencedTableName="group_profiles" referencedColumnNames="id"
    constraintName="fk_awg_group_profile"/>
```

---

## emcip-admin-ui

### Telegram page changes

Each account row in the accounts table gains a **"Groups" button**. Clicking it expands an inline panel beneath the row (or opens a drawer) showing:

**Watched groups sub-table:**
- Columns: Name, Chat ID, Moderation Level, Unwatch (button)
- "Discover groups" button → opens discover modal
- Empty state: "No groups watched. Use Discover to add groups."

**Discover groups modal:**
- Fetches `GET /api/telegram/accounts/{id}/chats` on open
- Lists all groups the bot is a member of: Name, Member count, Type, Watch button
- "Watch" button grayed out (label: "Watching") if the chat is already in the watched list
- On "Watch" click: calls `POST /api/telegram/accounts/{id}/watch`, refreshes both the watched sub-table and the modal
- "Refresh" button in the modal header — re-fetches the chat list (needed because TDLib takes a few seconds to sync chats after a fresh session; operator can retry if the list appears empty or incomplete)
- Loading and error states on the fetch

**Auto-open on authentication:**
The auth wizard already polls `GET /api/telegram/accounts/{id}/status` every 2.5 seconds. When the poll detects `ACTIVE`, instead of only closing the wizard, it also automatically opens the discover modal for that account. No backend change needed — the UI drives this. The operator can close the modal if they don't want to configure groups immediately.

**Invite link input (bottom of discover modal — if join endpoint is implemented):**
- Text input: "Invite link (optional)" + "Join & Watch" button
- Calls `POST /api/telegram/accounts/{id}/join`
- On success: refreshes watched list and closes modal
- On error: shows inline error message

---

## Edge Cases

| Scenario | Handling |
|----------|----------|
| Account not yet ACTIVE when "Discover" clicked | Return HTTP 400 from tdlib-adapter: "Account not initialized"; UI shows "Account must be authenticated first" |
| TDLib `GetChats` returns empty (no groups) | Return empty list; UI shows "No groups found" |
| Watch called for a chatId where GroupProfile already exists | `ON CONFLICT DO NOTHING` on insert; return existing profile |
| Watch called when account is not a member of the group | TDLib will not have the chat in `GetChats`; this path can only happen via the invite link join, which handles the error |
| tdlib-adapter unreachable when pushing watched set | Log warning; the push is best-effort — the in-memory set will be stale until next restart/resync. Admin-api still persists the DB change. |
| Two accounts publish same message simultaneously | Caffeine cache with `get-if-absent` is thread-safe; one caller executes the publish, the other returns the cached value and skips |

---

## Out of Scope

- Editing GroupProfile moderation settings from the Telegram accounts page (use existing Groups page)
- Unwatch all groups when an account is deleted (deferred — FK cascade can handle orphan cleanup)
- Per-group Kafka topic routing (all watched groups go to `telegram.raw.messages`)
- arm64 / multi-arch concerns (no new native code)
