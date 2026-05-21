# SC6 · SC7 · SC8 Design

**Date**: 2026-05-20
**Status**: Approved
**Addresses**: A5 (pagination), S11 (JWT expiry / refresh), A7 + G7 (circuit breakers)

---

## SC6 — Pagination Enforcement

### Goal

Replace unbounded `Flux` responses on list endpoints with `PageResponse<T>` (items + total count). Cap `size` at 200 in all tiers.

### Shared DTO — `emcip-core`

```java
// io.emcip.common.pagination.PageResponse
public record PageResponse<T>(List<T> items, long total, int page, int size) {}
```

Added to `emcip-core` so all services can reference it.

### policy-engine — `PolicyDecisionController`

- Accept `page` (default 0) and `size` (default 50, max 200) query params.
- Use existing `findAllByOrderByTimestampDesc(Pageable)` for items.
- Use `repository.count()` (or a filtered variant) for total.
- Return `Mono<PageResponse<PolicyDecision>>`.
- Filtered-by-decision path: add `Page<PolicyDecision> findByDecisionOrderByTimestampDesc(String decision, Pageable pageable)` to the repository.

### policy-engine — `PolicyRuleController`

- `listActive()` stays flat (`List<PolicyRuleConfig>`) — config data, small by design.
- Add `.take(200)` as an OOM safety net.

### audit-service — `AuditController`

- Add `page` (default 0) and `size` (default 50, max 200) query params to `GET /api/audit/events`.
- Items: `@Query` with `LIMIT :size OFFSET :offset` (R2DBC does not support `Pageable` on `ReactiveCrudRepository` without `ReactiveSortingRepository`; use explicit `@Query` params).
- Total: matching `@Query("SELECT COUNT(*) FROM …")` method.
- Return `Mono<PageResponse<AuditEventEntity>>`.
- New repository methods required:
  - `Flux<AuditEventEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant from, Instant to, Pageable pageable)` — extend repo to `ReactiveSortingRepository`, or use `@Query` with `:size`/`:offset` params.
  - `Mono<Long> countByCreatedAtBetween(Instant from, Instant to)`
  - Tenant-scoped variants of each.

### admin-api — clients and controllers

- `AuditServiceClient.listEvents()`: switches return type from `Flux<JsonNode>` to `Mono<JsonNode>` (the response is now a page object).
- `PolicyEngineClient.listFlags()` / `listDecisions()`: same switch.
- `AuditController` and `FlagController` in admin-api: expose `page` + `size` params; clamp `size` to max 200 before forwarding.

---

## SC7 — Refresh Token + Session Fix

### Goal

Reduce JWT access token to 1 h, add opaque refresh token (7-day, single-use rotation), and fix the broken-session UX in the frontend.

### Database — new Liquibase migration

File: `emcip-admin-api/src/main/resources/db/changelog/changes/011-refresh-tokens.xml`

```
refresh_tokens
  id          BIGSERIAL PK
  user_id     BIGINT NOT NULL FK → admin_users(id)
  token_hash  VARCHAR(255) NOT NULL
  expires_at  TIMESTAMPTZ NOT NULL
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
  revoked     BOOLEAN NOT NULL DEFAULT FALSE
```

Index on `token_hash`. Index on `(user_id, revoked)` for cleanup queries.

### `JwtService` changes

- `EXPIRY_MS`: 8 h → 1 h.
- New constant: `REFRESH_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L`.

### New classes

**`RefreshToken` entity** (R2DBC `@Table("refresh_tokens")`): fields mirror the table above.

**`RefreshTokenRepository`** (`ReactiveCrudRepository<RefreshToken, Long>`):
- `Mono<RefreshToken> findByTokenHash(String hash)`
- `Flux<RefreshToken> findByUserId(Long userId)`
- `Mono<Void> deleteByExpiresAtBefore(Instant cutoff)` — for cleanup

