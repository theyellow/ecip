# Multi-Tenancy Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce tenant isolation at the data access layer across all EMCIP services, eliminating the cross-tenant data leak that is currently possible.

**Architecture:** `TenantContextFilter` is hardened to reject requests without `X-Tenant-Id` (400). Admin-api gets a new `AdminTenantContextFilter` that allows bypass for JWT `ADMIN` role. JPA services get a `TenantFilterAspect` that enables Hibernate `@Filter` before every repository call. R2DBC services switch to tenant-scoped repository methods. Kafka consumers bind tenant from record headers.

**Tech Stack:** Java 21, Spring Boot 4, Hibernate `@Filter`/`@FilterDef`, Spring AOP (`@Aspect`/`@Before`), Spring Data JPA, Spring Data R2DBC, `emcip-core` shared tenant infrastructure.

---

## File Structure

**emcip-core** (shared, picked up by all services)
- Modify: `emcip-core/src/main/java/io/emcip/common/tenant/TenantContext.java` — add `ADMIN_MODE` ThreadLocal
- Modify: `emcip-core/src/main/java/io/emcip/common/tenant/TenantContextFilter.java` — reject missing header with 400
- Modify: `emcip-core/src/test/java/io/emcip/common/tenant/TenantContextTest.java` — add admin mode tests
- Modify: `emcip-core/src/test/java/io/emcip/common/tenant/TenantContextFilterTest.java` — add 400 rejection test

**emcip-admin-api** (R2DBC, WebFlux, JWT, admin bypass)
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/AdminTenantContextFilter.java` — tenant/admin routing WebFilter
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java` — register AdminTenantContextFilter after JWT
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramAccount.java` — add `tenantId` field
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/GroupProfileRepository.java` — add scoped methods
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/TelegramAccountRepository.java` — add scoped methods
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AccountWatchedGroupRepository.java` — add scoped methods
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/GroupProfileController.java` — branch on isAdminMode()
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java` — branch on isAdminMode()

**JPA entities** (DB columns exist via Liquibase; Java fields and Hibernate filters are missing)
- Modify: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/entity/Message.java`
- Modify: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/entity/MessageThread.java`
- Modify: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/entity/User.java`
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/entity/PolicyRuleConfig.java`
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/entity/PolicyDecision.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/PromptTemplate.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/ModelCostLog.java`

**TenantFilterAspect** — one per JPA service (spring-boot-starter-aop needed in each pom)
- Create: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/config/TenantFilterAspect.java`
- Create: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/TenantFilterAspect.java`
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/TenantFilterAspect.java`
- Modify: `emcip-conversation-context/pom.xml` — add spring-boot-starter-aop
- Modify: `emcip-policy-engine/pom.xml` — add spring-boot-starter-aop
- Modify: `emcip-llm-orchestrator/pom.xml` — add spring-boot-starter-aop

**emcip-moderation-service** (R2DBC, no admin bypass)
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/entity/ModerationRule.java` — add `tenantId` field
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/repository/ModerationRuleRepository.java` — add scoped methods
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/service/RuleEvaluationService.java` — remove startup cache; per-tenant load
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/ModerationEventConsumer.java` — bind tenant from record
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java` — use scoped methods

**emcip-audit-service** (R2DBC, no admin bypass, 5 Kafka listeners)
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/entity/AuditEventEntity.java` — add `tenantId` field
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/repository/AuditEventRepository.java` — add scoped methods
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java` — use scoped methods
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java` — bind tenant from record headers

**Kafka wiring**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/PolicyDecisionConsumer.java` — bind tenant from record
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramEventPublisher.java` — inject tenantId, add header to ProducerRecord
- Modify: `helm/emcip/values.yaml` — add `APP_TENANT_ID` env var for tdlib-adapter

---

## Task 1: emcip-core — TenantContext admin mode + TenantContextFilter 400 rejection

**Files:**
- Modify: `emcip-core/src/main/java/io/emcip/common/tenant/TenantContext.java`
- Modify: `emcip-core/src/main/java/io/emcip/common/tenant/TenantContextFilter.java`
- Modify: `emcip-core/src/test/java/io/emcip/common/tenant/TenantContextTest.java`
- Modify: `emcip-core/src/test/java/io/emcip/common/tenant/TenantContextFilterTest.java`

- [ ] **Step 1: Write failing tests for admin mode in TenantContextTest**

Add these three tests to the existing `TenantContextTest` class:

```java
@Test
void setAndGetAdminMode() {
    TenantContext.setAdminMode(true);
    assertThat(TenantContext.isAdminMode()).isTrue();
}

@Test
void defaultAdminModeIsFalse() {
    assertThat(TenantContext.isAdminMode()).isFalse();
}

@Test
void clearResetsAdminMode() {
    TenantContext.setAdminMode(true);
    TenantContext.clear();
    assertThat(TenantContext.isAdminMode()).isFalse();
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl emcip-core -Dtest=TenantContextTest --no-transfer-progress | cat
```
Expected: FAIL — `setAdminMode` method does not exist.

- [ ] **Step 3: Implement admin mode in TenantContext**

Replace the full file content:

```java
package io.emcip.common.tenant;

public final class TenantContext {

    public static final String HEADER_NAME = "X-Tenant-Id";
    public static final String KAFKA_HEADER = "tenant_id";

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ADMIN_MODE = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void setAdminMode(boolean admin) {
        ADMIN_MODE.set(admin);
    }

    public static boolean isAdminMode() {
        return Boolean.TRUE.equals(ADMIN_MODE.get());
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        ADMIN_MODE.remove();
    }
}
```

- [ ] **Step 4: Write failing test for 400 rejection in TenantContextFilterTest**

Add this test to the existing `TenantContextFilterTest` class — but first, delete the two existing tests named `noHeader_doesNotSetTenantId` and `blankHeader_doesNotSetTenantId` (they test the old silent-pass behavior that we're removing) and replace them with:

```java
@Test
void noHeader_returns400() throws Exception {
    when(request.getHeader(TenantContext.HEADER_NAME)).thenReturn(null);

    filter.doFilterInternal(request, response, filterChain);

    verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST,
            "X-Tenant-Id header is required");
    verify(filterChain, never()).doFilter(request, response);
}

