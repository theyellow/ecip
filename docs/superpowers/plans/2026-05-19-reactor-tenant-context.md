# SC3: Replace ThreadLocal Tenant with Reactor Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `TenantContext` ThreadLocal reads in all WebFlux services with Reactor Context propagation, eliminating the risk of tenant context loss or cross-contamination when Reactor switches threads between operators.

**Architecture:** A new `ReactorTenantContext` utility (in `emcip-core`) provides keys and helpers for writing tenant info into Reactor `Context` via `contextWrite()` in WebFilters, and reading it via `Flux/Mono.deferContextual()` in services and controllers. The existing `TenantContext` ThreadLocal is left untouched — it remains correct for Kafka consumer handlers (`AuditEventConsumer`, `ModerationEventConsumer`) which run on dedicated listener threads where thread-per-message is guaranteed.

**Tech Stack:** Java 21, Spring Boot 4, Spring WebFlux, `reactor-core` (`Context`, `ContextView`, `Flux.deferContextual`, `Mono.deferContextual`), JUnit 5 + Mockito + StepVerifier

---

## Context

### What is broken today

`TenantContext` stores tenant info in `ThreadLocal<String>`. In WebFlux (Spring's reactive framework), Reactor can hand off work between threads between operators (e.g. after a `.flatMap()`). The ThreadLocal value set on thread A is invisible on thread B. This means:

- A request arrives → filter sets `TenantContext.setTenantId("acme")` on thread A
- Service method reads `TenantContext.getTenantId()` on thread B (after a `.flatMap()`) → returns `null`
- Query goes unscoped, potentially returning another tenant's data

### What stays unchanged

The Kafka consumer handlers in `AuditEventConsumer` and `ModerationEventConsumer` use `TenantAwareKafkaSupport.bindTenantFromRecord(record)` followed by `TenantContext.getTenantId()`. These handlers are invoked on a dedicated Kafka listener thread pool where one thread processes one record completely before picking up another. ThreadLocal is correct here — **do not modify these files**.

`TenantContext.java`, `TenantContextFilter.java`, and `TenantAwareKafkaSupport.java` in `emcip-core` are **not modified** by this plan.

### Modules affected

| Module | Files changed |
|--------|--------------|
| `emcip-core` | `pom.xml` (add `reactor-core`), new `ReactorTenantContext.java` |
| `emcip-admin-api` | `AdminTenantContextFilter`, `TenantWebFilter`, `GroupProfileService`, `TelegramAccountService`, `TelegramAccountController` + their tests |
| `emcip-audit-service` | new `TenantWebFilter`, `AuditService` + its test |
| `emcip-moderation-service` | new `TenantWebFilter`, `ModerationRuleController` + its test |

### Run tests

```bash
mvn test -pl emcip-core -q 2>&1 | tail -5
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
mvn test -pl emcip-audit-service -q 2>&1 | tail -10
mvn test -pl emcip-moderation-service -q 2>&1 | tail -10
```

### Apply Spotless

```bash
mvn spotless:apply -pl emcip-core
mvn spotless:apply -pl emcip-admin-api
mvn spotless:apply -pl emcip-audit-service
mvn spotless:apply -pl emcip-moderation-service
```

---

## File Structure

**New files:**

| File | Purpose |
|------|---------|
| `emcip-core/src/main/java/io/emcip/common/tenant/ReactorTenantContext.java` | Context keys + `withTenant` / `withAdminMode` write helpers + `getTenantId` / `isAdminMode` read helpers |
| `emcip-core/src/test/java/io/emcip/common/tenant/ReactorTenantContextTest.java` | Unit tests for the utility |
| `emcip-audit-service/src/main/java/io/emcip/audit/service/config/TenantWebFilter.java` | WebFilter for audit-service: reads X-Tenant-Id header → writes to Reactor Context |
| `emcip-moderation-service/src/main/java/io/emcip/moderation/service/config/TenantWebFilter.java` | Same for moderation-service |

**Files to modify:**

| File | Change |
|------|--------|
| `emcip-core/pom.xml` | Add `reactor-core` optional dependency |
| `emcip-admin-api/.../security/AdminTenantContextFilter.java` | Replace ThreadLocal set/clear with `contextWrite` |
| `emcip-admin-api/.../filter/TenantWebFilter.java` | Replace ThreadLocal set/clear with `contextWrite` |
| `emcip-admin-api/.../service/GroupProfileService.java` | Replace `TenantContext.*` calls with `Flux/Mono.deferContextual` |
| `emcip-admin-api/.../service/TelegramAccountService.java` | Same for `findAll`, `create`, `delete` |
| `emcip-admin-api/.../controller/TelegramAccountController.java` | Replace `TenantContext.*` in `createAccount` with `Mono.deferContextual` |
| `emcip-admin-api/.../service/GroupProfileServiceTest.java` | Replace `TenantContext` setup with `.contextWrite(...)` on StepVerifier |
| `emcip-admin-api/.../service/TelegramAccountServiceTest.java` | Same |
| `emcip-audit-service/.../service/AuditService.java` | Replace `TenantContext.*` reads with `Flux/Mono.deferContextual` |
| `emcip-audit-service/.../service/AuditServiceTest.java` | Already works without context (tests non-tenant path); no change needed |
| `emcip-moderation-service/.../controller/ModerationRuleController.java` | Replace inline `TenantContext.getTenantId()` calls with `Flux/Mono.deferContextual` |
| `emcip-moderation-service/.../controller/ModerationRuleControllerTest.java` | Replace inline WebFilter to use `contextWrite` instead of ThreadLocal |

---

## Task 1: `ReactorTenantContext` in emcip-core

**Files:**
- Create: `emcip-core/src/main/java/io/emcip/common/tenant/ReactorTenantContext.java`
- Create: `emcip-core/src/test/java/io/emcip/common/tenant/ReactorTenantContextTest.java`
- Modify: `emcip-core/pom.xml`

- [ ] **Step 1: Add `reactor-core` optional dependency to `emcip-core/pom.xml`**

In `emcip-core/pom.xml`, add after the `<artifactId>spring-web</artifactId>` optional block:

```xml
    <!-- Reactor Core (optional - for ReactorTenantContext in WebFlux services) -->
    <dependency>
      <groupId>io.projectreactor</groupId>
      <artifactId>reactor-core</artifactId>
      <optional>true</optional>
    </dependency>
```

- [ ] **Step 2: Write failing `ReactorTenantContextTest`**

Create `emcip-core/src/test/java/io/emcip/common/tenant/ReactorTenantContextTest.java`:

```java
package io.emcip.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.util.context.Context;

class ReactorTenantContextTest {

    @Test
    void withTenant_populatesTenantId() {
        Context ctx = ReactorTenantContext.withTenant(Context.empty(), "tenant-abc");
        assertThat(ReactorTenantContext.getTenantId(ctx)).isEqualTo("tenant-abc");
        assertThat(ReactorTenantContext.isAdminMode(ctx)).isFalse();
    }

    @Test
    void withAdminMode_setsAdminModeTrue() {
        Context ctx = ReactorTenantContext.withAdminMode(Context.empty());
        assertThat(ReactorTenantContext.isAdminMode(ctx)).isTrue();
        assertThat(ReactorTenantContext.getTenantId(ctx)).isNull();
    }

    @Test
    void getTenantId_missingKey_returnsNull() {
        assertThat(ReactorTenantContext.getTenantId(Context.empty())).isNull();
    }

    @Test
    void isAdminMode_missingKey_returnsFalse() {
        assertThat(ReactorTenantContext.isAdminMode(Context.empty())).isFalse();
    }

    @Test
    void withTenant_overridesAdminMode() {
        Context base = ReactorTenantContext.withAdminMode(Context.empty());
        Context updated = ReactorTenantContext.withTenant(base, "tenant-xyz");
        assertThat(ReactorTenantContext.getTenantId(updated)).isEqualTo("tenant-xyz");
        assertThat(ReactorTenantContext.isAdminMode(updated)).isFalse();
    }
}
```

- [ ] **Step 3: Run test to confirm failure**

```bash
mvn test -pl emcip-core -Dtest=ReactorTenantContextTest -q 2>&1 | tail -5
```

Expected: FAIL with `ClassNotFoundException` or `cannot find symbol`

- [ ] **Step 4: Implement `ReactorTenantContext`**

Create `emcip-core/src/main/java/io/emcip/common/tenant/ReactorTenantContext.java`:

```java
package io.emcip.common.tenant;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Reactor Context keys and helpers for propagating tenant information in reactive pipelines.
 *
 * <p>Use {@link #withTenant} / {@link #withAdminMode} in WebFilters via
 * {@code chain.filter(exchange).contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, id))},
 * and {@link #getTenantId} / {@link #isAdminMode} in services via
 * {@code Mono.deferContextual(ctx -> ...)} / {@code Flux.deferContextual(ctx -> ...)}.
 *
 * <p>The blocking {@link TenantContext} ThreadLocal is unchanged and remains correct for Kafka
 * consumer handlers and servlet-based services where thread-per-request is guaranteed.
 */
public final class ReactorTenantContext {

    public static final String TENANT_ID_KEY = "emcip.tenantId";
    public static final String ADMIN_MODE_KEY = "emcip.adminMode";

    private ReactorTenantContext() {}

    /**
     * Returns a new context with tenantId set and adminMode=false.
     * Use as: {@code chain.filter(exchange).contextWrite(ctx -> withTenant(ctx, tenantId))}
     */
    public static Context withTenant(Context ctx, String tenantId) {
        return ctx.put(TENANT_ID_KEY, tenantId).put(ADMIN_MODE_KEY, false);
    }

    /**
     * Returns a new context with adminMode=true.
     * Use as: {@code chain.filter(exchange).contextWrite(ReactorTenantContext::withAdminMode)}
     */
    public static Context withAdminMode(Context ctx) {
        return ctx.put(ADMIN_MODE_KEY, true);
    }

    /** Returns the tenantId from the context, or {@code null} if not set. */
    public static String getTenantId(ContextView ctx) {
        return ctx.getOrDefault(TENANT_ID_KEY, null);
    }

    /** Returns whether admin mode is active in the context. */
    public static boolean isAdminMode(ContextView ctx) {
        return ctx.getOrDefault(ADMIN_MODE_KEY, Boolean.FALSE);
    }
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
mvn test -pl emcip-core -q 2>&1 | tail -5
```

Expected: `Tests run: 5, Failures: 0` (5 new + existing TenantContextTest + TenantContextFilterTest)

Actually expected total count will be existing tests + 5 new ones. Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 6: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-core
git add emcip-core/
git commit -m "feat(core): add ReactorTenantContext for Reactor-safe tenant propagation"
```

---

## Task 2: Update `emcip-admin-api` filters

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/AdminTenantContextFilter.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/filter/TenantWebFilter.java`

No test changes in this task — the filters are tested indirectly through integration and controller tests. The `SecurityFilterChainTest` in admin-api covers the filter chain.

- [ ] **Step 1: Replace `AdminTenantContextFilter` to use Reactor Context**

Replace the full file `emcip-admin-api/src/main/java/io/emcip/admin/api/security/AdminTenantContextFilter.java`:

```java
package io.emcip.admin.api.security;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
public class AdminTenantContextFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId =
                exchange.getRequest().getHeaders().getFirst(TenantContext.HEADER_NAME);

        if (tenantId != null && !tenantId.isBlank()) {
            return chain.filter(exchange)
                    .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, tenantId));
        }

        return ReactiveSecurityContextHolder.getContext()
                .map(
                        ctx ->
                                ctx.getAuthentication() != null
                                        && ctx.getAuthentication().getAuthorities().stream()
                                                .anyMatch(
                                                        a ->
                                                                "ROLE_ADMIN".equals(
                                                                        a.getAuthority())))
                .defaultIfEmpty(false)
                .flatMap(
                        isAdmin -> {
                            if (isAdmin) {
                                return chain.filter(exchange)
                                        .contextWrite(
                                                ctx ->
                                                        ReactorTenantContext.withAdminMode(ctx));
                            }
                            log.debug(
                                    "Rejected request to {} — missing X-Tenant-Id and no ADMIN"
                                            + " role",
                                    exchange.getRequest().getPath());
                            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                            return exchange.getResponse().setComplete();
                        });
    }
}
```

Key changes:
- Removed `TenantContext.setTenantId(tenantId)` + `doFinally(signal -> TenantContext.clear())`
- Replaced with `.contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, tenantId))`
- Same for admin mode path

- [ ] **Step 2: Replace `TenantWebFilter` to use Reactor Context**

Replace the full file `emcip-admin-api/src/main/java/io/emcip/admin/api/filter/TenantWebFilter.java`:

```java
package io.emcip.admin.api.filter;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class TenantWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId =
                exchange.getRequest().getHeaders().getFirst(TenantContext.HEADER_NAME);
        if (tenantId != null && !tenantId.isBlank()) {
            return chain.filter(exchange)
                    .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, tenantId));
        }
        return chain.filter(exchange);
    }
}
```

- [ ] **Step 3: Run admin-api tests**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 0 failures. (Services still use ThreadLocal reads but tests set `TenantContext` directly in `@BeforeEach`/`@AfterEach`, so existing tests pass through the old ThreadLocal path while we haven't broken anything yet.)

- [ ] **Step 4: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "refactor(admin-api): use Reactor contextWrite in tenant filters"
```

---

## Task 3: Update `emcip-admin-api` services and controller

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/GroupProfileService.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/GroupProfileServiceTest.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/TelegramAccountService.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/TelegramAccountServiceTest.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java`

- [ ] **Step 1: Replace `GroupProfileService` to use `deferContextual`**

Replace the full file `emcip-admin-api/src/main/java/io/emcip/admin/api/service/GroupProfileService.java`:

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.common.tenant.ReactorTenantContext;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class GroupProfileService {

    private final GroupProfileRepository repository;

    public Flux<GroupProfile> findAll() {
        return Flux.deferContextual(
                ctx -> {
                    if (ReactorTenantContext.isAdminMode(ctx)) {
                        return repository.findAll();
                    }
                    return repository.findAllByTenantId(
                            UUID.fromString(ReactorTenantContext.getTenantId(ctx)));
                });
    }

    public Mono<GroupProfile> findByChatId(long chatId) {
        return Mono.deferContextual(
                ctx -> {
                    if (ReactorTenantContext.isAdminMode(ctx)) {
                        return repository
                                .findByTelegramChatId(chatId)
                                .switchIfEmpty(notFound(chatId));
                    }
                    return repository
                            .findByTelegramChatIdAndTenantId(
                                    chatId,
                                    UUID.fromString(ReactorTenantContext.getTenantId(ctx)))
                            .switchIfEmpty(notFound(chatId));
                });
    }

    public Mono<GroupProfile> create(GroupProfile profile) {
        return Mono.deferContextual(
                ctx -> {
                    profile.setCreatedAt(Instant.now());
                    profile.setUpdatedAt(Instant.now());
                    if (!ReactorTenantContext.isAdminMode(ctx)) {
                        profile.setTenantId(
                                UUID.fromString(ReactorTenantContext.getTenantId(ctx)));
                    }
                    return repository.save(profile);
                });
    }

    public Mono<GroupProfile> update(long chatId, GroupProfile patch) {
        return findByChatId(chatId)
                .flatMap(
                        existing -> {
                            existing.setName(patch.getName());
                            existing.setDescription(patch.getDescription());
                            existing.setModerationLevel(patch.getModerationLevel());
                            existing.setAutoRespond(patch.isAutoRespond());
                            existing.setWelcomeMessage(patch.getWelcomeMessage());
                            existing.setUpdatedAt(Instant.now());
                            return repository.save(existing);
                        });
    }

    public Mono<Void> delete(long chatId) {
        return findByChatId(chatId).flatMap(repository::delete);
    }

    private <T> Mono<T> notFound(long chatId) {
        return Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found: " + chatId));
    }
}
```

Note: `update` and `delete` call `findByChatId` which already uses `deferContextual` — the Reactor Context flows naturally through the chain. No additional `deferContextual` needed there.

- [ ] **Step 2: Update `GroupProfileServiceTest` to use Reactor Context instead of ThreadLocal**

Replace the full file `emcip-admin-api/src/test/java/io/emcip/admin/api/service/GroupProfileServiceTest.java`:

```java
package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.common.tenant.ReactorTenantContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GroupProfileServiceTest {

    @Mock private GroupProfileRepository repository;

    private GroupProfileService service;

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID TENANT_UUID = UUID.fromString(TENANT_ID);

    @BeforeEach
    void setUp() {
        service = new GroupProfileService(repository);
    }

    private GroupProfile profile(Long chatId) {
        return GroupProfile.builder().id(1L).telegramChatId(chatId).name("Test Group").build();
    }

    @Test
    void findAll_adminMode_returnsAll() {
        when(repository.findAll()).thenReturn(Flux.just(profile(100L), profile(200L)));

        StepVerifier.create(
                        service.findAll()
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .expectNextCount(2)
                .verifyComplete();

        verify(repository).findAll();
    }

    @Test
    void findAll_tenantMode_scopesToTenant() {
        when(repository.findAllByTenantId(TENANT_UUID)).thenReturn(Flux.just(profile(100L)));

        StepVerifier.create(
                        service.findAll()
                                .contextWrite(
                                        ctx -> ReactorTenantContext.withTenant(ctx, TENANT_ID)))
                .expectNextCount(1)
                .verifyComplete();

        verify(repository).findAllByTenantId(TENANT_UUID);
    }

    @Test
    void create_setsTimestamps() {
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        GroupProfile input = profile(123L);

        StepVerifier.create(
                        service.create(input)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .assertNext(
                        saved -> {
                            assertThat(saved.getCreatedAt()).isNotNull();
                            assertThat(saved.getUpdatedAt()).isNotNull();
                        })
                .verifyComplete();
    }

    @Test
    void findByChatId_notFound_returns404() {
        when(repository.findByTelegramChatId(999L)).thenReturn(Mono.empty());

        StepVerifier.create(
                        service.findByChatId(999L)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .expectErrorSatisfies(
                        ex -> {
                            assertThat(ex).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) ex).getStatusCode())
                                    .isEqualTo(HttpStatus.NOT_FOUND);
                        })
                .verify();
    }

    @Test
    void update_mergesFields() {
        GroupProfile existing = profile(123L);
        when(repository.findByTelegramChatId(123L)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        GroupProfile patch =
                GroupProfile.builder()
                        .name("Updated")
                        .description("New desc")
                        .moderationLevel("HIGH")
                        .autoRespond(true)
                        .welcomeMessage("Welcome!")
                        .build();

        StepVerifier.create(
                        service.update(123L, patch)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .assertNext(
                        result -> {
                            assertThat(result.getName()).isEqualTo("Updated");
                            assertThat(result.getDescription()).isEqualTo("New desc");
                            assertThat(result.getModerationLevel()).isEqualTo("HIGH");
                            assertThat(result.isAutoRespond()).isTrue();
                            assertThat(result.getWelcomeMessage()).isEqualTo("Welcome!");
                            assertThat(result.getUpdatedAt()).isNotNull();
                        })
                .verifyComplete();
    }
}
```

Key changes from the old test:
- Removed `import io.emcip.common.tenant.TenantContext`
- Removed `@AfterEach void clearTenantContext() { TenantContext.clear(); }`
- Every `StepVerifier.create(service.someMethod())` now chains `.contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx))` or `.contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, TENANT_ID))`

- [ ] **Step 3: Run GroupProfileService tests**

```bash
mvn test -pl emcip-admin-api -Dtest=GroupProfileServiceTest -q 2>&1 | tail -5
```

Expected: `Tests run: 5, Failures: 0`

- [ ] **Step 4: Update `TelegramAccountService` to use `deferContextual`**

Read `TelegramAccountService.java` fully first. Then replace only the three methods that read `TenantContext`:

**Replace `findAll()`:**
```java
public Flux<TelegramAccount> findAll() {
    return Flux.deferContextual(
            ctx -> {
                if (ReactorTenantContext.isAdminMode(ctx)) {
                    return repository.findAll();
                }
                return repository.findAllByTenantId(
                        UUID.fromString(ReactorTenantContext.getTenantId(ctx)));
            });
}
```

**Replace `create(String phoneNumber, String displayName, UUID tenantId)`:**
```java
public Mono<TelegramAccount> create(String phoneNumber, String displayName, UUID tenantId) {
    return Mono.deferContextual(
            ctx -> {
                TelegramAccount account =
                        TelegramAccount.builder()
                                .id(UUID.randomUUID())
                                .phoneNumber(phoneNumber)
                                .apiId(telegramApiId)
                                .apiHash(telegramApiHash)
                                .displayName(displayName)
                                .status(TelegramAccountStatus.UNCONFIGURED)
                                .createdAt(Instant.now())
                                .updatedAt(Instant.now())
                                .build();
                if (tenantId != null) {
                    account.setTenantId(tenantId);
                } else if (!ReactorTenantContext.isAdminMode(ctx)) {
                    account.setTenantId(
                            UUID.fromString(ReactorTenantContext.getTenantId(ctx)));
                }
                return r2dbcEntityTemplate.insert(account);
            });
}
```

**Replace `delete(UUID id)`:**
```java
public Mono<Void> delete(UUID id) {
    return Mono.deferContextual(
            ctx -> {
                if (ReactorTenantContext.isAdminMode(ctx)) {
                    return repository.deleteById(id);
                }
                return repository
                        .findByIdAndTenantId(
                                id,
                                UUID.fromString(ReactorTenantContext.getTenantId(ctx)))
                        .switchIfEmpty(
                                Mono.error(
                                        new ResponseStatusException(HttpStatus.NOT_FOUND)))
                        .flatMap(account -> repository.deleteById(id));
            });
}
```

Also remove the `import io.emcip.common.tenant.TenantContext;` import and add `import io.emcip.common.tenant.ReactorTenantContext;`.

- [ ] **Step 5: Update `TelegramAccountServiceTest` to use Reactor Context**

Replace the full file `emcip-admin-api/src/test/java/io/emcip/admin/api/service/TelegramAccountServiceTest.java`:

```java
package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.common.tenant.ReactorTenantContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramAccountServiceTest {

    @Mock private TelegramAccountRepository repository;
    @Mock private AccountWatchedGroupRepository watchedGroupRepository;
    @Mock private GroupProfileRepository groupProfileRepository;
    @Mock private R2dbcEntityTemplate r2dbcEntityTemplate;
    @Mock private WebClient tdlibClient;

    private TelegramAccountService service;

    @BeforeEach
    void setUp() {
        service =
                new TelegramAccountService(
                        repository,
                        watchedGroupRepository,
                        groupProfileRepository,
                        r2dbcEntityTemplate,
                        tdlibClient);
        ReflectionTestUtils.setField(service, "telegramApiId", 12345);
        ReflectionTestUtils.setField(service, "telegramApiHash", "test-api-hash");
    }

    private TelegramAccount account(UUID id) {
        TelegramAccount a = new TelegramAccount();
        a.setId(id);
        a.setPhoneNumber("+49123456789");
        a.setStatus(TelegramAccountStatus.ACTIVE);
        return a;
    }

    @Test
    void findAll_adminMode_returnsAll() {
        UUID id = UUID.randomUUID();
        when(repository.findAll()).thenReturn(Flux.just(account(id)));

        StepVerifier.create(
                        service.findAll()
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void getById_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.getById(id))
                .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("404"))
                .verify();
    }

    @Test
    void create_setsStatusUnconfiguredAndCredentials() {
        UUID tenantId = UUID.randomUUID();
        when(r2dbcEntityTemplate.insert(any(TelegramAccount.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(
                        service.create("+49123", "Test Account", tenantId)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .assertNext(
                        a -> {
                            assertThat(a.getStatus())
                                    .isEqualTo(TelegramAccountStatus.UNCONFIGURED);
                            assertThat(a.getPhoneNumber()).isEqualTo("+49123");
                            assertThat(a.getTenantId()).isEqualTo(tenantId);
                            assertThat(a.getCreatedAt()).isNotNull();
                            assertThat(a.getApiId()).isEqualTo(12345);
                            assertThat(a.getApiHash()).isEqualTo("test-api-hash");
                        })
                .verifyComplete();
    }

    @Test
    void delete_callsDeleteById() {
        UUID id = UUID.randomUUID();
        when(repository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(
                        service.delete(id)
                                .contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx)))
                .verifyComplete();

        verify(repository).deleteById(id);
    }

    @Test
    void findWatchedGroups_returnsGroupProfiles() {
        UUID accountId = UUID.randomUUID();
        io.emcip.admin.api.entity.AccountWatchedGroup awg =
                new io.emcip.admin.api.entity.AccountWatchedGroup();
        awg.setGroupProfileId(10L);

        GroupProfile gp = new GroupProfile();
        gp.setTelegramChatId(100L);

        when(watchedGroupRepository.findByAccountId(accountId)).thenReturn(Flux.just(awg));
        when(groupProfileRepository.findById(10L)).thenReturn(Mono.just(gp));

        StepVerifier.create(service.findWatchedGroups(accountId))
                .assertNext(p -> assertThat(p.getTelegramChatId()).isEqualTo(100L))
                .verifyComplete();
    }
}
```

Key changes:
- Changed `@InjectMocks` + `new service` pattern to manual `@BeforeEach` constructor call + `ReflectionTestUtils.setField` for `@Value` fields
- Removed `@AfterEach TenantContext.clear()`
- Context provided via `.contextWrite(ctx -> ReactorTenantContext.withAdminMode(ctx))` on the Mono/Flux passed to StepVerifier
- `getById_notFound_returns404` and `findWatchedGroups_returnsGroupProfiles` don't need context (those methods don't read tenant)

- [ ] **Step 6: Update `TelegramAccountController.createAccount` to use `Mono.deferContextual`**

In `TelegramAccountController.java`, replace only the `createAccount` method body, and update imports:

Remove: `import io.emcip.common.tenant.TenantContext;`
Add: `import io.emcip.common.tenant.ReactorTenantContext;`

Replace the `createAccount` method:
```java
@Operation(summary = "Create and connect a new Telegram account")
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public Mono<Map<String, Object>> createAccount(@RequestBody CreateAccountRequest req) {
    return Mono.deferContextual(
            ctx -> {
                UUID tenantId =
                        ReactorTenantContext.isAdminMode(ctx)
                                ? null
                                : UUID.fromString(ReactorTenantContext.getTenantId(ctx));
                return telegramAccountService
                        .create(req.phoneNumber(), req.displayName(), tenantId)
                        .map(TelegramAccountController::toSafeMap);
            });
}
```

- [ ] **Step 7: Run all admin-api tests**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 0 failures. Same count as before (89 tests).

- [ ] **Step 8: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "refactor(admin-api): use Reactor deferContextual in services and controller"
```

---

## Task 4: Update `emcip-audit-service`

**Files:**
- Create: `emcip-audit-service/src/main/java/io/emcip/audit/service/config/TenantWebFilter.java`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java`

> **Note:** `AuditServiceTest.java` does NOT need to change — the existing tests do not set tenant context, so they exercise the `tenantId == null` path (no-tenant queries). After the migration to `deferContextual`, the same path is exercised because `ReactorTenantContext.getTenantId(ctx)` returns `null` when no context is set. The tests stay green as-is.
>
> `AuditEventConsumer.java` is NOT modified — it's a Kafka listener running on a dedicated thread where ThreadLocal is correct.

- [ ] **Step 1: Create `TenantWebFilter` for audit-service**

Create `emcip-audit-service/src/main/java/io/emcip/audit/service/config/TenantWebFilter.java`:

```java
package io.emcip.audit.service.config;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux filter that reads the {@code X-Tenant-Id} header and propagates it
 * via Reactor Context for the duration of the request.
 */
@Component
public class TenantWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId =
                exchange.getRequest().getHeaders().getFirst(TenantContext.HEADER_NAME);
        if (tenantId != null && !tenantId.isBlank()) {
            return chain.filter(exchange)
                    .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, tenantId));
        }
        return chain.filter(exchange);
    }
}
```

- [ ] **Step 2: Replace `AuditService` to use `deferContextual`**

Replace the full file `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java`:

```java
package io.emcip.audit.service.service;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.common.tenant.ReactorTenantContext;
import io.r2dbc.postgresql.codec.Json;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public Mono<AuditEventEntity> save(AuditEventEntity entity) {
        return repository
                .save(entity)
                .doOnSuccess(
                        saved ->
                                log.debug(
                                        "Saved audit event: id={}, type={}",
                                        saved.getId(),
                                        saved.getEventType()))
                .doOnError(
                        e ->
                                log.error(
                                        "Failed to save audit event: eventId={}",
                                        entity.getEventId(),
                                        e));
    }

    public Flux<AuditEventEntity> findByEventType(String eventType) {
        return Flux.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByEventTypeAndTenantId(
                                eventType, UUID.fromString(tenantId));
                    }
                    return repository.findByEventType(eventType);
                });
    }

    public Flux<AuditEventEntity> findByDateRange(Instant from, Instant to) {
        return Flux.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByCreatedAtBetweenAndTenantId(
                                from, to, UUID.fromString(tenantId));
                    }
                    return repository.findByCreatedAtBetween(from, to);
                });
    }

    public Flux<AuditEventEntity> findByEventTypeAndDateRange(
            String eventType, Instant from, Instant to) {
        return Flux.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByEventTypeAndCreatedAtBetweenAndTenantId(
                                eventType, from, to, UUID.fromString(tenantId));
                    }
                    return repository.findByEventTypeAndCreatedAtBetween(eventType, from, to);
                });
    }

    public Mono<AuditEventEntity> findByEventId(String eventId) {
        return Mono.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByEventIdAndTenantId(
                                eventId, UUID.fromString(tenantId));
                    }
                    return repository.findByEventId(eventId);
                });
    }

    /**
     * Serialize a map of event fields to a {@link Json} value for the JSONB details column.
     *
     * @param fields key/value pairs to serialize
     * @return Json wrapping the serialized JSON, or null if serialization fails
     */
    public Json serializeDetails(Map<String, Object> fields) {
        try {
            return Json.of(objectMapper.writeValueAsString(fields));
        } catch (JacksonException e) {
            log.warn("Failed to serialize details map: {}", e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 3: Run audit-service tests**

```bash
mvn test -pl emcip-audit-service -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 4: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-audit-service
git add emcip-audit-service/
git commit -m "refactor(audit-service): use Reactor deferContextual for tenant scoping"
```

---

## Task 5: Update `emcip-moderation-service`

**Files:**
- Create: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/config/TenantWebFilter.java`
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java`
- Modify: `emcip-moderation-service/src/test/java/io/emcip/moderation/service/controller/ModerationRuleControllerTest.java`

> **Note:** `ModerationEventConsumer.java` is NOT modified — it's a Kafka listener on a dedicated thread where ThreadLocal is correct.

- [ ] **Step 1: Create `TenantWebFilter` for moderation-service**

Create `emcip-moderation-service/src/main/java/io/emcip/moderation/service/config/TenantWebFilter.java`:

```java
package io.emcip.moderation.service.config;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux filter that reads the {@code X-Tenant-Id} header and propagates it
 * via Reactor Context for the duration of the request.
 */
@Component
public class TenantWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId =
                exchange.getRequest().getHeaders().getFirst(TenantContext.HEADER_NAME);
        if (tenantId != null && !tenantId.isBlank()) {
            return chain.filter(exchange)
                    .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, tenantId));
        }
        return chain.filter(exchange);
    }
}
```

- [ ] **Step 2: Replace `ModerationRuleController` to use `deferContextual`**

Replace the full file `emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java`:

```java
package io.emcip.moderation.service.controller;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/moderation-rules")
@RequiredArgsConstructor
@Tag(name = "Moderation Rules", description = "Create, read, update, and delete moderation rules")
public class ModerationRuleController {

    private final ModerationRuleRepository repository;

    @GetMapping
    @Operation(summary = "List all moderation rules")
    public Flux<ModerationRule> list() {
        return Flux.deferContextual(
                ctx -> {
                    UUID tenantId =
                            UUID.fromString(ReactorTenantContext.getTenantId(ctx));
                    return repository.findAllOrderedByTenantId(tenantId);
                });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new moderation rule")
    public Mono<ModerationRule> create(@RequestBody ModerationRule rule) {
        return Mono.deferContextual(
                ctx -> {
                    rule.setId(null);
                    rule.setCreatedAt(Instant.now());
                    rule.setUpdatedAt(Instant.now());
                    if (rule.getSeverity() == null || rule.getSeverity().isBlank()) {
                        rule.setSeverity("MEDIUM");
                    }
                    if (rule.getAction() == null || rule.getAction().isBlank()) {
                        rule.setAction("FLAG");
                    }
                    rule.setEnabled(true);
                    rule.setTenantId(
                            UUID.fromString(ReactorTenantContext.getTenantId(ctx)));
                    return repository.save(rule);
                });
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing moderation rule")
    public Mono<ModerationRule> update(
            @PathVariable Long id, @RequestBody ModerationRule rule) {
        return Mono.deferContextual(
                ctx -> {
                    UUID tenantId =
                            UUID.fromString(ReactorTenantContext.getTenantId(ctx));
                    return repository
                            .findByIdAndTenantId(id, tenantId)
                            .switchIfEmpty(
                                    Mono.error(
                                            new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND)))
                            .flatMap(
                                    existing -> {
                                        existing.setName(rule.getName());
                                        existing.setRuleType(rule.getRuleType());
                                        existing.setPattern(rule.getPattern());
                                        existing.setSeverity(rule.getSeverity());
                                        existing.setAction(rule.getAction());
                                        existing.setEnabled(rule.isEnabled());
                                        existing.setUpdatedAt(Instant.now());
                                        return repository.save(existing);
                                    });
                });
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a moderation rule")
    public Mono<Void> delete(@PathVariable Long id) {
        return Mono.deferContextual(
                ctx -> {
                    UUID tenantId =
                            UUID.fromString(ReactorTenantContext.getTenantId(ctx));
                    return repository
                            .findByIdAndTenantId(id, tenantId)
                            .switchIfEmpty(
                                    Mono.error(
                                            new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND)))
                            .flatMap(rule -> repository.deleteById(id));
                });
    }
}
```

- [ ] **Step 3: Update `ModerationRuleControllerTest` to use `contextWrite`**

Replace the full file `emcip-moderation-service/src/test/java/io/emcip/moderation/service/controller/ModerationRuleControllerTest.java`:

```java
package io.emcip.moderation.service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ModerationRuleControllerTest {

    private static final UUID TEST_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ModerationRuleRepository repository;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        repository = mock(ModerationRuleRepository.class);
        ModerationRuleController controller = new ModerationRuleController(repository);
        client =
                WebTestClient.bindToController(controller)
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange)
                                                .contextWrite(
                                                        ctx ->
                                                                ReactorTenantContext.withTenant(
                                                                        ctx,
                                                                        TEST_TENANT_ID
                                                                                .toString())))
                        .build();
    }

    private ModerationRule rule(Long id, String name) {
        ModerationRule r = new ModerationRule();
        r.setId(id);
        r.setName(name);
        r.setRuleType("KEYWORD");
        r.setPattern("spam");
        r.setSeverity("HIGH");
        r.setAction("FLAG");
        r.setEnabled(true);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    @Test
    void list_returnsAllRulesOrdered() {
        when(repository.findAllOrderedByTenantId(TEST_TENANT_ID))
                .thenReturn(Flux.just(rule(1L, "spam-rule")));
        client.get()
                .uri("/api/moderation-rules")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(ModerationRule.class)
                .hasSize(1);
    }

    @Test
    void create_returns201() {
        ModerationRule r = rule(null, "new-rule");
        when(repository.save(any())).thenReturn(Mono.just(rule(2L, "new-rule")));
        client.post()
                .uri("/api/moderation-rules")
                .bodyValue(r)
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void update_returns200() {
        ModerationRule existing = rule(1L, "old");
        ModerationRule update = rule(1L, "updated");
        when(repository.findByIdAndTenantId(1L, TEST_TENANT_ID)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenReturn(Mono.just(update));
        client.put()
                .uri("/api/moderation-rules/1")
                .bodyValue(update)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void update_notFound_returns404() {
        when(repository.findByIdAndTenantId(eq(999L), eq(TEST_TENANT_ID)))
                .thenReturn(Mono.empty());

        client.put()
                .uri("/api/moderation-rules/999")
                .bodyValue(rule(null, "irrelevant"))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void delete_returns204() {
        ModerationRule existing = rule(1L, "to-delete");
        when(repository.findByIdAndTenantId(1L, TEST_TENANT_ID)).thenReturn(Mono.just(existing));
        when(repository.deleteById(1L)).thenReturn(Mono.empty());
        client.delete().uri("/api/moderation-rules/1").exchange().expectStatus().isNoContent();
    }
}
```

Key change: the inline WebFilter now calls `chain.filter(exchange).contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, TEST_TENANT_ID.toString()))` instead of `TenantContext.setTenantId(...)` + `doFinally(TenantContext::clear)`.

- [ ] **Step 4: Run moderation-service tests**

```bash
mvn test -pl emcip-moderation-service -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-moderation-service
git add emcip-moderation-service/
git commit -m "refactor(moderation-service): use Reactor deferContextual for tenant scoping"
```

---

## Self-Review

**Spec coverage:**

| Requirement | Task |
|-------------|------|
| `TenantContext` ThreadLocal NOT used in WebFlux filter chain | Task 2 ✅ |
| `TenantContext` ThreadLocal NOT used in service reactive chains | Tasks 3, 4, 5 ✅ |
| Kafka consumer ThreadLocal paths left unchanged | No modification needed ✅ |
| Reactor Context utility in shared library | Task 1 ✅ |
| `GroupProfileService` uses `deferContextual` | Task 3 ✅ |
| `TelegramAccountService` uses `deferContextual` | Task 3 ✅ |
| `TelegramAccountController.createAccount` uses `deferContextual` | Task 3 ✅ |
| `AuditService` uses `deferContextual` | Task 4 ✅ |
| `ModerationRuleController` uses `deferContextual` | Task 5 ✅ |
| All affected service tests migrated to `contextWrite` | Tasks 3, 5 ✅ |
| `AuditServiceTest` unchanged (already exercises null-tenant path) | Task 4 ✅ |

**Type consistency check:**
- `ReactorTenantContext.withTenant(Context, String)` used in filters: ✅ consistent
- `ReactorTenantContext.getTenantId(ContextView)` used in services: ✅ consistent
- `Flux.deferContextual` vs `Mono.deferContextual` usage matches return type of each method: ✅

**Known non-goals (out of scope):**
- `TenantWebFilter` in admin-api may be redundant with `AdminTenantContextFilter` — both write the same key. Both are updated to use `contextWrite`, so no harm; cleanup is a separate ADR discussion.
- `auditService.save(entity).block()` in `AuditEventConsumer` — separate issue (SC5), not addressed here.