**`RefreshTokenService`**:
- `issue(Long userId) → Mono<String>` — generates `UUID`, bcrypt-hashes it, persists, returns raw token.
- `rotate(String rawToken) → Mono<String>` — validates hash + not revoked + not expired; marks old token revoked; issues new token. Returns new raw token.
- `revoke(String rawToken) → Mono<Void>` — marks token revoked.

### `TokenResponse` DTO

```java
public record TokenResponse(String token, Instant expiresAt, String refreshToken) {}
```

### `AuthService`

`authenticate()` calls `RefreshTokenService.issue()` and includes the raw refresh token in `TokenResponse`.

### `AuthController` — new endpoints

```
POST /api/auth/refresh
  Body: { "refreshToken": "..." }
  Returns: TokenResponse (new access JWT + new refresh token)
  Errors: 401 if invalid/expired/revoked

POST /api/auth/logout
  Body: { "refreshToken": "..." }
  Returns: 204 No Content
```

### `SecurityConfig`

Permit `/api/auth/refresh` and `/api/auth/logout` without JWT (alongside existing `/api/auth/token`).

### Frontend — auth interceptor (`emcip-admin-ui`)

- On any API response with status 401:
  1. Attempt `POST /api/auth/refresh` with stored refresh token.
  2. If success: store new access + refresh tokens, replay the original request.
  3. If failure (400/401 from refresh): clear both tokens from storage, redirect to `/login`.
- On hard reload: expired access token → 401 on first call → interceptor fires → refresh succeeds → user stays logged in.
- Store `refreshToken` in `localStorage` alongside the existing access token (same security boundary as current setup).

---

## SC8 — Circuit Breakers (Resilience4j)

### Goal

Prevent cascading failures when a downstream service is unavailable. Open circuit returns 503 immediately; re-probes after 30 s.

### Dependencies — `emcip-admin-api/pom.xml`

```xml
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-reactor</artifactId>
</dependency>
```

No `<version>` — managed by parent pom.

### `application.yml` configuration

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
        registerHealthIndicator: true
    instances:
      policy-engine:
        baseConfig: default
      audit-service:
        baseConfig: default
      moderation-service:
        baseConfig: default
      tdlib-adapter:
        baseConfig: default
      orchestrator:
        baseConfig: default
```

### Client classes

Each client constructor injects `CircuitBreakerRegistry` and creates a named `CircuitBreaker`. Every reactive call appends:

```java
.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
```

Affected clients: `PolicyEngineClient`, `AuditServiceClient`, `ModerationServiceClient`.
Affected `WebClient` bean usage: `tdlibWebClient` in `TelegramAccountService`, `orchestratorWebClient` in `AIProxyController`.

### `GlobalExceptionHandler`

New handler:

```java
@ExceptionHandler(CallNotPermittedException.class)
ResponseEntity<Map<String,String>> handleCircuitOpen(CallNotPermittedException ex) {
    return ResponseEntity.status(503)
        .body(Map.of("error", "Service temporarily unavailable"));
}
```

### No fallbacks

Errors surface as 503. Circuit re-closes automatically after the 30 s half-open probe succeeds.

---

## Affected modules summary

| Module | SC6 | SC7 | SC8 |
|---|---|---|---|
| `emcip-core` | `PageResponse<T>` DTO | — | — |
| `emcip-policy-engine` | Paginate decisions endpoint | — | — |
| `emcip-audit-service` | Paginate events endpoint | — | — |
| `emcip-admin-api` | Clamp size, update clients | Refresh token (DB, service, endpoints), JWT 1h, Liquibase migration | Resilience4j deps + config, wrap clients |
| `emcip-admin-ui` | — | Auth interceptor | — |

## Not in scope

- Pagination on `PolicyRuleController.listActive()` beyond the `.take(200)` safety cap.
- Refresh token cleanup job (expired tokens accumulate; a scheduled `deleteByExpiresAtBefore` can be added later).
- Retry logic on circuit-broken calls (30 s half-open re-probe is sufficient for now).
- Fallback responses for open circuits.