@Test
void blankHeader_returns400() throws Exception {
    when(request.getHeader(TenantContext.HEADER_NAME)).thenReturn("   ");

    filter.doFilterInternal(request, response, filterChain);

    verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST,
            "X-Tenant-Id header is required");
    verify(filterChain, never()).doFilter(request, response);
}
```

- [ ] **Step 5: Run to verify the new tests fail (old behavior still in place)**

```bash
mvn test -pl emcip-core -Dtest=TenantContextFilterTest --no-transfer-progress | cat
```
Expected: FAIL on the two new tests.

- [ ] **Step 6: Update TenantContextFilter to reject missing header**

Replace the full file:

```java
package io.emcip.common.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String tenantId = request.getHeader(TenantContext.HEADER_NAME);
            if (tenantId == null || tenantId.isBlank()) {
                response.sendError(
                        HttpServletResponse.SC_BAD_REQUEST, "X-Tenant-Id header is required");
                return;
            }
            TenantContext.setTenantId(tenantId);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
```

- [ ] **Step 7: Run all emcip-core tests**

```bash
mvn test -pl emcip-core --no-transfer-progress | cat
```
Expected: All tests PASS.

- [ ] **Step 8: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-core --no-transfer-progress | cat
git add emcip-core/src/main/java/io/emcip/common/tenant/TenantContext.java \
        emcip-core/src/main/java/io/emcip/common/tenant/TenantContextFilter.java \
        emcip-core/src/test/java/io/emcip/common/tenant/TenantContextTest.java \
        emcip-core/src/test/java/io/emcip/common/tenant/TenantContextFilterTest.java
git commit -m "feat(core): add TenantContext admin mode and harden TenantContextFilter with 400 rejection"
```

---

