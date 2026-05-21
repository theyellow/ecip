# Telegram Self-Service Account Connection + RBAC Design

**Date:** 2026-05-21
**Status:** Approved — pending implementation
**Backlog:** Item #9 (Telegram: self-service account connection)
**Scope:** emcip-admin-api, emcip-admin-ui
**Also addresses:** Security finding S10 (tenant ID spoofable via header)

---

## Context

Backlog item #9 originally described as "allow end-users (not just admins) to link Telegram accounts via phone → OTP flow." Analysis revealed this requires a proper RBAC foundation first: there is currently only one user role (`ADMIN`) and no concept of a tenant-scoped user.

A public self-service portal (non-EMCIP users registering via phone OTP only, no admin account) is explicitly out of scope and documented in `documentation/POSSIBLE_DEVELOPMENT.md`.

This spec covers:
1. A new `TENANT_ADMIN` role — full tenant-scoped access, can self-service Telegram accounts
2. A fixed role → permission matrix (extensible, auditable)
3. S10 fix — tenant ID embedded in JWT, not spoofable via header
4. User management API + UI page (admin-only)
5. Global tenant context switcher in the admin UI

---

## Section 1: Data Model

### Liquibase migration `012-admin-users-add-tenant.xml`

Adds `tenant_id UUID` (nullable) to `admin_users`.

```sql
ALTER TABLE admin_users ADD COLUMN tenant_id UUID REFERENCES tenants(id);
```

**Semantics:**
- `ADMIN` role → `tenant_id` is NULL (superadmin, cross-tenant)
- `TENANT_ADMIN` role → `tenant_id` is non-null (scoped to that tenant)

No other tables change.

---

## Section 2: Role & Permission Model

### `Role` enum
```
ADMIN         — superadmin, cross-tenant, tenant_id = null
TENANT_ADMIN  — full tenant-scoped access, tenant_id required
```

### `Permission` enum
```
GROUPS_READ           GROUPS_WRITE
POLICY_RULES_READ     POLICY_RULES_WRITE
MODERATION_RULES_READ MODERATION_RULES_WRITE
AUDIT_READ
TELEGRAM_READ         TELEGRAM_WRITE
SIMULATE_WRITE
AI_CONFIG_READ        AI_CONFIG_WRITE
TENANTS_READ          TENANTS_WRITE
USERS_READ            USERS_WRITE
```

### `RolePermissions` — single source of truth

| Permission            | ADMIN | TENANT_ADMIN |
|-----------------------|-------|--------------|
| GROUPS_READ           | ✅    | ✅           |
| GROUPS_WRITE          | ✅    | ✅           |
| POLICY_RULES_READ     | ✅    | ✅           |
| POLICY_RULES_WRITE    | ✅    | ✅           |
| MODERATION_RULES_READ | ✅    | ✅           |
| MODERATION_RULES_WRITE| ✅    | ✅           |
| AUDIT_READ            | ✅    | ✅           |
| TELEGRAM_READ         | ✅    | ✅           |
| TELEGRAM_WRITE        | ✅    | ✅           |
| SIMULATE_WRITE        | ✅    | ✅           |
| AI_CONFIG_READ        | ✅    | ❌           |
| AI_CONFIG_WRITE       | ✅    | ❌           |
| TENANTS_READ          | ✅    | ❌           |
| TENANTS_WRITE         | ✅    | ❌           |
| USERS_READ            | ✅    | ❌           |
| USERS_WRITE           | ✅    | ❌           |

### Enforcement mechanism

At login, `JwtAuthenticationFilter` calls `RolePermissions.permissionsFor(role)` and adds each `Permission` as a `GrantedAuthority`. Controllers declare:

```java
@PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
```

No role checks in controllers — only permission checks. `RolePermissions` is the only place role→permission logic lives.

---

## Section 3: JWT & Auth (S10 Fix)

### JWT payload

```json
// ADMIN
{ "sub": "admin", "role": "ADMIN" }

// TENANT_ADMIN
{ "sub": "alice", "role": "TENANT_ADMIN", "tenantId": "550e8400-e29b-41d4-a716-446655440000" }
```

### `JwtService` changes
- `generateToken(String username, String role, @Nullable String tenantId)` — adds `tenantId` claim when non-null
- `extractTenantId(String token)` — new method alongside existing `extractRole`
- `generateRefreshToken` and token-from-refresh both carry `tenantId` through unchanged

### `AdminTenantContextFilter` updated

| Condition | Behaviour |
|-----------|-----------|
| Path is `/actuator/**`, `/api/auth/token`, `/api/auth/refresh` | Bypass (unchanged) |
| Role is `ADMIN`, no `X-Tenant-Id` header | Admin mode (unchanged) |
| Role is `ADMIN`, `X-Tenant-Id` header present | Tenant context from header (admin cross-tenant switching) |
| Role is `TENANT_ADMIN` | Tenant context from JWT `tenantId` claim; `X-Tenant-Id` header **ignored** |

This is the S10 fix: a `TENANT_ADMIN` user cannot spoof a different tenant by sending a crafted `X-Tenant-Id` header.

---

## Section 4: User Management API

All endpoints require `USERS_WRITE` permission (ADMIN only).

```
GET    /api/users                  — list all users
POST   /api/users                  — create user
PUT    /api/users/{id}             — update role, tenantId, enabled
DELETE /api/users/{id}             — delete user
POST   /api/users/{id}/password    — reset password
```

