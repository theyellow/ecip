# Multi-Tenancy Enforcement Design

**Goal:** Enforce tenant isolation at the data access layer across all EMCIP services, eliminating the cross-tenant data leak that is currently possible.

**Architecture:** Tenant context flows in via HTTP header (`X-Tenant-Id`) or Kafka header (`tenant_id`). JPA services use Hibernate `@Filter` activated by an AOP aspect. R2DBC services use explicit tenant-scoped repository methods. The only bypass is in `admin-api`, where a JWT `ADMIN` role sets admin mode instead of a tenant — all other services always require a tenant.

**Tech Stack:** Java 21, Spring Boot 4, Hibernate `@Filter`, Spring AOP, Spring Data JPA, Spring Data R2DBC, `emcip-core` shared tenant infrastructure.

---

## Current State

### Infrastructure that exists but is not enforced

| Component | Location | Status |
|---|---|---|
| `TenantContext` | `emcip-core` | ThreadLocal storage — works |
| `TenantContextFilter` | `emcip-core` | Extracts HTTP header — works, but missing absent = 400 rejection |
| `TenantAwareKafkaSupport` | `emcip-core` | Helper methods — exist but not called anywhere |
| `tenant_id` DB columns | All services | Liquibase migrations applied |
| `tenant_id` Java fields | Most entities | **Missing from:** `Message`, `MessageThread`, `User`, `PolicyRuleConfig`, `PolicyDecision`, `PromptTemplate`, `ModelCostLog` |

### Security gap

`TenantContextFilter` currently silently allows requests with no `X-Tenant-Id` header — `TenantContext.getTenantId()` returns null and no tenant filtering occurs. Any caller that omits the header reads or writes across all tenants.

---

## Services and their data access stack

| Service | Stack | Has tenant_id | Needs bypass |
|---|---|---|---|
| `emcip-admin-api` | R2DBC | `GroupProfile`, `TelegramAccount` | Yes — admin JWT |
| `emcip-moderation-service` | R2DBC | `ModerationRule` | No |
| `emcip-audit-service` | R2DBC | `AuditEventEntity` | No |
| `emcip-conversation-context` | JPA | `Message`, `MessageThread`, `User` | No |
| `emcip-policy-engine` | JPA | `PolicyRuleConfig`, `PolicyDecision` | No |
| `emcip-llm-orchestrator` | JPA | `PromptTemplate`, `ModelCostLog` | No |

---

## Design

### Section 1: `emcip-core` changes

#### `TenantContext`

Add a second `ThreadLocal<Boolean>` for admin mode. The existing API is unchanged.

```java
// New additions only
private static final ThreadLocal<Boolean> ADMIN_MODE = new ThreadLocal<>();

public static void setAdminMode(boolean admin) {
    ADMIN_MODE.set(admin);
}

public static boolean isAdminMode() {
    return Boolean.TRUE.equals(ADMIN_MODE.get());
}

// clear() updated to also reset ADMIN_MODE
public static void clear() {
    CURRENT_TENANT.remove();
    ADMIN_MODE.remove();
}
```

#### `TenantContextFilter` (servlet — used by JPA services)

Reject requests with no tenant header:

```java
String tenantId = request.getHeader(TenantContext.HEADER_NAME);
if (tenantId == null || tenantId.isBlank()) {
    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
            "X-Tenant-Id header is required");
    return;
}
TenantContext.setTenantId(tenantId);
```

No admin bypass in this filter — internal JPA services always require a tenant.

---

### Section 2: `emcip-admin-api` — `AdminTenantContextFilter`

`admin-api` is reactive (WebFlux) and already has Spring Security with JWT. A new `AdminTenantContextFilter` (implementing `WebFilter`) replaces the generic `TenantContextFilter` in this service.

Logic:
1. `X-Tenant-Id` header present → `TenantContext.setTenantId(value)`, proceed
2. Header absent + authenticated user has role `ADMIN` → `TenantContext.setAdminMode(true)`, proceed
3. Header absent + no ADMIN role → respond 400 `X-Tenant-Id header is required`

The filter reads the role from the Spring Security `ReactiveSecurityContextHolder` — the JWT is already validated by the security chain, so no re-validation is needed.

All existing admin-api service layer methods that call repositories are updated to branch on `TenantContext.isAdminMode()`:
- Admin mode → call existing unscoped method (e.g. `findAll()`, `findById(id)`)
- Tenant mode → call new scoped method (e.g. `findAllByTenantId(tenantId)`, `findByIdAndTenantId(id, tenantId)`)