## Task 2: emcip-admin-api — AdminTenantContextFilter

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/AdminTenantContextFilter.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java`

**Why a separate filter from TenantContextFilter:** admin-api is reactive (WebFlux). `TenantContextFilter extends OncePerRequestFilter` is a servlet filter — it cannot be used in WebFlux. `AdminTenantContextFilter` implements `WebFilter` and reads JWT role from `ReactiveSecurityContextHolder`.

**Filter logic:**
1. Header present → `TenantContext.setTenantId(value)`, proceed
2. Header absent + authenticated user has `ROLE_ADMIN` → `TenantContext.setAdminMode(true)`, proceed
3. Header absent + no `ROLE_ADMIN` → respond 400

The JWT filter (`JwtAuthenticationFilter`) runs at `AUTHENTICATION` order and calls `.contextWrite(ReactiveSecurityContextHolder.withAuthentication(...))`. The admin tenant filter must run AFTER that order so the security context is populated. Registering it with `.addFilterAfter(filter, SecurityWebFiltersOrder.AUTHENTICATION)` inside the security filter chain achieves this.

- [ ] **Step 1: Create AdminTenantContextFilter**

```java
package io.emcip.admin.api.security;

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
        String tenantId = exchange.getRequest().getHeaders().getFirst(TenantContext.HEADER_NAME);

        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.setTenantId(tenantId);
            return chain.filter(exchange).doFinally(signal -> TenantContext.clear());
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
                                TenantContext.setAdminMode(true);
                                return chain.filter(exchange)
                                        .doFinally(signal -> TenantContext.clear());
                            }
                            log.debug(
                                    "Rejected request to {} — missing X-Tenant-Id and no ADMIN role",
                                    exchange.getRequest().getPath());
                            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                            return exchange.getResponse().setComplete();
                        });
    }
}
```

- [ ] **Step 2: Register AdminTenantContextFilter in SecurityConfig**

Add `AdminTenantContextFilter` as a bean and register it after `AUTHENTICATION` order. Replace `SecurityConfig.java`:

```java
package io.emcip.admin.api.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${admin.cors.allowed-origins:http://localhost:14009}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            ServiceTokenAuthenticationFilter serviceTokenFilter,
            AdminTenantContextFilter adminTenantContextFilter) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(corsSpec -> corsSpec.configurationSource(corsConfigurationSource()))
                .authorizeExchange(
                        auth ->
                                auth.pathMatchers(HttpMethod.POST, "/api/auth/token", "/auth/token")
                                        .permitAll()
                                        .pathMatchers("/actuator/**")
                                        .permitAll()
                                        .anyExchange()
                                        .authenticated())
                .addFilterAt(serviceTokenFilter, SecurityWebFiltersOrder.HTTP_BASIC)
                .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAfter(adminTenantContextFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }

    @Bean
    public AdminTenantContextFilter adminTenantContextFilter() {
        return new AdminTenantContextFilter();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 3: Compile check**

```bash
mvn compile -pl emcip-admin-api --no-transfer-progress | cat
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api --no-transfer-progress | cat
git add emcip-admin-api/src/main/java/io/emcip/admin/api/security/AdminTenantContextFilter.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java
git commit -m "feat(admin-api): add AdminTenantContextFilter with ADMIN JWT bypass for tenant isolation"
```

---

## Task 3: emcip-admin-api — TelegramAccount entity + scoped repositories + controller updates

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramAccount.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/GroupProfileRepository.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/TelegramAccountRepository.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AccountWatchedGroupRepository.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/GroupProfileController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java`

**Note:** `GroupProfile` already has `tenantId UUID` field. `TelegramAccount` does not — add it. DB column `tenant_id` already exists in both tables (Liquibase already applied).

- [ ] **Step 1: Add tenantId to TelegramAccount entity**

Add field after `updatedAt`:

```java
@Column("tenant_id")
private UUID tenantId;
```

Full file after change:

```java
package io.emcip.admin.api.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("telegram_accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramAccount {

    @Id private UUID id;

    @Column("phone_number")
    private String phoneNumber;

    @Column("api_id")
    private Integer apiId;

    @Column("api_hash")
    private String apiHash;

    @Column("display_name")
    private String displayName;

    @Column("session_string")
    private String sessionString;

    @Column("status")
    private TelegramAccountStatus status;

    @Column("last_error")
    private String lastError;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("tenant_id")
    private UUID tenantId;
}
```

- [ ] **Step 2: Add scoped methods to GroupProfileRepository**

```java
package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.GroupProfile;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface GroupProfileRepository extends ReactiveCrudRepository<GroupProfile, Long> {

    Mono<GroupProfile> findByTelegramChatId(Long chatId);

    Flux<GroupProfile> findAllByTenantId(UUID tenantId);

    Mono<GroupProfile> findByTelegramChatIdAndTenantId(Long chatId, UUID tenantId);
}
```

- [ ] **Step 3: Add scoped methods to TelegramAccountRepository**

```java
package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface TelegramAccountRepository extends ReactiveCrudRepository<TelegramAccount, UUID> {

    Flux<TelegramAccount> findByStatus(TelegramAccountStatus status);

    Flux<TelegramAccount> findAllByTenantId(UUID tenantId);

    Flux<TelegramAccount> findByStatusAndTenantId(TelegramAccountStatus status, UUID tenantId);
}
```

- [ ] **Step 4: Add scoped methods to AccountWatchedGroupRepository**

```java
package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.AccountWatchedGroup;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AccountWatchedGroupRepository
        extends ReactiveCrudRepository<AccountWatchedGroup, Long> {

    Flux<AccountWatchedGroup> findByAccountId(UUID accountId);

    Mono<Void> deleteByAccountIdAndGroupProfileId(UUID accountId, Long groupProfileId);

    Mono<Boolean> existsByAccountIdAndGroupProfileId(UUID accountId, Long groupProfileId);
}
```

Note: `AccountWatchedGroup` has no `tenantId` — it is scoped through its `accountId` FK to `TelegramAccount`, which is already tenant-scoped. No change needed to this entity.

- [ ] **Step 5: Update GroupProfileController to branch on admin mode**

`TenantContext.getTenantId()` / `TenantContext.isAdminMode()` are ThreadLocal — safe to call directly in reactive handlers since the filter sets them synchronously before the chain runs.

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.common.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@Tag(name = "Group Profiles", description = "Manage Telegram group configuration profiles")
public class GroupProfileController {

    private final GroupProfileRepository repository;

    @Operation(summary = "List all group profiles")
    @GetMapping
    public Flux<GroupProfile> listAll() {
        if (TenantContext.isAdminMode()) {
            return repository.findAll();
        }
        return repository.findAllByTenantId(UUID.fromString(TenantContext.getTenantId()));
    }

    @Operation(summary = "Get a group profile by chat ID")
    @GetMapping("/{chatId}")
    public Mono<ResponseEntity<GroupProfile>> getByChatId(@PathVariable("chatId") Long chatId) {
        if (TenantContext.isAdminMode()) {
            return repository
                    .findByTelegramChatId(chatId)
                    .map(ResponseEntity::ok)
                    .defaultIfEmpty(ResponseEntity.notFound().<GroupProfile>build());
        }
        UUID tenantId = UUID.fromString(TenantContext.getTenantId());
        return repository
                .findByTelegramChatIdAndTenantId(chatId, tenantId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().<GroupProfile>build());
    }

    @Operation(summary = "Create a group profile")
    @PostMapping
    public Mono<ResponseEntity<GroupProfile>> create(@RequestBody GroupProfile profile) {
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        if (!TenantContext.isAdminMode()) {
            profile.setTenantId(UUID.fromString(TenantContext.getTenantId()));
        }
        return repository
                .save(profile)
                .map(saved -> ResponseEntity.status(HttpStatus.CREATED).body(saved));
    }

    @Operation(summary = "Update a group profile")
    @PutMapping("/{chatId}")
    public Mono<ResponseEntity<GroupProfile>> update(
            @PathVariable("chatId") Long chatId, @RequestBody GroupProfile update) {
        Mono<GroupProfile> finder =
                TenantContext.isAdminMode()
                        ? repository.findByTelegramChatId(chatId)
                        : repository.findByTelegramChatIdAndTenantId(
                                chatId, UUID.fromString(TenantContext.getTenantId()));
        return finder.flatMap(
                        existing -> {
                            existing.setName(update.getName());
                            existing.setDescription(update.getDescription());
                            existing.setModerationLevel(update.getModerationLevel());
                            existing.setAutoRespond(update.isAutoRespond());
                            existing.setWelcomeMessage(update.getWelcomeMessage());
                            existing.setUpdatedAt(Instant.now());
                            return repository.save(existing);
                        })
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().<GroupProfile>build());
    }

    @Operation(summary = "Delete a group profile")
    @DeleteMapping("/{chatId}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("chatId") Long chatId) {
        Mono<GroupProfile> finder =
                TenantContext.isAdminMode()
                        ? repository.findByTelegramChatId(chatId)
                        : repository.findByTelegramChatIdAndTenantId(
                                chatId, UUID.fromString(TenantContext.getTenantId()));
        return finder.flatMap(
                        existing ->
                                repository
                                        .delete(existing)
                                        .thenReturn(ResponseEntity.<Void>noContent().<Void>build()))
                .defaultIfEmpty(ResponseEntity.notFound().<Void>build());
    }
}
```

- [ ] **Step 6: Update TelegramAccountController — listAccounts and createAccount**

In `TelegramAccountController`, update `listAccounts()` to scope by tenant, and `createAccount()` to set tenantId:

Find `listAccounts()`:
```java
public Mono<List<Map<String, Object>>> listAccounts() {
    return repository.findAll().map(TelegramAccountController::toSafeMap).collectList();
}
```
Replace with:
```java
public Mono<List<Map<String, Object>>> listAccounts() {
    if (TenantContext.isAdminMode()) {
        return repository.findAll().map(TelegramAccountController::toSafeMap).collectList();
    }
    return repository
            .findAllByTenantId(UUID.fromString(TenantContext.getTenantId()))
            .map(TelegramAccountController::toSafeMap)
            .collectList();
}
```

Find the `createAccount` insert line:
```java
return r2dbcEntityTemplate.insert(account).map(TelegramAccountController::toSafeMap);
```
Replace with (set tenantId before inserting):
```java
if (!TenantContext.isAdminMode()) {
    account.setTenantId(UUID.fromString(TenantContext.getTenantId()));
}
return r2dbcEntityTemplate.insert(account).map(TelegramAccountController::toSafeMap);
```

Also add the import at top of file:
```java
import io.emcip.common.tenant.TenantContext;
```

- [ ] **Step 7: Compile check**

```bash
mvn compile -pl emcip-admin-api --no-transfer-progress | cat
```
Expected: BUILD SUCCESS.

- [ ] **Step 8: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api --no-transfer-progress | cat
git add emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramAccount.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/repository/GroupProfileRepository.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/repository/TelegramAccountRepository.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AccountWatchedGroupRepository.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/controller/GroupProfileController.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java
git commit -m "feat(admin-api): scope repository queries by tenant; admin JWT bypasses to unscoped methods"
```

---

## Task 4: JPA entities — add tenantId + @FilterDef/@Filter (conversation-context)

**Files:**
- Modify: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/entity/Message.java`
- Modify: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/entity/MessageThread.java`
- Modify: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/entity/User.java`

**Note:** DB column `tenant_id` already exists in all three tables via Liquibase. We are only adding the Java field mapping and Hibernate filter annotations.

- [ ] **Step 1: Update Message entity**

Add to imports:
```java
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
```

Add annotations before `@Entity`:
```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
```

Add field (at end of class, before closing brace):
```java
@Column(name = "tenant_id", nullable = false)
private UUID tenantId;
```

- [ ] **Step 2: Update MessageThread entity**

Add same imports. Add same `@FilterDef` and `@Filter` annotations before `@Entity`. Add field:
```java
@Column(name = "tenant_id", nullable = false)
private UUID tenantId;
```

- [ ] **Step 3: Update User entity**

Add same imports. Add same `@FilterDef` and `@Filter` annotations before `@Entity`. Add field:
```java
@Column(name = "tenant_id", nullable = false)
private UUID tenantId;
```

- [ ] **Step 4: Compile check**

```bash
mvn compile -pl emcip-conversation-context --no-transfer-progress | cat
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-conversation-context --no-transfer-progress | cat
git add emcip-conversation-context/src/main/java/io/emcip/conversation/context/entity/Message.java \
        emcip-conversation-context/src/main/java/io/emcip/conversation/context/entity/MessageThread.java \
        emcip-conversation-context/src/main/java/io/emcip/conversation/context/entity/User.java
git commit -m "feat(conversation-context): add tenantId field and Hibernate @Filter to Message, MessageThread, User"
```

---

## Task 5: JPA entities — add tenantId + @FilterDef/@Filter (policy-engine + llm-orchestrator)

**Files:**
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/entity/PolicyRuleConfig.java`
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/entity/PolicyDecision.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/PromptTemplate.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/ModelCostLog.java`

- [ ] **Step 1: Update PolicyRuleConfig entity**

`PolicyRuleConfig` uses `import jakarta.persistence.*;` wildcard. Add new imports:
```java
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
```

Add annotations before `@Entity`:
```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
```

Add field (after `version` field, before closing brace):
```java
@Schema(description = "Tenant this rule belongs to")
@Column(name = "tenant_id", nullable = false)
private UUID tenantId;
```

- [ ] **Step 2: Update PolicyDecision entity**

`PolicyDecision` uses `import jakarta.persistence.*;`. Add:
```java
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
```

Add annotations before `@Entity`:
```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
```

Add field before `@Version`:
```java
@Column(name = "tenant_id", nullable = false)
private UUID tenantId;
```

- [ ] **Step 3: Update PromptTemplate entity**

Add imports:
```java
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
```
(`UUID` already imported.)

Add annotations before `@Entity`:
```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
```

Add field before `versionLock`:
```java
@Schema(description = "Tenant this template belongs to")
@Column(name = "tenant_id", nullable = false)
private UUID tenantId;
```

- [ ] **Step 4: Update ModelCostLog entity**

Add imports:
```java
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
```
(`UUID` already imported.)

Add annotations before `@Entity`:
```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
```

Add field before `versionLock`:
```java
@Column(name = "tenant_id", nullable = false)
private UUID tenantId;
```

- [ ] **Step 5: Compile check both services**

```bash
mvn compile -pl emcip-policy-engine,emcip-llm-orchestrator --no-transfer-progress | cat
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-policy-engine,emcip-llm-orchestrator --no-transfer-progress | cat
git add emcip-policy-engine/src/main/java/io/emcip/policy/engine/entity/PolicyRuleConfig.java \
        emcip-policy-engine/src/main/java/io/emcip/policy/engine/entity/PolicyDecision.java \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/PromptTemplate.java \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/ModelCostLog.java
git commit -m "feat(policy-engine,llm-orchestrator): add tenantId field and Hibernate @Filter to JPA entities"
```

---

## Task 6: TenantFilterAspect — one per JPA service

**Files:**
- Modify: `emcip-conversation-context/pom.xml`
- Modify: `emcip-policy-engine/pom.xml`
- Modify: `emcip-llm-orchestrator/pom.xml`
- Create: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/config/TenantFilterAspect.java`
- Create: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/TenantFilterAspect.java`
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/TenantFilterAspect.java`

**How it works:** An `@Aspect` bean with a `@Before` pointcut on all `@Repository` beans. Before each repository method executes, it checks `TenantContext.getTenantId()`. If present, it opens a Hibernate `Session`, enables the `tenantFilter` with the tenant UUID as parameter.

- [ ] **Step 1: Add spring-boot-starter-aop to each pom**

In `emcip-conversation-context/pom.xml`, inside `<dependencies>`, after the `spring-boot-starter-data-jpa` entry:
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

Repeat the same addition in `emcip-policy-engine/pom.xml` and `emcip-llm-orchestrator/pom.xml`.

- [ ] **Step 2: Write failing test for TenantFilterAspect in conversation-context**

Create `emcip-conversation-context/src/test/java/io/emcip/conversation/context/config/TenantFilterAspectTest.java`:

```java
package io.emcip.conversation.context.config;

import static org.mockito.Mockito.*;

import io.emcip.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class TenantFilterAspectTest {

    @Mock private EntityManager entityManager;
    @Mock private Session session;
    @Mock private Filter hibernateFilter;

    @InjectMocks private TenantFilterAspect aspect;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void enablesFilterWhenTenantIsSet() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId.toString());

        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.enableFilter("tenantFilter")).thenReturn(hibernateFilter);
        when(hibernateFilter.setParameter("tenantId", tenantId)).thenReturn(hibernateFilter);

        aspect.applyTenantFilter();

        verify(session).enableFilter("tenantFilter");
        verify(hibernateFilter).setParameter("tenantId", tenantId);
    }

    @Test
    void doesNotEnableFilterWhenTenantIsAbsent() {
        // No TenantContext.setTenantId() called

        aspect.applyTenantFilter();

        verifyNoInteractions(entityManager);
    }
}
```

- [ ] **Step 3: Run to verify the test fails**

```bash
mvn test -pl emcip-conversation-context -Dtest=TenantFilterAspectTest --no-transfer-progress | cat
```
Expected: FAIL — `TenantFilterAspect` does not exist yet.

- [ ] **Step 4: Create TenantFilterAspect in conversation-context**

```java
package io.emcip.conversation.context.config;

