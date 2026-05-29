# Users Page v2 Redesign — Design Spec

**Goal:** Convert the Users page from inline styles + raw HTML to DataTable + shared components with v2 design tokens.

**Approach:** Full rewrite — the current page uses no CSS module, no shared components, and no tests. Convert to DataTable, shared Modal/Button/Badge, v2 CSS module. Add tests.

---

## 1. Page Structure

DataTable conversion. System-id:

`◉ admin-api · {active}/{total} active`

Where `active` = count of users with `enabled === true`.

---

## 2. Table Columns

| Column | Key | Options | Notes |
|--------|-----|---------|-------|
| Username | `username` | mono: true, width: 140 | |
| Email | `email` | mono: true | |
| Role | `role` | width: 110, render: Badge | ADMIN=red, TENANT_ADMIN=yellow |
| Tenant | `tenantName` | | Display `tenantName`, show em-dash if null |
| Enabled | `enabled` | width: 80, render: Badge | true=green "ON", false=gray "OFF" |
| Password | (custom) | width: 80, render: Button | "Password" secondary button, stopPropagation |

**Row interactions:**
- Row click → opens Edit User modal (`onEdit`)
- Delete button → confirm + delete (`onDelete`)
- Password button → opens Password Reset modal (custom render column)

---

## 3. Edit/Create User Modal

Shared `Modal` with `onSubmit`. Title: "Add User" or `Edit · {username}`.

**Fields:**
- Username (text, disabled on edit)
- Email (text)
- Password (password, only shown on create)
- Role (select: ADMIN, TENANT_ADMIN)
- Tenant (select: loaded from tenantsApi, only shown when role=TENANT_ADMIN)

All fields use v2 `.field`/`.input` pattern. Select uses same `.input` class.

---

## 4. Password Reset Modal

Shared `Modal` with `onSubmit`. Title: `Reset Password · {username}`.

Single field: New Password (password input, v2 styled).

---

## 5. CSS Module

New file `Users.module.css` with v2 field/input styles (same pattern as Telegram/ModerationRules).

---

## 6. Tests

New test file — 8 tests covering:
1. Empty table state
2. User row with badges for role and enabled
3. OFF badge for disabled user
4. Create modal opens
5. Create user flow
6. Edit modal opens with prefilled values (row click)
7. Password modal opens
8. Delete with confirmation

---

## 7. Excluded

- Design handoff roles (MODERATOR, ANALYST, VIEWER) — production only has ADMIN/TENANT_ADMIN
- `lastLogin` / `createdAt` columns — not in production API response
- `active` checkbox in modal — production uses `enabled` field but current UI doesn't expose toggle (keep as-is)