Affected repositories: `GroupProfileRepository`, `TelegramAccountRepository`, `AccountWatchedGroupRepository`.

---

### Section 3: JPA services — Hibernate `@Filter`

Applies to: `emcip-conversation-context`, `emcip-policy-engine`, `emcip-llm-orchestrator`.

#### Entity changes

Add `tenantId` Java field where missing, and add `@FilterDef` + `@Filter` to all tenant-scoped entities:

```java
@FilterDef(name = "tenantFilter",
           parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Entity
public class Message {
    // ...
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
}
```

Entities to update:

| Service | Entity |
|---|---|
| `emcip-conversation-context` | `Message`, `MessageThread`, `User` |
| `emcip-policy-engine` | `PolicyRuleConfig`, `PolicyDecision` |
| `emcip-llm-orchestrator` | `PromptTemplate`, `ModelCostLog` |

#### `TenantFilterAspect` (one per JPA service, or shared in `emcip-core`)

An `@Aspect` that intercepts all `@Repository` beans before execution:

```java
@Aspect
@Component
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Before("within(@org.springframework.stereotype.Repository *)")
    public void applyTenantFilter() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("tenantFilter")
                   .setParameter("tenantId", UUID.fromString(tenantId));
        }
    }
}
```

Since `TenantContextFilter` now always rejects requests without a tenant, `getTenantId()` is guaranteed non-null for all HTTP-originated calls. Kafka-originated calls are covered by Section 5.

---

### Section 4: R2DBC services — explicit scoped methods

Applies to: `emcip-moderation-service`, `emcip-audit-service`.

No admin bypass in these services. `TenantContextFilter` guarantees a tenant is present for all HTTP calls.

Add tenant-scoped repository methods:

```java
// ModerationRuleRepository
Flux<ModerationRule> findAllByTenantId(UUID tenantId);
Mono<ModerationRule> findByIdAndTenantId(Long id, UUID tenantId);
Flux<ModerationRule> findByEnabledTrueAndTenantId(UUID tenantId);

// AuditEventRepository
Flux<AuditEventEntity> findAllByTenantId(UUID tenantId);
Mono<AuditEventEntity> findByIdAndTenantId(Long id, UUID tenantId);
```

Update all service layer methods to pass `UUID.fromString(TenantContext.getTenantId())` to the scoped variants.

`AuditEventConsumer` (Kafka) calls `TenantAwareKafkaSupport.bindTenantFromRecord(record)` at the start of each listener and `TenantContext.clear()` in a finally block. If the Kafka header is absent (legacy producers), log a warning and skip tenant filtering — audit events are not dropped.

---

### Section 5: Kafka tenant propagation

Wire up the existing (but unused) `TenantAwareKafkaSupport` methods.

#### Producers — add tenant header

| Producer | Location |
|---|---|
| `TelegramEventPublisher` | `emcip-tdlib-adapter` |

Pattern:
```java
ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
TenantAwareKafkaSupport.addTenantHeader(record);
kafkaTemplate.send(record);
```

#### Consumers — bind tenant from header

| Consumer | Location |
|---|---|
| `AuditEventConsumer` | `emcip-audit-service` |
| `PolicyDecisionConsumer` | `emcip-llm-orchestrator` |
| Any other `@KafkaListener` methods processing tenant-scoped data |

Pattern:
```java
@KafkaListener(...)
public void onMessage(ConsumerRecord<String, String> record) {
    try {
        TenantAwareKafkaSupport.bindTenantFromRecord(record);
        // process...
    } finally {
        TenantContext.clear();
    }
}
```

---

## Bypass summary

| Caller type | Bypass allowed | Mechanism |
|---|---|---|
| HTTP request to admin-api with `X-Tenant-Id` | No | Normal tenant mode |
| HTTP request to admin-api without header + ADMIN JWT | Yes | `TenantContext.setAdminMode(true)` |
| HTTP request to admin-api without header + no ADMIN JWT | No | 400 rejected |
| HTTP request to any other service without `X-Tenant-Id` | No | 400 rejected by `TenantContextFilter` |
| Kafka message without `tenant_id` header | Partial | Warning logged, audit events not dropped; JPA services skip filter |

---

## What is NOT in scope

- Row-level security at the PostgreSQL level (redundant given application-level enforcement)
- Tenant provisioning or onboarding flow
- Admin UI cross-tenant views (reads go through admin-api which already has the bypass)
- `ModelConfig` and `LlmProviderConfig` in `emcip-llm-orchestrator` — these are global admin-managed config, not per-tenant data, so they intentionally have no `tenant_id`