import io.emcip.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

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
            session.enableFilter("tenantFilter").setParameter("tenantId", UUID.fromString(tenantId));
        }
    }
}
```

- [ ] **Step 5: Run test again to verify it passes**

```bash
mvn test -pl emcip-conversation-context -Dtest=TenantFilterAspectTest --no-transfer-progress | cat
```
Expected: PASS.

- [ ] **Step 6: Create TenantFilterAspect in policy-engine**

```java
package io.emcip.policy.engine.config;

import io.emcip.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

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
            session.enableFilter("tenantFilter").setParameter("tenantId", UUID.fromString(tenantId));
        }
    }
}
```

- [ ] **Step 7: Create TenantFilterAspect in llm-orchestrator**

```java
package io.emcip.llm.orchestrator.config;

import io.emcip.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

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
            session.enableFilter("tenantFilter").setParameter("tenantId", UUID.fromString(tenantId));
        }
    }
}
```

- [ ] **Step 8: Compile check all three**

```bash
mvn compile -pl emcip-conversation-context,emcip-policy-engine,emcip-llm-orchestrator --no-transfer-progress | cat
```
Expected: BUILD SUCCESS.

- [ ] **Step 9: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-conversation-context,emcip-policy-engine,emcip-llm-orchestrator --no-transfer-progress | cat
git add emcip-conversation-context/pom.xml \
        emcip-policy-engine/pom.xml \
        emcip-llm-orchestrator/pom.xml \
        emcip-conversation-context/src/main/java/io/emcip/conversation/context/config/TenantFilterAspect.java \
        emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/TenantFilterAspect.java \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/TenantFilterAspect.java \
        emcip-conversation-context/src/test/java/io/emcip/conversation/context/config/TenantFilterAspectTest.java
git commit -m "feat(jpa-services): add TenantFilterAspect to enable Hibernate tenant filter before each repository call"
```