### Request/response shapes

**POST /api/users** (create)
```json
{
  "username": "alice",
  "email": "alice@example.com",
  "password": "...",
  "role": "TENANT_ADMIN",
  "tenantId": "uuid"
}
```

**GET /api/users** (list item)
```json
{
  "id": "uuid",
  "username": "alice",
  "email": "alice@example.com",
  "role": "TENANT_ADMIN",
  "tenantId": "uuid",
  "tenantName": "Acme Corp",
  "enabled": true
}
```

Password is never returned.

### Validation rules (`UserManagementService`)
- `TENANT_ADMIN` must have a non-null `tenantId` referencing an existing tenant
- `ADMIN` must have null `tenantId`
- Cannot delete or disable your own account
- Cannot delete the last enabled `ADMIN` user (prevents lockout)
- Passwords hashed with BCrypt (same strength as existing seed)

### `AdminUser` entity update
- Add `tenantId UUID` field (nullable, `@Column(nullable = false)` **not** set — null is valid for ADMIN)
- Convert `String role` field to `Role` enum (mapped as `@Column` string — Liquibase data requires no change since existing value `"ADMIN"` matches the enum constant name)

---

## Section 5: Admin UI

### Auth context (`AuthContext.jsx`)

Expanded fields decoded from JWT on login/refresh:
- `role` — `"ADMIN"` or `"TENANT_ADMIN"`
- `tenantId` — UUID string or null (ADMIN)
- `currentTenant` — `{ id, name }` or null ("All Tenants" / TENANT_ADMIN's fixed tenant)

Persisted in `sessionStorage` alongside the token.

Frontend permission helper:
```js
const ROLE_PERMISSIONS = {
  ADMIN: ['GROUPS_READ', 'GROUPS_WRITE', /* ... all */],
  TENANT_ADMIN: ['GROUPS_READ', 'GROUPS_WRITE', 'POLICY_RULES_READ', /* ... no AI_CONFIG, TENANTS, USERS */],
};

function hasPermission(role, permission) {
  return ROLE_PERMISSIONS[role]?.includes(permission) ?? false;
}
```

### API client (`client.js`)

Reads `role` and `currentTenant` from auth context on every request:
- Role is `ADMIN` and `currentTenant` is set → adds `X-Tenant-Id: {currentTenant.id}` header
- Role is `ADMIN` and `currentTenant` is null ("All Tenants") → no header (backend enters admin mode)
- Role is `TENANT_ADMIN` → no header sent; backend reads tenantId from JWT claim

Note: `currentTenant` is still set in auth context for TENANT_ADMIN (to display the static label), but the API client checks role before deciding whether to send the header.

### Global tenant switcher (Sidebar)

Placed at the top of the sidebar, above the menu items.

**ADMIN:** Dropdown showing "All Tenants" + list of all tenant names. Selecting a tenant sets `currentTenant` in auth context (and `sessionStorage`). Selecting "All Tenants" clears it. All subsequent API calls automatically include or omit the header.

**TENANT_ADMIN:** Static label showing their tenant name. No dropdown. On login, `currentTenant` is set from the JWT `tenantId` and cannot be changed.

### Sidebar permission gating

Menu items visible based on `hasPermission(role, ...)`:

| Page | Required permission | ADMIN | TENANT_ADMIN |
|------|---------------------|-------|--------------|
| Tenants | TENANTS_READ | ✅ | ❌ |
| Policy Rules | POLICY_RULES_READ | ✅ | ✅ |
| Moderation Rules | MODERATION_RULES_READ | ✅ | ✅ |
| Flags | AUDIT_READ | ✅ | ✅ |
| Groups | GROUPS_READ | ✅ | ✅ |
| Audit Log | AUDIT_READ | ✅ | ✅ |
| Simulate Event | SIMULATE_WRITE | ✅ | ✅ |
| Telegram | TELEGRAM_READ | ✅ | ✅ |
| AI Config | AI_CONFIG_READ | ✅ | ❌ |
| Users | USERS_READ | ✅ | ❌ |

### New "Users" page (`/users`, ADMIN only)

Table columns: Username, Email, Role (badge), Tenant (name or "—"), Enabled (toggle).

Actions:
- **Add user** — modal: username, email, password, role selector, tenant dropdown (visible only when role = TENANT_ADMIN)
- **Edit** — modal: role, tenant, enabled (username/email not editable here)
- **Reset password** — modal: new password field
- **Delete** — confirmation dialog; blocked with error if self or last admin

### TENANT_ADMIN experience

- Lands on same dashboard as ADMIN
- Tenant switcher shows static label
- All list pages auto-filtered by backend (JWT-based)
- Tenant column hidden in create/edit modals (no tenant to choose — always their own)
- Restricted pages simply absent from sidebar

---

## What Does NOT Change

- Login endpoint contract (`POST /api/auth/token`) — same request shape, richer token
- Existing ADMIN users — `tenant_id` column is nullable; no data migration needed
- Telegram auth flow (phone → OTP → password) — unchanged, just now accessible to `TENANT_ADMIN`
- All other services (intent-classifier, policy-engine, etc.)
- Kafka pipeline

---

## Out of Scope

- Public self-service portal (external users, no EMCIP account required) → `documentation/POSSIBLE_DEVELOPMENT.md`
- Fine-grained per-resource permissions (e.g., read-only operator role)
- Tenant-level user limits or quotas
- SSO / OAuth2 / OIDC for admin login
