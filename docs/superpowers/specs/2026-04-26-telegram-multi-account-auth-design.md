# Telegram Multi-Account Auth — Design Spec

**Date:** 2026-04-26
**Status:** Approved
**Scope:** emcip-tdlib-adapter, emcip-admin-api, emcip-admin-ui

---

## Context

EMCIP currently supports exactly one Telegram user account: `TelegramConfig` is a single-row table (`id=1`) and `TdLibClient` is a singleton Spring bean. This hard-codes a single session and makes multi-account support impossible.

This design replaces that with a proper multi-account pool: multiple Telegram user accounts can be stored, authenticated interactively via the Admin UI, and reactivated across restarts via persisted sessions.

---

## Data Model

### Replace `telegram_config` with `telegram_accounts`

The `telegram_config` table is dropped and replaced by `telegram_accounts` via a clean Liquibase migration (pre-alpha, no migration needed).

```sql
telegram_accounts
─────────────────────────────────────────────────────
id             UUID         PK
phone_number   VARCHAR(50)  NOT NULL UNIQUE
api_id         INTEGER      NOT NULL
api_hash       VARCHAR(255) NOT NULL
display_name   VARCHAR(100)              -- e.g. "Monitor account 1"
session_string TEXT                      -- persisted TDLib session
status         VARCHAR(30)  NOT NULL     -- state machine value
last_error     VARCHAR(500)              -- null when healthy
created_at     TIMESTAMP    NOT NULL
updated_at     TIMESTAMP    NOT NULL
```

### Auth Status State Machine

```
UNCONFIGURED
     │ submit phone
     ▼
AWAITING_CODE
     │ submit code
     ▼
AWAITING_PASSWORD  ──(no 2FA)──▶  ACTIVE
     │ submit password
     ▼
   ACTIVE
     │ logout / error / ban
     ▼
DISCONNECTED  ──(reconnect)──▶  AWAITING_CODE
```

Values: `UNCONFIGURED`, `AWAITING_CODE`, `AWAITING_PASSWORD`, `ACTIVE`, `DISCONNECTED`

---

## Architecture & Components

### emcip-tdlib-adapter

**`TdLibClientManager`** (new, replaces singleton `TdLibClient` bean)
- Holds `Map<UUID, TdLibClient>` — one client instance per account
- On startup: loads all accounts with status `ACTIVE` or `DISCONNECTED` from the DB; attempts silent session resume for each
- Creates a new `TdLibClient` when an account starts its auth flow
- Destroys client instances on account deletion or logout
- Each client uses an isolated TDLib database directory: `tdlib-db/{accountId}/`

**`TdLibClient`** (refactored)
- No longer a singleton Spring bean — instantiated per account by the manager
- Accepts `accountId`, `apiId`, `apiHash`, `databaseDirectory` at construction
- Emits auth state transitions back to the manager via callback
- On error: sets `status=DISCONNECTED` + populates `last_error`

**`AuthController`** (refactored — all endpoints become account-scoped)
```
GET  /api/auth/{accountId}/status    -- current auth state + last_error
POST /api/auth/{accountId}/phone     -- submit phone number
POST /api/auth/{accountId}/code      -- submit verification code
POST /api/auth/{accountId}/password  -- submit 2FA password
POST /api/auth/{accountId}/logout    -- logout and clear session
```

### emcip-admin-api

**`TelegramAccountController`** (replaces `TelegramController`)
```
GET    /api/telegram/accounts            -- list all accounts with status
POST   /api/telegram/accounts            -- create account (phone, apiId, apiHash, displayName)
DELETE /api/telegram/accounts/{id}       -- remove account
GET    /api/telegram/accounts/{id}/status -- current status + last_error
POST   /api/telegram/accounts/{id}/reconnect -- trigger re-auth (→ AWAITING_CODE)
```

**`TelegramAccount`** entity (replaces `TelegramConfig`)
- JPA entity matching the schema above
- `TelegramAccountRepository extends JpaRepository<TelegramAccount, UUID>`

**Liquibase migration**
- Drop `telegram_config` table
- Create `telegram_accounts` table
- New changeset in `emcip-admin-api/src/main/resources/db/changelog/`

### emcip-admin-ui

Replace the existing single Telegram config panel with an **Accounts page**:

- Table listing all accounts: `displayName`, `phoneNumber`, `status` badge, `lastError` tooltip on failure badges
- **Add account** button → modal with fields: Display Name, Phone Number, API ID, API Hash
- Per-row actions:
  - **Authenticate** → opens auth wizard (step 1: phone sent automatically on reconnect; step 2: enter code; step 3: enter 2FA password if required)
  - **Disconnect** → logout
  - **Delete** → remove account

**Status polling:** While the auth wizard is open, the UI polls `GET /api/telegram/accounts/{id}/status` every 2–3 seconds to update the status badge in real-time.

---

## Auth Flow (Runtime Sequence)

### Adding and authenticating a new account

```
Admin UI          Admin API           TdLib Adapter         Telegram
   │                  │                     │                   │
   │ POST /accounts   │                     │                   │
   │─────────────────▶│ creates DB row      │                   │
   │                  │ status=UNCONFIGURED │                   │
   │                  │                     │                   │
   │ POST /reconnect  │                     │                   │
   │─────────────────▶│ POST /auth/{id}/phone                   │
   │                  │────────────────────▶│ TDLib sendPhone   │
   │                  │                     │──────────────────▶│
   │                  │ status=AWAITING_CODE│                   │
   │◀─────────────────│◀────────────────────│                   │
   │                  │                     │                   │
   │  [admin enters code from Telegram app] │                   │
   │                  │                     │                   │
   │ POST /code       │                     │                   │
   │─────────────────▶│ POST /auth/{id}/code                    │
   │                  │────────────────────▶│ TDLib submitCode  │
   │                  │                     │──────────────────▶│
   │                  │ status=ACTIVE        │                   │
   │                  │  (or AWAITING_PASSWORD if 2FA)          │
   │◀─────────────────│◀────────────────────│                   │
```

### Session resume on startup

On `TdLibClientManager` startup: for each account with `status=ACTIVE`, instantiate a `TdLibClient` with the persisted `session_string` and attempt silent resume. If TDLib confirms authorization, remain `ACTIVE`. If it fails, set `DISCONNECTED` with an appropriate `last_error`.

---

## Edge Case Handling

| Scenario | Handling |
|----------|----------|
| Expired verification code | TDLib returns error; keep `status=AWAITING_CODE`; return `"Code expired — request a new one"` to UI |
| Wrong 2FA password | TDLib returns error; keep `status=AWAITING_PASSWORD`; return `"Incorrect password"` to UI |
| Invalid phone format | Validate before sending to TDLib; return HTTP 400 immediately |
| Account banned | TDLib signals `authorizationStateClosed`; set `status=DISCONNECTED`, `last_error="Account banned"`; shown as red badge in UI |
| Network/TDLib timeout | Set `status=DISCONNECTED`, `last_error="Connection timeout"` |

---

## What Does NOT Change

- The `User` entity (`users` table in emcip-conversation-context) — tracks end-users sending messages, not our accounts
- Kafka topics and event publishing pipeline
- Admin JWT authentication
- All other services (intent-classifier, policy-engine, etc.)

---

## Out of Scope (Future)

- Concurrent active sessions (all accounts active simultaneously)
- Self-service account connection by end-users
- OAuth2/OIDC integration