---

## Task 7: emcip-moderation-service — per-tenant rule loading

**Files:**
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/entity/ModerationRule.java`
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/repository/ModerationRuleRepository.java`
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/service/RuleEvaluationService.java`
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/ModerationEventConsumer.java`
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java`

**Key design note:** `RuleEvaluationService` currently caches all enabled rules at startup without a tenant. With tenant isolation, rules are per-tenant. The cache is replaced by a per-call load using the tenant from context. The `evaluate()` signature changes to accept `tenantId` explicitly (the Kafka consumer passes it).

- [ ] **Step 1: Add tenantId to ModerationRule entity**

Add import:
```java
import java.util.UUID;
```

Add field (after `updatedAt`):
```java
@Column("tenant_id")
private UUID tenantId;
```

- [ ] **Step 2: Add scoped repository methods**

```java
package io.emcip.moderation.service.repository;

import io.emcip.moderation.service.entity.ModerationRule;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ModerationRuleRepository extends ReactiveCrudRepository<ModerationRule, Long> {

    Flux<ModerationRule> findByEnabledTrue();

    @Query("SELECT * FROM moderation_rules ORDER BY rule_type ASC, name ASC")
    Flux<ModerationRule> findAllOrdered();

    Flux<ModerationRule> findByEnabledTrueAndTenantId(UUID tenantId);

    @Query("SELECT * FROM moderation_rules WHERE tenant_id = :tenantId ORDER BY rule_type ASC, name ASC")
    Flux<ModerationRule> findAllOrderedByTenantId(UUID tenantId);

    Mono<ModerationRule> findByIdAndTenantId(Long id, UUID tenantId);
}
```

- [ ] **Step 3: Write failing tests for the new RuleEvaluationService signature**

Create `emcip-moderation-service/src/test/java/io/emcip/moderation/service/service/RuleEvaluationServiceTest.java`:

```java
package io.emcip.moderation.service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class RuleEvaluationServiceTest {

    @Mock private ModerationRuleRepository repository;

    @InjectMocks private RuleEvaluationService service;

    @Test
    void evaluateMatchesKeywordRule() {
        UUID tenantId = UUID.randomUUID();
        ModerationRule rule = new ModerationRule();
        rule.setName("block-spam");
        rule.setRuleType("KEYWORD");
        rule.setPattern("spam");
        rule.setSeverity("HIGH");
        rule.setAction("BLOCK");
        rule.setEnabled(true);

        when(repository.findByEnabledTrueAndTenantId(tenantId)).thenReturn(Flux.just(rule));

        var result = service.evaluate("this is spam content", tenantId.toString());

        assertThat(result).isPresent();
        assertThat(result.get().ruleName()).isEqualTo("block-spam");
        assertThat(result.get().action()).isEqualTo("BLOCK");
    }

    @Test
    void evaluateReturnsEmptyWhenNoRulesMatch() {
        UUID tenantId = UUID.randomUUID();
        ModerationRule rule = new ModerationRule();
        rule.setRuleType("KEYWORD");
        rule.setPattern("badword");
        rule.setEnabled(true);
        rule.setSeverity("LOW");
        rule.setAction("FLAG");

        when(repository.findByEnabledTrueAndTenantId(tenantId)).thenReturn(Flux.just(rule));

        var result = service.evaluate("clean content", tenantId.toString());

        assertThat(result).isEmpty();
    }

    @Test
    void evaluateReturnsEmptyForBlankText() {
        var result = service.evaluate("", UUID.randomUUID().toString());
        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void evaluateReturnsEmptyForNullTenantId() {
        var result = service.evaluate("some text", null);
        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }
}
```

- [ ] **Step 4: Run to verify tests fail**

```bash
mvn test -pl emcip-moderation-service -Dtest=RuleEvaluationServiceTest --no-transfer-progress | cat
```
Expected: FAIL — method signature mismatch (`evaluate` still takes only `String`).

- [ ] **Step 5: Rewrite RuleEvaluationService — remove cache, per-tenant evaluation**

```java
package io.emcip.moderation.service.service;

import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class RuleEvaluationService {

    private final ModerationRuleRepository repository;

    public Optional<EvaluationResult> evaluate(String text, String tenantId) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }

        List<ModerationRule> rules =
                repository
                        .findByEnabledTrueAndTenantId(UUID.fromString(tenantId))
                        .collectList()
                        .block();

        if (rules == null) {
            return Optional.empty();
        }

        for (ModerationRule rule : rules) {
            boolean matched =
                    switch (rule.getRuleType()) {
                        case "KEYWORD" ->
                                text.toLowerCase().contains(rule.getPattern().toLowerCase());
                        case "REGEX" -> text.matches("(?i).*" + rule.getPattern() + ".*");
                        case "LENGTH" -> text.length() > Integer.parseInt(rule.getPattern());
                        default -> false;
                    };
            if (matched) {
                return Optional.of(
                        new EvaluationResult(
                                rule.getName(),
                                rule.getSeverity(),
                                rule.getAction(),
                                rule.getRuleType()));
            }
        }
        return Optional.empty();
    }

    public record EvaluationResult(
            String ruleName, String severity, String action, String ruleType) {}
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
mvn test -pl emcip-moderation-service -Dtest=RuleEvaluationServiceTest --no-transfer-progress | cat
```
Expected: PASS.

- [ ] **Step 7: Update ModerationEventConsumer — bind tenant from record**

`ModerationEventConsumer.consume()` currently receives `String message`. Change it to `ConsumerRecord<String, String>` to access headers:

```java
package io.emcip.moderation.service.kafka;

