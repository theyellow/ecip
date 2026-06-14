# #40 — SC8 Resilience Follow-ons

**Date**: 2026-06-14
**Status**: Draft
**Addresses**: Backlog #40 — two gaps after PR #73 (circuit breakers)

---

## Goal

Add retry with exponential backoff to all admin-api downstream calls, and per-service fallback responses on read (GET) operations so the UI stays navigable when a downstream service is temporarily unavailable.

## Current State

- 5 circuit breaker instances configured in `application.yml` (policy-engine, audit-service, moderation-service, tdlib-adapter, orchestrator)
- All use the same default config: sliding window 10, 50% failure threshold, 30s wait in open state
- `GlobalExceptionHandler` catches `CallNotPermittedException` → 503
- No Resilience4j retry configured — only a manual `Retry.backoff()` in `TelegramAccountService.pushWatchedGroups()` (unrelated)
- When a downstream is down, all pages that depend on it show error screens

## Design

### 1. Retry with exponential backoff

Add `resilience4j.retry` configuration to `emcip-admin-api/src/main/resources/application.yml`:

```yaml
resilience4j:
  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 500ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        retryExceptions:
          - org.springframework.web.reactive.function.client.WebClientRequestException
          - org.springframework.web.reactive.function.client.WebClientResponseException$ServiceUnavailable
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - org.springframework.web.reactive.function.client.WebClientResponseException$BadRequest
          - org.springframework.web.reactive.function.client.WebClientResponseException$NotFound
          - org.springframework.web.reactive.function.client.WebClientResponseException$Forbidden
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

Each client injects `RetryRegistry` alongside `CircuitBreakerRegistry` and applies retry **before** the circuit breaker in the reactive chain:

```java
.retryWhen(reactor.util.retry.Retry.from(retryOperator))
.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
```

Since Resilience4j's `RetryOperator` for Reactor requires building from the registry, each client will use `io.github.resilience4j.reactor.retry.RetryOperator.of(retry)` applied via `.transformDeferred()`.

The chain order is: **retry → circuit breaker → (fallback on reads)**. This means:
- A transient failure gets retried up to 3 times
- If all retries fail, the circuit breaker records it as a failure
- If the circuit is open, `CallNotPermittedException` fires immediately (no retry)

### 2. Fallbacks on read operations

When retries are exhausted and the circuit breaker trips (or is already open), read endpoints return empty/degraded responses instead of propagating 503 to the UI.

| Client | Method | Fallback response |
|--------|--------|-------------------|
| `AuditServiceClient` | `listEvents()` | `{ "items": [], "total": 0, "page": 0, "size": 50 }` |
| `PolicyEngineClient` | `listRules()` | `[]` (empty array) |
| `PolicyEngineClient` | `listDecisions()` | `{ "items": [], "total": 0, "page": 0, "size": 50 }` |
| `ModerationServiceClient` | `listRules()` | `[]` (empty array) |

**No fallback** (503 propagates) for:
- `PolicyEngineClient.getDecision()` — single-item fetch, no sensible empty fallback
- `PolicyEngineClient.updateDecision()`, `updateDecisionStatus()` — write operations
- `PolicyEngineClient.createRule()`, `updateRule()`, `deleteRule()` — write operations
- `ModerationServiceClient.createRule()`, `updateRule()`, `deleteRule()` — write operations

Fallbacks are implemented via `.onErrorResume()` at the end of the reactive chain, after the circuit breaker. Each client has a private helper method to build the fallback `JsonNode`:

```java
private Mono<JsonNode> emptyPage() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.putArray("items");
    node.put("total", 0L);
    node.put("page", 0);
    node.put("size", 50);
    return Mono.just(node);
}
```

The fallback logs a warning so operators can see degraded state in logs:

```java
.onErrorResume(e -> {
    log.warn("Fallback: returning empty response for listEvents ({})", e.getMessage());
    return emptyPage();
})
```

### 3. Retry dependency

Add `resilience4j-retry` to the existing dependencies. Actually, `resilience4j-spring-boot3` already bundles retry support — no new dependency needed. Just add the YAML config and inject `RetryRegistry`.

## Affected files

| File | Change |
|------|--------|
| `emcip-admin-api/src/main/resources/application.yml` | Add `resilience4j.retry` config section |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java` | Add retry + fallback on `listEvents()` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/client/PolicyEngineClient.java` | Add retry to all methods + fallback on `listRules()`, `listDecisions()` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/client/ModerationServiceClient.java` | Add retry to all methods + fallback on `listRules()` |
| `emcip-admin-api/src/test/java/io/emcip/admin/api/client/AuditServiceClientRetryTest.java` | Test retry behavior + fallback |
| `emcip-admin-api/src/test/java/io/emcip/admin/api/client/PolicyEngineClientRetryTest.java` | Test retry behavior + fallback |
| `emcip-admin-api/src/test/java/io/emcip/admin/api/client/ModerationServiceClientRetryTest.java` | Test retry behavior + fallback |

## Not in scope

- Per-service custom retry/CB tuning (all share defaults — tune later based on production metrics)
- Fallback caching (returning last-known-good data)
- UI indicators for degraded state (pages just show empty tables)
- TdlibAdapterClient or OrchestratorClient changes (these are not used by list pages)