import io.emcip.common.events.EventSchemas.ModerationFlagEvent;
import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.common.tenant.TenantContext;
import io.emcip.moderation.service.service.RuleEvaluationService;
import io.emcip.moderation.service.service.RuleEvaluationService.EvaluationResult;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class ModerationEventConsumer {

    private static final String MODERATION_FLAGS_TOPIC = "moderation.flags";

    private final RuleEvaluationService ruleEvaluationService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public ModerationEventConsumer(
            RuleEvaluationService ruleEvaluationService,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.ruleEvaluationService = ruleEvaluationService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @KafkaListener(
            topics = "telegram.raw.messages",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            TenantAwareKafkaSupport.bindTenantFromRecord(record);
            String tenantId = TenantContext.getTenantId();

            TelegramMessageEvent event =
                    objectMapper.readValue(record.value(), TelegramMessageEvent.class);

            String text = event.text();
            Optional<EvaluationResult> result = ruleEvaluationService.evaluate(text, tenantId);

            if (result.isPresent()) {
                EvaluationResult match = result.get();
                log.info(
                        "Moderation rule '{}' matched for event {}: severity={}, action={}",
                        match.ruleName(),
                        event.eventId(),
                        match.severity(),
                        match.action());

                ModerationFlagEvent flagEvent =
                        new ModerationFlagEvent(
                                UUID.randomUUID().toString(),
                                Instant.now().toString(),
                                null,
                                null,
                                event.eventId(),
                                match.ruleType(),
                                match.severity(),
                                "Rule matched: " + match.ruleName(),
                                Map.of("action", match.action(), "ruleName", match.ruleName()));

                String flagJson = objectMapper.writeValueAsString(flagEvent);
                kafkaTemplate.send(MODERATION_FLAGS_TOPIC, event.eventId(), flagJson);
                log.debug(
                        "Published ModerationFlagEvent to {} for source event {}",
                        MODERATION_FLAGS_TOPIC,
                        event.eventId());
            }

            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process telegram message at offset {}: {}", record.offset(), e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            TenantContext.clear();
        }
    }
}
```

- [ ] **Step 8: Update ModerationRuleController to use scoped methods**

Replace the `list()` method:
```java
@GetMapping
@Operation(summary = "List all moderation rules")
public Flux<ModerationRule> list() {
    return repository.findAllOrderedByTenantId(
            UUID.fromString(io.emcip.common.tenant.TenantContext.getTenantId()));
}
```

Replace the `update()` `findById(id)` call with:
```java
return repository
        .findByIdAndTenantId(id, UUID.fromString(io.emcip.common.tenant.TenantContext.getTenantId()))
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
        ...
```

Replace the `create()` method to set tenantId before save:
```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
@Operation(summary = "Create a new moderation rule")
public Mono<ModerationRule> create(@RequestBody ModerationRule rule) {
    rule.setId(null);
    rule.setCreatedAt(Instant.now());
    rule.setUpdatedAt(Instant.now());
    rule.setTenantId(UUID.fromString(io.emcip.common.tenant.TenantContext.getTenantId()));
    if (rule.getSeverity() == null || rule.getSeverity().isBlank()) {
        rule.setSeverity("MEDIUM");
    }
    if (rule.getAction() == null || rule.getAction().isBlank()) {
        rule.setAction("FLAG");
    }
    rule.setEnabled(true);
    return repository.save(rule);
}
```

Add import at top of `ModerationRuleController`:
```java
import java.util.UUID;
```

- [ ] **Step 9: Compile check**

```bash
mvn compile -pl emcip-moderation-service --no-transfer-progress | cat
```
Expected: BUILD SUCCESS.

- [ ] **Step 10: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-moderation-service --no-transfer-progress | cat
git add emcip-moderation-service/src/main/java/io/emcip/moderation/service/entity/ModerationRule.java \
        emcip-moderation-service/src/main/java/io/emcip/moderation/service/repository/ModerationRuleRepository.java \
        emcip-moderation-service/src/main/java/io/emcip/moderation/service/service/RuleEvaluationService.java \
        emcip-moderation-service/src/main/java/io/emcip/moderation/service/kafka/ModerationEventConsumer.java \
        emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java \
        emcip-moderation-service/src/test/java/io/emcip/moderation/service/service/RuleEvaluationServiceTest.java
git commit -m "feat(moderation-service): per-tenant rule evaluation; bind tenant from Kafka record; scope HTTP endpoints"
```

---

## Task 8: emcip-audit-service — per-tenant queries + Kafka binding

**Files:**
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/entity/AuditEventEntity.java`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/repository/AuditEventRepository.java`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java`

**Design note on audit events:** Audit events must not be dropped for missing tenant (legacy Kafka producers may not have the header). Per the spec: if the header is absent, log a warning and save with `null` tenantId rather than discarding.

- [ ] **Step 1: Add tenantId to AuditEventEntity**

Add import:
```java
import java.util.UUID;
```

Add field (after `processingTimeMs`, before `createdAt`):
```java
@Schema(description = "Tenant this audit event belongs to")
@Column("tenant_id")
private UUID tenantId;
```

- [ ] **Step 2: Add scoped methods to AuditEventRepository**

```java
package io.emcip.audit.service.repository;

import io.emcip.audit.service.entity.AuditEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface AuditEventRepository extends ReactiveCrudRepository<AuditEventEntity, Long> {

    Flux<AuditEventEntity> findByEventType(String eventType);

    Flux<AuditEventEntity> findByCreatedAtBetween(Instant from, Instant to);

    Flux<AuditEventEntity> findByEventTypeAndCreatedAtBetween(
            String eventType, Instant from, Instant to);

    Mono<AuditEventEntity> findByEventId(String eventId);

    Flux<AuditEventEntity> findByEventTypeAndTenantId(String eventType, UUID tenantId);

    Flux<AuditEventEntity> findByCreatedAtBetweenAndTenantId(
            Instant from, Instant to, UUID tenantId);

    Flux<AuditEventEntity> findByEventTypeAndCreatedAtBetweenAndTenantId(
            String eventType, Instant from, Instant to, UUID tenantId);

    Mono<AuditEventEntity> findByEventIdAndTenantId(String eventId, UUID tenantId);
}
```

- [ ] **Step 3: Update AuditService to scope queries by tenant**

Replace `AuditService.java` with the scoped variant. `AuditController` calls `findByEventType`, `findByDateRange`, etc. — these now use tenant-scoped methods when a tenant is in context:

```java
package io.emcip.audit.service.service;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.common.tenant.TenantContext;
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
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            return repository.findByEventTypeAndTenantId(eventType, UUID.fromString(tenantId));
        }
        return repository.findByEventType(eventType);
    }

    public Flux<AuditEventEntity> findByDateRange(Instant from, Instant to) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            return repository.findByCreatedAtBetweenAndTenantId(
                    from, to, UUID.fromString(tenantId));
        }
        return repository.findByCreatedAtBetween(from, to);
    }

    public Flux<AuditEventEntity> findByEventTypeAndDateRange(
            String eventType, Instant from, Instant to) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            return repository.findByEventTypeAndCreatedAtBetweenAndTenantId(
                    eventType, from, to, UUID.fromString(tenantId));
        }
        return repository.findByEventTypeAndCreatedAtBetween(eventType, from, to);
    }

    public Mono<AuditEventEntity> findByEventId(String eventId) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            return repository.findByEventIdAndTenantId(eventId, UUID.fromString(tenantId));
        }
        return repository.findByEventId(eventId);
    }

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

- [ ] **Step 4: Update AuditEventConsumer — bind tenant from record, set on entity**

Each of the 5 `@KafkaListener` methods needs: `TenantAwareKafkaSupport.bindTenantFromRecord(record)` at start, `TenantContext.clear()` in finally, and `tenantId` set on the built entity.

The current listeners receive `ConsumerRecord<String, String> record` — no signature change needed.

Update each listener by adding these lines at the start of the `try` block:

```java
TenantAwareKafkaSupport.bindTenantFromRecord(record);
String tenantIdStr = TenantContext.getTenantId();
UUID tenantUuid = tenantIdStr != null ? UUID.fromString(tenantIdStr) : null;
if (tenantUuid == null) {
    log.warn("No tenant_id header on record offset {} topic {} — saving with null tenant",
             record.offset(), record.topic());
}
```

And update each entity builder to include `.tenantId(tenantUuid)`.

And add a `finally` block after each `catch`:
```java
} finally {
    TenantContext.clear();
}
```

Show the full updated `handleTelegramMessage` as a template (repeat the pattern for all 5 listeners):

```java
@KafkaListener(
        topics = "telegram.raw.messages",
        groupId = "emcip-audit-service",
        containerFactory = "kafkaListenerContainerFactory")
public void handleTelegramMessage(
        ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    try {
        TenantAwareKafkaSupport.bindTenantFromRecord(record);
        String tenantIdStr = TenantContext.getTenantId();
        UUID tenantUuid = tenantIdStr != null ? UUID.fromString(tenantIdStr) : null;
        if (tenantUuid == null) {
            log.warn(
                    "No tenant_id header on record offset {} topic {} — saving with null tenant",
                    record.offset(),
                    record.topic());
        }

        EventSchemas.TelegramMessageEvent event =
                objectMapper.readValue(record.value(), EventSchemas.TelegramMessageEvent.class);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("telegramMessageId", event.telegramMessageId());
        details.put("chatId", event.chatId());
        details.put("senderId", event.senderId());
        details.put("senderType", event.senderType());

        AuditEventEntity entity =
                AuditEventEntity.builder()
                        .eventId(event.eventId())
                        .eventType(event.eventType())
                        .correlationId(event.eventId())
                        .sourceService("emcip-tdlib-adapter")
                        .action(event.eventType())
                        .actorType("SYSTEM")
                        .actorId(event.senderId())
                        .resourceType("TelegramMessage")
                        .resourceId(
                                event.telegramMessageId() != null
                                        ? event.telegramMessageId().toString()
                                        : null)
                        .outcome("PROCESSED")
                        .details(auditService.serializeDetails(details))
                        .tenantId(tenantUuid)
                        .createdAt(Instant.now())
                        .build();

        auditService.save(entity).block();
        acknowledgment.acknowledge();

    } catch (JacksonException e) {
        log.error(
                "Permanently malformed telegram.raw.messages record at offset {}, skipping: {}",
                record.offset(),
                e.getMessage());
        acknowledgment.acknowledge();
    } catch (Exception e) {
        log.error(
                "Failed to persist audit event for telegram.raw.messages offset {}: {}",
                record.offset(),
                e.getMessage(),
                e);
        throw new RuntimeException(e);
    } finally {
        TenantContext.clear();
    }
}
```

Apply the same pattern (`bindTenantFromRecord` + `tenantUuid` extraction + `.tenantId(tenantUuid)` on builder + `finally { TenantContext.clear(); }`) to all remaining 4 listener methods: `handleIntentClassified`, `handlePolicyDecision`, `handleResponseGenerated`, `handleModerationFlag`.

Also add these imports to `AuditEventConsumer.java`:
```java
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.common.tenant.TenantContext;
import java.util.UUID;
```

- [ ] **Step 5: Compile check**

```bash
mvn compile -pl emcip-audit-service --no-transfer-progress | cat
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-audit-service --no-transfer-progress | cat
git add emcip-audit-service/src/main/java/io/emcip/audit/service/entity/AuditEventEntity.java \
        emcip-audit-service/src/main/java/io/emcip/audit/service/repository/AuditEventRepository.java \
        emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java \
        emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java
git commit -m "feat(audit-service): scope queries by tenant; bind tenant_id from Kafka headers on all consumers"
```

---

## Task 9: Kafka wiring — PolicyDecisionConsumer + TelegramEventPublisher

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/PolicyDecisionConsumer.java`
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramEventPublisher.java`
- Modify: `helm/emcip/values.yaml`

- [ ] **Step 1: Update PolicyDecisionConsumer — bind tenant from record**

`PolicyDecisionConsumer.consume()` receives `ConsumerRecord<String, String> record`. Add tenant binding at the top of `consume()` and clear in finally:

Add imports:
```java
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.common.tenant.TenantContext;
```

Update `consume()`:
```java
@KafkaListener(
        topics = TOPIC,
        groupId = "llm-orchestrator",
        containerFactory = "kafkaListenerContainerFactory")
public void consume(ConsumerRecord<String, String> record) {
    log.debug(
            "Received policy decision from partition {} offset {}",
            record.partition(),
            record.offset());

    try {
        TenantAwareKafkaSupport.bindTenantFromRecord(record);

        var decisionEvent =
                objectMapper.readValue(record.value(), EventSchemas.PolicyDecisionEvent.class);

        String decision = decisionEvent.decision();
        String sourceEventId = decisionEvent.sourceEventId();

        log.info(
                "Processing policy decision for event {}: decision={}",
                sourceEventId,
                decision);

        switch (decision) {
            case "RESPOND" -> handleRespondDecision(decisionEvent);
            case "ESCALATE" -> handleEscalateDecision(decisionEvent);
            case "EXECUTE" -> handleExecuteDecision(decisionEvent);
            case "BLOCK", "REVIEW" ->
                    log.info(
                            "Policy decision {} for event {} - skipping AI interaction",
                            decision,
                            sourceEventId);
            case "ALLOW" ->
                    log.debug(
                            "Policy ALLOW for event {} - no AI action needed", sourceEventId);
            default ->
                    log.warn(
                            "Unknown policy decision: {} for event {}",
                            decision,
                            sourceEventId);
        }

    } catch (Exception e) {
        log.error("Error processing policy decision: {}", e.getMessage(), e);
    } finally {
        TenantContext.clear();
    }
}
```

- [ ] **Step 2: Update TelegramEventPublisher — inject tenantId and add to ProducerRecord**

`TelegramEventPublisher` is reactive. `ThreadLocal` works inside `Mono.fromCallable()` since the callable runs synchronously on the subscribing thread. The tenant is configured per-deployment via `APP_TENANT_ID`.

Add field after the deduplication cache:
```java
@Value("${app.tenant-id:}")
private String configuredTenantId;
```

Also add the `@Value` import (if not present):
```java
import org.springframework.beans.factory.annotation.Value;
```

In `publishMessage()`, change the `kafkaTemplate.send(...)` call from string topic+key+value to ProducerRecord with tenant header. Replace the line:

```java
return kafkaTemplate.send(
        TOPIC_TELEGRAM_RAW, String.valueOf(message.chatId), json);
```

With:

```java
org.apache.kafka.clients.producer.ProducerRecord<String, String> kafkaRecord =
        new org.apache.kafka.clients.producer.ProducerRecord<>(
                TOPIC_TELEGRAM_RAW, String.valueOf(message.chatId), json);
if (configuredTenantId != null && !configuredTenantId.isBlank()) {
    kafkaRecord
            .headers()
            .add(
                    io.emcip.common.tenant.TenantContext.KAFKA_HEADER,
                    configuredTenantId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
}
return kafkaTemplate.send(kafkaRecord);
```

- [ ] **Step 3: Add APP_TENANT_ID env var to tdlib-adapter in values.yaml**

In `helm/emcip/values.yaml`, in the `tdlibAdapter.env` section, add:

```yaml
  env:
    KAFKA_BOOTSTRAP_SERVERS: "emcip-kafka-bootstrap:9092"
    OTEL_EXPORTER_OTLP_ENDPOINT: "http://emcip-tempo:4318"
    APP_TENANT_ID: ""      # Set to the tenant UUID this adapter serves
```

- [ ] **Step 4: Compile check both modules**

```bash
mvn compile -pl emcip-llm-orchestrator,emcip-tdlib-adapter --no-transfer-progress | cat
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator,emcip-tdlib-adapter --no-transfer-progress | cat
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/PolicyDecisionConsumer.java \
        emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramEventPublisher.java \
        helm/emcip/values.yaml
git commit -m "feat(kafka): bind tenant from Kafka headers in PolicyDecisionConsumer; propagate tenant header in TelegramEventPublisher"
```

---

## Final verification

- [ ] **Step 1: Run all module tests**

```bash
mvn test -pl emcip-core,emcip-admin-api,emcip-conversation-context,emcip-policy-engine,emcip-llm-orchestrator,emcip-moderation-service,emcip-audit-service --no-transfer-progress | cat
```
Expected: All tests PASS.

- [ ] **Step 2: Full project compile**

```bash
mvn compile --no-transfer-progress | cat
```
Expected: BUILD SUCCESS across all modules.

---

## Self-review notes

**Spec coverage check:**
- ✅ Section 1 (emcip-core): Task 1
- ✅ Section 2 (admin-api AdminTenantContextFilter): Tasks 2 + 3
- ✅ Section 3 (JPA Hibernate @Filter): Tasks 4 + 5 + 6
- ✅ Section 4 (R2DBC scoped methods): Tasks 7 + 8
- ✅ Section 5 (Kafka wiring): Tasks 8 (AuditEventConsumer) + 9

**Out of scope (confirmed):** `ModelConfig`, `LlmProviderConfig` — global admin-managed config, no tenantId needed.

**Known limitation:** `TelegramAccountController` has many more endpoints beyond `listAccounts` and `createAccount` that use repository calls (status checks, group watch/unwatch). These read/write `TelegramAccount` and `AccountWatchedGroup` records. The plan updates the two highest-risk endpoints (list and create). The remaining endpoints (`deleteAccount`, status, sync, etc.) should be reviewed against the same pattern — scope reads and writes using the same `isAdminMode()` / `findAllByTenantId()` branching — but are omitted here to keep the plan focused on the data isolation rather than full controller rewrites.
