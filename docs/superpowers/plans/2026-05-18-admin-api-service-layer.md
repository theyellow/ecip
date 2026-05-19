# emcip-admin-api: Extract Service Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract all business logic from admin-api controllers into dedicated `@Service` classes, leaving controllers responsible only for HTTP request/response mapping.

**Architecture:** Six services created (AuthService, TenantService, FlagService, GroupProfileService, SimulationService, TelegramAccountService). Four thin delegation controllers (AIProxy, ModerationRule, PolicyRule, Audit) already delegate to clients with no business logic and are left unchanged. `TelegramAccountService.pushWatchedGroups()` is made `public` to eliminate the verbatim duplicate in `TelegramSessionResumeRunner` — the retry-backoff version from `TelegramSessionResumeRunner` is the authoritative one and moves into the service.

**Tech Stack:** Java 21, Spring Boot 4, Spring WebFlux, Reactor (Mono/Flux), Spring Data R2DBC, Spring Kafka, `tools.jackson.databind.ObjectMapper` (Jackson 3), Lombok `@RequiredArgsConstructor` / `@Slf4j`, JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) + `reactor.test.StepVerifier`

---

## Context

All changes are in `emcip-admin-api`. Key packages:
- `io.emcip.admin.api.controller` — 9 existing controllers
- `io.emcip.admin.api.service` — **new package** for all services
- `io.emcip.admin.api.dto` — **new package** for shared DTOs
- `io.emcip.admin.api.security` — `JwtService`, security filters
- `io.emcip.admin.api.entity` — `AdminUser`, `GroupProfile`, `TelegramAccount`, `Tenant`, `AccountWatchedGroup`, `TelegramAccountStatus`
- `io.emcip.admin.api.repository` — all `ReactiveCrudRepository` interfaces
- `io.emcip.admin.api.client` — `AuditServiceClient`, `ModerationServiceClient`, `PolicyEngineClient`
- `io.emcip.admin.api.config` — `TelegramSessionResumeRunner`, `WebClientConfig`
- `io.emcip.common.tenant` — `TenantContext` (ThreadLocal-based; SC3 will replace with Reactor Context — for now replicate as-is)

Run tests:
```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
```
Format before every commit:
```bash
mvn spotless:apply -pl emcip-admin-api
```

---

## File Structure

**New files:**

| File | Purpose |
|------|---------|
| `src/main/java/io/emcip/admin/api/dto/TokenResponse.java` | Auth token response record (moved from inner record in `AuthController`) |
| `src/main/java/io/emcip/admin/api/service/AuthService.java` | Credential check + JWT token generation |
| `src/main/java/io/emcip/admin/api/service/TenantService.java` | Tenant CRUD with UUID/timestamp assignment |
| `src/main/java/io/emcip/admin/api/service/FlagService.java` | Flag routing logic + status update |
| `src/main/java/io/emcip/admin/api/service/GroupProfileService.java` | Multi-tenant group CRUD with field-merge update |
| `src/main/java/io/emcip/admin/api/service/SimulationService.java` | Kafka event construction + publish |
| `src/main/java/io/emcip/admin/api/service/TelegramAccountService.java` | All Telegram account management (454-line controller → service) |
| `src/test/java/io/emcip/admin/api/service/AuthServiceTest.java` | Service unit tests |
| `src/test/java/io/emcip/admin/api/service/TenantServiceTest.java` | Service unit tests |
| `src/test/java/io/emcip/admin/api/service/FlagServiceTest.java` | Service unit tests |
| `src/test/java/io/emcip/admin/api/service/GroupProfileServiceTest.java` | Service unit tests |
| `src/test/java/io/emcip/admin/api/service/SimulationServiceTest.java` | Service unit tests |
| `src/test/java/io/emcip/admin/api/service/TelegramAccountServiceTest.java` | Service unit tests |

**Files to modify:**

| File | Change |
|------|--------|
| `src/main/java/io/emcip/admin/api/security/JwtService.java` | `EXPIRY_MS` → `public static final` (eliminates duplication with `AuthController`) |
| `src/main/java/io/emcip/admin/api/controller/AuthController.java` | Inject `AuthService`; remove `AdminUserRepository`, `JwtService`, `PasswordEncoder` |
| `src/main/java/io/emcip/admin/api/controller/TenantController.java` | Inject `TenantService`; remove `TenantRepository`, `R2dbcEntityTemplate` |
| `src/main/java/io/emcip/admin/api/controller/FlagController.java` | Inject `FlagService`; remove `PolicyEngineClient` |
| `src/main/java/io/emcip/admin/api/controller/GroupProfileController.java` | Inject `GroupProfileService`; remove `GroupProfileRepository` and all logic |
| `src/main/java/io/emcip/admin/api/controller/SimulateController.java` | Inject `SimulationService`; remove `KafkaTemplate`, `ObjectMapper`, event-building logic |
| `src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java` | Inject `TelegramAccountService`; remove all 5 injected deps + all business logic |
| `src/main/java/io/emcip/admin/api/config/TelegramSessionResumeRunner.java` | Inject `TelegramAccountService`; call `service.pushWatchedGroups()` + `service.initializeAccount()` instead of duplicated private methods |
| `src/test/java/io/emcip/admin/api/controller/AuthControllerTest.java` | Mock `AuthService` instead of repo/jwtService/encoder |
| `src/test/java/io/emcip/admin/api/controller/TenantControllerTest.java` | Mock `TenantService` |
| `src/test/java/io/emcip/admin/api/controller/FlagControllerTest.java` | Mock `FlagService` |
| `src/test/java/io/emcip/admin/api/controller/GroupProfileControllerTest.java` | Mock `GroupProfileService` |
| `src/test/java/io/emcip/admin/api/controller/SimulateControllerTest.java` | Mock `SimulationService` |
| `src/test/java/io/emcip/admin/api/controller/TelegramAccountControllerTest.java` | Mock `TelegramAccountService` |

---

## Task 1: AuthService + TokenResponse DTO

**Files:**
- Create: `src/main/java/io/emcip/admin/api/dto/TokenResponse.java`
- Create: `src/main/java/io/emcip/admin/api/service/AuthService.java`
- Create: `src/test/java/io/emcip/admin/api/service/AuthServiceTest.java`
- Modify: `src/main/java/io/emcip/admin/api/security/JwtService.java`
- Modify: `src/main/java/io/emcip/admin/api/controller/AuthController.java`
- Modify: `src/test/java/io/emcip/admin/api/controller/AuthControllerTest.java`

- [ ] **Step 1: Make `EXPIRY_MS` public in `JwtService`**

In `JwtService.java`, change:
```java
private static final long EXPIRY_MS = 8 * 60 * 60 * 1000L;
```
to:
```java
public static final long EXPIRY_MS = 8 * 60 * 60 * 1000L;
```

- [ ] **Step 2: Create `TokenResponse.java` DTO**

```java
package io.emcip.admin.api.dto;

import java.time.Instant;

public record TokenResponse(String token, Instant expiresAt) {}
```

- [ ] **Step 3: Write failing `AuthServiceTest`**

```java
package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.entity.AdminUser;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private AdminUserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;

    @InjectMocks private AuthService authService;

    private AdminUser enabledUser() {
        return AdminUser.builder()
                .id(1L)
                .username("admin")
                .passwordHash("$2a$hash")
                .role("ADMIN")
                .enabled(true)
                .build();
    }

    @Test
    void authenticate_validCredentials_returnsToken() {
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
        when(passwordEncoder.matches("secret", "$2a$hash")).thenReturn(true);
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("jwt-abc");

        StepVerifier.create(authService.authenticate("admin", "secret"))
                .assertNext(
                        resp -> {
                            assertThat(resp.token()).isEqualTo("jwt-abc");
                            assertThat(resp.expiresAt()).isNotNull();
                        })
                .verifyComplete();
    }

    @Test
    void authenticate_wrongPassword_returnsUnauthorized() {
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        StepVerifier.create(authService.authenticate("admin", "wrong"))
                .expectErrorMatches(
                        e ->
                                e.getMessage() != null
                                        && e.getMessage().contains("Invalid credentials"))
                .verify();
    }

    @Test
    void authenticate_unknownUser_returnsUnauthorized() {
        when(userRepository.findByUsername("nobody")).thenReturn(Mono.empty());

        StepVerifier.create(authService.authenticate("nobody", "pass"))
                .expectErrorMatches(
                        e ->
                                e.getMessage() != null
                                        && e.getMessage().contains("Invalid credentials"))
                .verify();
    }

    @Test
    void authenticate_disabledUser_returnsUnauthorized() {
        AdminUser disabled =
                AdminUser.builder()
                        .id(2L)
                        .username("admin")
                        .passwordHash("$2a$hash")
                        .role("ADMIN")
                        .enabled(false)
                        .build();
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(disabled));

        StepVerifier.create(authService.authenticate("admin", "secret"))
                .expectError()
                .verify();
    }
}
```

- [ ] **Step 4: Run test to confirm failure**

```bash
mvn test -pl emcip-admin-api -Dtest=AuthServiceTest -q 2>&1 | tail -5
```
Expected: FAIL (class not found)

- [ ] **Step 5: Implement `AuthService`**

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.security.JwtService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AdminUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public Mono<TokenResponse> authenticate(String username, String password) {
        return userRepository
                .findByUsername(username)
                .filter(
                        user ->
                                user.isEnabled()
                                        && passwordEncoder.matches(
                                                password, user.getPasswordHash()))
                .map(
                        user ->
                                new TokenResponse(
                                        jwtService.generateToken(
                                                user.getUsername(), user.getRole()),
                                        Instant.now().plusMillis(JwtService.EXPIRY_MS)))
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "Invalid credentials")));
    }
}
```

- [ ] **Step 6: Run service test to confirm pass**

```bash
mvn test -pl emcip-admin-api -Dtest=AuthServiceTest -q 2>&1 | tail -5
```
Expected: 4 tests, 0 failures

- [ ] **Step 7: Update `AuthController` to delegate to `AuthService`**

Replace the full class with:
```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Obtain JWT tokens")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Obtain a JWT token")
    @PostMapping({"/api/auth/token", "/auth/token"})
    public Mono<ResponseEntity<TokenResponse>> token(@RequestBody AuthRequest request) {
        return authService
                .authenticate(request.username(), request.password())
                .map(ResponseEntity::ok);
    }

    public record AuthRequest(String username, String password) {}
}
```

- [ ] **Step 8: Update `AuthControllerTest` to mock `AuthService`**

Replace the full class with:
```java
package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.service.AuthService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private AuthService authService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new AuthController(authService)).build();
    }

    @Test
    void token_validCredentials_returns200WithToken() {
        when(authService.authenticate("admin", "secret"))
                .thenReturn(Mono.just(new TokenResponse("jwt-token-abc", Instant.now())));

        webTestClient
                .post()
                .uri("/api/auth/token")
                .bodyValue(new AuthController.AuthRequest("admin", "secret"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(TokenResponse.class)
                .value(resp -> assertThat(resp.token()).isEqualTo("jwt-token-abc"));
    }

    @Test
    void token_invalidCredentials_returns401() {
        when(authService.authenticate("admin", "wrong"))
                .thenReturn(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "Invalid credentials")));

        webTestClient
                .post()
                .uri("/api/auth/token")
                .bodyValue(new AuthController.AuthRequest("admin", "wrong"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }
}
```

- [ ] **Step 9: Run all admin-api tests**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 0 failures

- [ ] **Step 10: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "refactor(admin-api): extract AuthService; move TokenResponse to dto package"
```

---

## Task 2: TenantService

**Files:**
- Create: `src/main/java/io/emcip/admin/api/service/TenantService.java`
- Create: `src/test/java/io/emcip/admin/api/service/TenantServiceTest.java`
- Modify: `src/main/java/io/emcip/admin/api/controller/TenantController.java`
- Modify: `src/test/java/io/emcip/admin/api/controller/TenantControllerTest.java`

- [ ] **Step 1: Write failing `TenantServiceTest`**

```java
package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private R2dbcEntityTemplate r2dbcEntityTemplate;

    @InjectMocks private TenantService tenantService;

    @Test
    void findAll_returnsTenants() {
        Tenant t = new Tenant();
        t.setName("acme");
        when(tenantRepository.findAll()).thenReturn(Flux.just(t));

        StepVerifier.create(tenantService.findAll())
                .assertNext(tenant -> assertThat(tenant.getName()).isEqualTo("acme"))
                .verifyComplete();
    }

    @Test
    void create_assignsIdAndTimestamp() {
        Tenant input = new Tenant();
        input.setName("new-tenant");
        when(r2dbcEntityTemplate.insert(any(Tenant.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(tenantService.create(input))
                .assertNext(
                        tenant -> {
                            assertThat(tenant.getId()).isNotNull();
                            assertThat(tenant.getCreatedAt()).isNotNull();
                            assertThat(tenant.getName()).isEqualTo("new-tenant");
                        })
                .verifyComplete();
    }

    @Test
    void delete_delegatesToRepository() {
        when(tenantRepository.deleteById(any())).thenReturn(Mono.empty());
        java.util.UUID id = java.util.UUID.randomUUID();

        StepVerifier.create(tenantService.delete(id)).verifyComplete();

        verify(tenantRepository).deleteById(id);
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
mvn test -pl emcip-admin-api -Dtest=TenantServiceTest -q 2>&1 | tail -5
```
Expected: FAIL (class not found)

- [ ] **Step 3: Implement `TenantService`**

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.TenantRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcEntityTemplate;

    public Flux<Tenant> findAll() {
        return tenantRepository.findAll();
    }

    public Mono<Tenant> create(Tenant tenant) {
        tenant.setId(UUID.randomUUID());
        tenant.setCreatedAt(Instant.now());
        return r2dbcEntityTemplate.insert(tenant);
    }

    public Mono<Void> delete(UUID id) {
        return tenantRepository.deleteById(id);
    }
}
```

- [ ] **Step 4: Run service test to confirm pass**

```bash
mvn test -pl emcip-admin-api -Dtest=TenantServiceTest -q 2>&1 | tail -5
```
Expected: 3 tests, 0 failures

- [ ] **Step 5: Update `TenantController`**

Replace the full class with:
```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
@Tag(name = "Tenants", description = "Manage tenants")
public class TenantController {

    private final TenantService tenantService;

    @Operation(summary = "List all tenants")
    @GetMapping
    public Flux<Tenant> listTenants() {
        return tenantService.findAll();
    }

    @Operation(summary = "Create a tenant")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Tenant> createTenant(@RequestBody Tenant tenant) {
        return tenantService.create(tenant);
    }

    @Operation(summary = "Delete a tenant")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTenant(@PathVariable UUID id) {
        return tenantService.delete(id);
    }
}
```

- [ ] **Step 6: Update `TenantControllerTest`**

Read the existing test first (`src/test/java/io/emcip/admin/api/controller/TenantControllerTest.java`), then replace it with a version that mocks `TenantService`:

```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.service.TenantService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class TenantControllerTest {

    @Mock private TenantService tenantService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new TenantController(tenantService)).build();
    }

    private Tenant tenant(String name) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setName(name);
        t.setCreatedAt(Instant.now());
        return t;
    }

    @Test
    void listTenants_returns200() {
        when(tenantService.findAll()).thenReturn(Flux.just(tenant("acme"), tenant("beta")));

        webTestClient.get().uri("/api/tenants").exchange().expectStatus().isOk();
    }

    @Test
    void createTenant_returns201() {
        Tenant saved = tenant("new");
        when(tenantService.create(any())).thenReturn(Mono.just(saved));

        webTestClient
                .post()
                .uri("/api/tenants")
                .bodyValue(new Tenant())
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void deleteTenant_returns204() {
        when(tenantService.delete(any())).thenReturn(Mono.empty());

        webTestClient
                .delete()
                .uri("/api/tenants/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNoContent();
    }
}
```

- [ ] **Step 7: Run all admin-api tests and commit**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "refactor(admin-api): extract TenantService"
```

---

## Task 3: FlagService

**Files:**
- Create: `src/main/java/io/emcip/admin/api/service/FlagService.java`
- Create: `src/test/java/io/emcip/admin/api/service/FlagServiceTest.java`
- Modify: `src/main/java/io/emcip/admin/api/controller/FlagController.java`
- Modify: `src/test/java/io/emcip/admin/api/controller/FlagControllerTest.java`

- [ ] **Step 1: Write failing `FlagServiceTest`**

```java
package io.emcip.admin.api.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.client.PolicyEngineClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

    @Mock private PolicyEngineClient policyEngineClient;

    @InjectMocks private FlagService flagService;

    @Test
    void listFlags_withoutDecision_callsListFlags() {
        when(policyEngineClient.listFlags(50))
                .thenReturn(Flux.just(JsonNodeFactory.instance.objectNode()));

        StepVerifier.create(flagService.listFlags(50, null)).expectNextCount(1).verifyComplete();

        verify(policyEngineClient).listFlags(50);
    }

    @Test
    void listFlags_withDecision_callsListDecisionsByType() {
        when(policyEngineClient.listDecisionsByType("BLOCK", 10))
                .thenReturn(Flux.just(JsonNodeFactory.instance.objectNode()));

        StepVerifier.create(flagService.listFlags(10, "BLOCK"))
                .expectNextCount(1)
                .verifyComplete();

        verify(policyEngineClient).listDecisionsByType("BLOCK", 10);
    }

    @Test
    void updateStatus_missingStatus_returnsError() {
        StepVerifier.create(flagService.updateStatus("id-1", Map.of()))
                .expectErrorMatches(
                        e ->
                                e instanceof IllegalArgumentException
                                        && e.getMessage().contains("status"))
                .verify();
    }

    @Test
    void updateStatus_validStatus_delegatesToClient() {
        when(policyEngineClient.updateDecisionStatus("id-1", "REVIEWED"))
                .thenReturn(Mono.empty());

        StepVerifier.create(flagService.updateStatus("id-1", Map.of("status", "REVIEWED")))
                .verifyComplete();

        verify(policyEngineClient).updateDecisionStatus("id-1", "REVIEWED");
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
mvn test -pl emcip-admin-api -Dtest=FlagServiceTest -q 2>&1 | tail -5
```

- [ ] **Step 3: Implement `FlagService`**

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.client.PolicyEngineClient;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class FlagService {

    private final PolicyEngineClient policyEngineClient;

    public Flux<JsonNode> listFlags(int size, String decision) {
        if (decision != null && !decision.isBlank()) {
            return policyEngineClient.listDecisionsByType(decision, size);
        }
        return policyEngineClient.listFlags(size);
    }

    public Mono<Void> updateStatus(String id, Map<String, String> body) {
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            return Mono.error(new IllegalArgumentException("status is required"));
        }
        return policyEngineClient.updateDecisionStatus(id, status);
    }
}
```

- [ ] **Step 4: Run service test to confirm pass**

```bash
mvn test -pl emcip-admin-api -Dtest=FlagServiceTest -q 2>&1 | tail -5
```
Expected: 4 tests, 0 failures

- [ ] **Step 5: Update `FlagController`**

Replace the full class (read it first to preserve any OpenAPI annotations):
```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.service.FlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/flags")
@RequiredArgsConstructor
@Tag(name = "Flags", description = "Manage policy decisions and flags")
public class FlagController {

    private final FlagService flagService;

    @Operation(summary = "List policy decisions / flags")
    @GetMapping
    public Flux<JsonNode> getFlags(
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String decision) {
        return flagService.listFlags(size, decision);
    }

    @Operation(summary = "Update the status of a flag")
    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> updateStatus(
            @PathVariable String id, @RequestBody Map<String, String> body) {
        return flagService.updateStatus(id, body);
    }
}
```

- [ ] **Step 6: Update `FlagControllerTest`**

Read existing test first, then replace with service-mocked version:
```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.service.FlagService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.JsonNodeFactory;

@ExtendWith(MockitoExtension.class)
class FlagControllerTest {

    @Mock private FlagService flagService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new FlagController(flagService)).build();
    }

    @Test
    void getFlags_returns200() {
        when(flagService.listFlags(anyInt(), any()))
                .thenReturn(Flux.just(JsonNodeFactory.instance.objectNode()));

        webTestClient.get().uri("/api/flags").exchange().expectStatus().isOk();
    }

    @Test
    void getFlags_withDecision_passesFilter() {
        when(flagService.listFlags(50, "BLOCK"))
                .thenReturn(Flux.just(JsonNodeFactory.instance.objectNode()));

        webTestClient
                .get()
                .uri("/api/flags?decision=BLOCK")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void updateStatus_returns204() {
        when(flagService.updateStatus(anyString(), any())).thenReturn(Mono.empty());

        webTestClient
                .patch()
                .uri("/api/flags/id-1/status")
                .bodyValue(Map.of("status", "REVIEWED"))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void updateStatus_missingStatus_returns400() {
        when(flagService.updateStatus(anyString(), any()))
                .thenReturn(
                        Mono.error(new IllegalArgumentException("status is required")));

        webTestClient
                .patch()
                .uri("/api/flags/id-1/status")
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
}
```

- [ ] **Step 7: Run all tests and commit**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "refactor(admin-api): extract FlagService"
```

---

## Task 4: GroupProfileService

**Files:**
- Create: `src/main/java/io/emcip/admin/api/service/GroupProfileService.java`
- Create: `src/test/java/io/emcip/admin/api/service/GroupProfileServiceTest.java`
- Modify: `src/main/java/io/emcip/admin/api/controller/GroupProfileController.java`
- Modify: `src/test/java/io/emcip/admin/api/controller/GroupProfileControllerTest.java`

- [ ] **Step 1: Write failing `GroupProfileServiceTest`**

```java
package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.common.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GroupProfileServiceTest {

    @Mock private GroupProfileRepository repository;

    @InjectMocks private GroupProfileService groupProfileService;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private GroupProfile profile(long chatId) {
        GroupProfile p = new GroupProfile();
        p.setTelegramChatId(chatId);
        p.setName("group-" + chatId);
        return p;
    }

    @Test
    void findAll_adminMode_returnsAll() {
        TenantContext.setAdminMode(true);
        when(repository.findAll()).thenReturn(Flux.just(profile(1L), profile(2L)));

        StepVerifier.create(groupProfileService.findAll())
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void findAll_tenantMode_scopesToTenant() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId.toString());
        when(repository.findAllByTenantId(tenantId)).thenReturn(Flux.just(profile(1L)));

        StepVerifier.create(groupProfileService.findAll())
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void create_setsTimestamps() {
        TenantContext.setAdminMode(true);
        GroupProfile input = profile(99L);
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(groupProfileService.create(input))
                .assertNext(
                        p -> {
                            assertThat(p.getCreatedAt()).isNotNull();
                            assertThat(p.getUpdatedAt()).isNotNull();
                        })
                .verifyComplete();
    }

    @Test
    void findByChatId_notFound_returns404() {
        TenantContext.setAdminMode(true);
        when(repository.findByTelegramChatId(999L)).thenReturn(Mono.empty());

        StepVerifier.create(groupProfileService.findByChatId(999L))
                .expectErrorMatches(
                        e ->
                                e.getMessage() != null
                                        && e.getMessage().contains("404"))
                .verify();
    }

    @Test
    void update_mergesFields() {
        TenantContext.setAdminMode(true);
        GroupProfile existing = profile(5L);
        existing.setModerationLevel("LOW");
        when(repository.findByTelegramChatId(5L)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        GroupProfile patch = new GroupProfile();
        patch.setName("updated-name");
        patch.setModerationLevel("HIGH");

        StepVerifier.create(groupProfileService.update(5L, patch))
                .assertNext(
                        p -> {
                            assertThat(p.getName()).isEqualTo("updated-name");
                            assertThat(p.getModerationLevel()).isEqualTo("HIGH");
                            assertThat(p.getUpdatedAt()).isNotNull();
                        })
                .verifyComplete();
    }
}
```

> **Note:** `TenantContext` is from `emcip-core`. Check the actual method signatures:
> - `TenantContext.setAdminMode(boolean)` or `TenantContext.setAdmin()` — read the class to confirm
> - `TenantContext.setTenantId(String)` or `TenantContext.set(UUID)` — read the class to confirm
> - `TenantContext.isAdminMode()` — confirmed by existing controller code
> - `TenantContext.getTenantId()` — returns `UUID`
> - `TenantContext.clear()` — to clean up after each test
>
> Read `emcip-core/src/main/java/io/emcip/common/tenant/TenantContext.java` before writing the test to get exact method names.

- [ ] **Step 2: Run test to confirm failure**

```bash
mvn test -pl emcip-admin-api -Dtest=GroupProfileServiceTest -q 2>&1 | tail -5
```

- [ ] **Step 3: Implement `GroupProfileService`**

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.common.tenant.TenantContext;
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
        if (TenantContext.isAdminMode()) {
            return repository.findAll();
        }
        return repository.findAllByTenantId(TenantContext.getTenantId());
    }

    public Mono<GroupProfile> findByChatId(long chatId) {
        if (TenantContext.isAdminMode()) {
            return repository
                    .findByTelegramChatId(chatId)
                    .switchIfEmpty(notFound(chatId));
        }
        return repository
                .findByTelegramChatIdAndTenantId(chatId, TenantContext.getTenantId())
                .switchIfEmpty(notFound(chatId));
    }

    public Mono<GroupProfile> create(GroupProfile profile) {
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        if (!TenantContext.isAdminMode()) {
            profile.setTenantId(TenantContext.getTenantId());
        }
        return repository.save(profile);
    }

    public Mono<GroupProfile> update(long chatId, GroupProfile patch) {
        return findByChatId(chatId)
                .flatMap(
                        existing -> {
                            if (patch.getName() != null) existing.setName(patch.getName());
                            if (patch.getDescription() != null)
                                existing.setDescription(patch.getDescription());
                            if (patch.getModerationLevel() != null)
                                existing.setModerationLevel(patch.getModerationLevel());
                            existing.setAutoRespond(patch.isAutoRespond());
                            if (patch.getWelcomeMessage() != null)
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
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Group not found: " + chatId));
    }
}
```

- [ ] **Step 4: Run service tests**

```bash
mvn test -pl emcip-admin-api -Dtest=GroupProfileServiceTest -q 2>&1 | tail -5
```
Expected: 5 tests, 0 failures

- [ ] **Step 5: Update `GroupProfileController`**

Read the existing controller first, then replace with:
```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.service.GroupProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@Tag(name = "Groups", description = "Manage Telegram group profiles")
public class GroupProfileController {

    private final GroupProfileService groupProfileService;

    @Operation(summary = "List all group profiles")
    @GetMapping
    public Flux<GroupProfile> listGroups() {
        return groupProfileService.findAll();
    }

    @Operation(summary = "Get a group profile by Telegram chat ID")
    @GetMapping("/{chatId}")
    public Mono<ResponseEntity<GroupProfile>> getGroup(@PathVariable long chatId) {
        return groupProfileService.findByChatId(chatId).map(ResponseEntity::ok);
    }

    @Operation(summary = "Create a group profile")
    @PostMapping
    public Mono<ResponseEntity<GroupProfile>> createGroup(@RequestBody GroupProfile profile) {
        return groupProfileService
                .create(profile)
                .map(saved -> ResponseEntity.status(HttpStatus.CREATED).body(saved));
    }

    @Operation(summary = "Update a group profile")
    @PutMapping("/{chatId}")
    public Mono<ResponseEntity<GroupProfile>> updateGroup(
            @PathVariable long chatId, @RequestBody GroupProfile patch) {
        return groupProfileService.update(chatId, patch).map(ResponseEntity::ok);
    }

    @Operation(summary = "Delete a group profile")
    @DeleteMapping("/{chatId}")
    public Mono<ResponseEntity<Void>> deleteGroup(@PathVariable long chatId) {
        return groupProfileService
                .delete(chatId)
                .thenReturn(ResponseEntity.<Void>noContent().build());
    }
}
```

- [ ] **Step 6: Update `GroupProfileControllerTest`**

Read existing test, then replace with a service-mocked version testing HTTP contract:
```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.service.GroupProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class GroupProfileControllerTest {

    @Mock private GroupProfileService groupProfileService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient =
                WebTestClient.bindToController(new GroupProfileController(groupProfileService))
                        .build();
    }

    private GroupProfile profile(long chatId) {
        GroupProfile p = new GroupProfile();
        p.setTelegramChatId(chatId);
        p.setName("test-group");
        return p;
    }

    @Test
    void listGroups_returns200() {
        when(groupProfileService.findAll()).thenReturn(Flux.just(profile(1L)));

        webTestClient.get().uri("/api/groups").exchange().expectStatus().isOk();
    }

    @Test
    void getGroup_found_returns200() {
        when(groupProfileService.findByChatId(42L)).thenReturn(Mono.just(profile(42L)));

        webTestClient.get().uri("/api/groups/42").exchange().expectStatus().isOk();
    }

    @Test
    void getGroup_notFound_returns404() {
        when(groupProfileService.findByChatId(99L))
                .thenReturn(
                        Mono.error(
                                new ResponseStatusException(HttpStatus.NOT_FOUND)));

        webTestClient.get().uri("/api/groups/99").exchange().expectStatus().isNotFound();
    }

    @Test
    void createGroup_returns201() {
        when(groupProfileService.create(any())).thenReturn(Mono.just(profile(1L)));

        webTestClient
                .post()
                .uri("/api/groups")
                .bodyValue(new GroupProfile())
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void updateGroup_found_returns200() {
        when(groupProfileService.update(anyLong(), any())).thenReturn(Mono.just(profile(1L)));

        webTestClient
                .put()
                .uri("/api/groups/1")
                .bodyValue(new GroupProfile())
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void deleteGroup_returns204() {
        when(groupProfileService.delete(anyLong())).thenReturn(Mono.empty());

        webTestClient.delete().uri("/api/groups/1").exchange().expectStatus().isNoContent();
    }
}
```

- [ ] **Step 7: Run all tests and commit**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "refactor(admin-api): extract GroupProfileService with multi-tenant scoping"
```

---

## Task 5: SimulationService

**Files:**
- Create: `src/main/java/io/emcip/admin/api/service/SimulationService.java`
- Create: `src/test/java/io/emcip/admin/api/service/SimulationServiceTest.java`
- Modify: `src/main/java/io/emcip/admin/api/controller/SimulateController.java`
- Modify: `src/test/java/io/emcip/admin/api/controller/SimulateControllerTest.java`

**Key context:**
- `SimulateController.SimulateMessageRequest` is a `@Data` class (Lombok) with fields: `chatId` (Long), `senderId` (String), `senderType` (String), `text` (String), `telegramMessageId` (Long). Keep it in the controller (it has OpenAPI `@Schema` annotations).
- `EventSchemas.TelegramMessageEvent` constructor has 16 parameters — replicate exactly as in the existing controller.
- `tools.jackson.databind.ObjectMapper` is Jackson 3 — inject from Spring context instead of `new ObjectMapper()`.
- `tools.jackson.core.JacksonException` is the checked exception.

- [ ] **Step 1: Write failing `SimulationServiceTest`**

```java
package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.controller.SimulateController.SimulateMessageRequest;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private SimulationService simulationService;

    @SuppressWarnings("unchecked")
    private void mockKafkaSend() {
        SendResult<String, String> result =
                new SendResult<>(null, new RecordMetadata(null, 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(result));
    }

    private SimulateMessageRequest request(long chatId) {
        SimulateMessageRequest req = new SimulateMessageRequest();
        req.setChatId(chatId);
        req.setText("hello");
        return req;
    }

    @Test
    void simulate_publishesToKafka() throws Exception {
        mockKafkaSend();
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        StepVerifier.create(simulationService.simulate(request(123L))).expectNextCount(1).verifyComplete();

        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    @Test
    void simulate_returnsEventIdAndTopic() throws Exception {
        mockKafkaSend();
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        StepVerifier.create(simulationService.simulate(request(456L)))
                .assertNext(
                        result -> {
                            assertThat(result.eventId()).isNotBlank();
                            assertThat(result.topic()).isEqualTo("telegram.raw.messages");
                        })
                .verifyComplete();
    }

    @Test
    void simulate_usesDefaultsForNullFields() throws Exception {
        mockKafkaSend();
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        SimulateMessageRequest req = new SimulateMessageRequest();
        req.setChatId(77L);
        // text/senderId/senderType/telegramMessageId all null

        StepVerifier.create(simulationService.simulate(req)).expectNextCount(1).verifyComplete();
    }
}
```

> **Note:** The `when(objectMapper.writeValueAsString(any()))` mock uses Mockito's `any()` — import `org.mockito.ArgumentMatchers.any`. If the test fails because `ObjectMapper.writeValueAsString` isn't mockable (final method), use `tools.jackson.databind.ObjectMapper` as a Spy instead, or use a real `ObjectMapper` instance. Adjust accordingly.

- [ ] **Step 2: Run test to confirm failure**

```bash
mvn test -pl emcip-admin-api -Dtest=SimulationServiceTest -q 2>&1 | tail -5
```

- [ ] **Step 3: Implement `SimulationService`**

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.controller.SimulateController.SimulateMessageRequest;
import io.emcip.common.events.EventSchemas;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class SimulationService {

    static final String TOPIC = "telegram.raw.messages";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public record SimulateResult(String eventId, String topic) {}

    public Mono<SimulateResult> simulate(SimulateMessageRequest req) {
        String eventId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();

        EventSchemas.TelegramMessageEvent event =
                new EventSchemas.TelegramMessageEvent(
                        eventId,
                        timestamp,
                        null,
                        null,
                        req.getTelegramMessageId() != null
                                ? req.getTelegramMessageId()
                                : System.currentTimeMillis(),
                        req.getChatId(),
                        req.getSenderId() != null ? req.getSenderId() : "sim-user",
                        req.getSenderType() != null ? req.getSenderType() : "USER",
                        req.getText(),
                        (int) (System.currentTimeMillis() / 1000),
                        null,
                        false,
                        null,
                        null,
                        null,
                        null);

        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(TOPIC, String.valueOf(req.getChatId()), payload);
            return Mono.just(new SimulateResult(eventId, TOPIC));
        } catch (JacksonException e) {
            log.error("Failed to serialize simulation event", e);
            return Mono.error(new RuntimeException("Failed to serialize event", e));
        }
    }
}
```

> **Note on EventSchemas constructor:** The constructor above replicates the exact argument order from the existing `SimulateController`. Read `emcip-core/src/main/java/io/emcip/common/events/EventSchemas.java` to verify the constructor signature before writing this code — parameter count and order must match exactly.

- [ ] **Step 4: Run service tests**

```bash
mvn test -pl emcip-admin-api -Dtest=SimulationServiceTest -q 2>&1 | tail -5
```
Expected: 3 tests, 0 failures

- [ ] **Step 5: Update `SimulateController`**

The `SimulateMessageRequest` inner class stays in `SimulateController`. Replace the controller body:
```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.service.SimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/simulate")
@RequiredArgsConstructor
@Tag(name = "Simulation", description = "Inject test messages through the full pipeline")
public class SimulateController {

    private final SimulationService simulationService;

    @Operation(summary = "Simulate a Telegram message through the processing pipeline")
    @PostMapping("/message")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> simulateMessage(@RequestBody SimulateMessageRequest req) {
        return simulationService
                .simulate(req)
                .map(
                        result ->
                                Map.of(
                                        "eventId", result.eventId(),
                                        "topic", result.topic(),
                                        "chatId", req.getChatId(),
                                        "status", "published"));
    }

    @Schema(description = "Request to inject a simulated Telegram message into the pipeline")
    @Data
    public static class SimulateMessageRequest {
        @Schema(description = "Telegram chat ID", example = "-1001234567890")
        private Long chatId;

        @Schema(description = "Sender identifier", example = "user-42")
        private String senderId;

        @Schema(
                description = "Sender type",
                example = "USER",
                allowableValues = {"USER", "BOT"})
        private String senderType;

        @Schema(description = "Message text to classify and process", example = "Hello everyone!")
        private String text;

        @Schema(description = "Optional Telegram message ID override")
        private Long telegramMessageId;
    }
}
```

- [ ] **Step 6: Update `SimulateControllerTest`**

Replace with a service-mocked version. The existing tests verify response shape — keep those assertions, but mock `SimulationService`:
```java
package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.service.SimulationService;
import io.emcip.admin.api.service.SimulationService.SimulateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SimulateControllerTest {

    @Mock private SimulationService simulationService;

    private SimulateController controller;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        controller = new SimulateController(simulationService);
        webTestClient = WebTestClient.bindToController(controller).build();
        when(simulationService.simulate(any()))
                .thenReturn(Mono.just(new SimulateResult("evt-123", "telegram.raw.messages")));
    }

    private SimulateController.SimulateMessageRequest request(long chatId) {
        SimulateController.SimulateMessageRequest req =
                new SimulateController.SimulateMessageRequest();
        req.setChatId(chatId);
        req.setText("hello world");
        return req;
    }

    @Test
    void simulateMessage_returnsPublishedResponse() {
        StepVerifier.create(controller.simulateMessage(request(12345L)))
                .assertNext(
                        response -> {
                            assertThat(response.get("topic")).isEqualTo("telegram.raw.messages");
                            assertThat(response.get("chatId")).isEqualTo(12345L);
                            assertThat(response.get("status")).isEqualTo("published");
                            assertThat(response.get("eventId")).isEqualTo("evt-123");
                        })
                .verifyComplete();
    }

    @Test
    void simulateMessage_returns202() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .bodyValue(request(555L))
                .exchange()
                .expectStatus()
                .isAccepted();
    }

    @Test
    void simulateMessage_returnsCorrectResponseBody() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .bodyValue(request(666L))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.topic")
                .isEqualTo("telegram.raw.messages")
                .jsonPath("$.status")
                .isEqualTo("published")
                .jsonPath("$.eventId")
                .isEqualTo("evt-123");
    }
}
```

- [ ] **Step 7: Run all tests and commit**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "refactor(admin-api): extract SimulationService; inject Spring ObjectMapper"
```

---

## Task 6: TelegramAccountService

This is the largest task. The controller is 454 lines; the service will hold all business logic and also eliminate the `pushWatchedGroups` duplicate in `TelegramSessionResumeRunner`.

**Files:**
- Create: `src/main/java/io/emcip/admin/api/service/TelegramAccountService.java`
- Create: `src/test/java/io/emcip/admin/api/service/TelegramAccountServiceTest.java`
- Modify: `src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java`
- Modify: `src/main/java/io/emcip/admin/api/config/TelegramSessionResumeRunner.java`
- Modify: `src/test/java/io/emcip/admin/api/controller/TelegramAccountControllerTest.java`

**Key context — read these files before implementing:**
- `src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java` — full 454-line controller
- `src/main/java/io/emcip/admin/api/config/TelegramSessionResumeRunner.java` — has the authoritative `pushWatchedGroups` with retry backoff (5 retries, 2s base, 30s max)
- `src/test/java/io/emcip/admin/api/controller/TelegramAccountControllerTest.java` — existing 9 tests

**Service API:**

```java
// Multi-tenant scoped
Flux<TelegramAccount> findAll();
Mono<TelegramAccount> getById(UUID id);          // 404 if not found
Mono<TelegramAccount> create(String phoneNumber, String displayName, UUID tenantId);
Mono<Void> delete(UUID id);

// Telegram auth flow (delegate to tdlib)
Mono<TelegramAccount> getStatus(UUID id);         // calls tdlib, syncs DB if changed
Mono<TelegramAccount> reconnect(UUID id);         // calls tdlib /api/auth/{id}/initialize
Mono<Void> submitCode(UUID id, String code);      // calls tdlib /api/auth/{id}/code
Mono<Void> submitPassword(UUID id, String password); // calls tdlib /api/auth/{id}/password
Mono<Void> logout(UUID id);                       // calls tdlib, sets DISCONNECTED in DB

// Group watching
Mono<Void> sync();                                // pushWatchedGroups for all ACTIVE accounts
Flux<GroupProfile> findWatchedGroups(UUID accountId);
Mono<GroupProfile> watchGroup(UUID accountId, long chatId, String title);
Mono<Void> unwatchGroup(UUID accountId, long chatId);
Mono<Void> pushWatchedGroups(UUID accountId);     // public — used by TelegramSessionResumeRunner

// Discovery
Mono<List<Map<String, Object>>> discoverChats(UUID accountId);

// Also needed by TelegramSessionResumeRunner:
Mono<Void> initializeAccount(TelegramAccount account); // calls tdlib /api/auth/{id}/initialize
```

- [ ] **Step 1: Write `TelegramAccountServiceTest` (focused on critical paths)**

Read `TelegramAccountController.java` fully before writing this test.

```java
package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.common.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
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

    @InjectMocks private TelegramAccountService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
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
        TenantContext.setAdminMode(true);
        UUID id = UUID.randomUUID();
        when(repository.findAll()).thenReturn(Flux.just(account(id)));

        StepVerifier.create(service.findAll()).expectNextCount(1).verifyComplete();
    }

    @Test
    void getById_notFound_returns404() {
        TenantContext.setAdminMode(true);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.getById(id))
                .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("404"))
                .verify();
    }

    @Test
    void create_setsStatusUnconfiguredAndApiCredentials() {
        UUID tenantId = UUID.randomUUID();
        when(r2dbcEntityTemplate.insert(any(TelegramAccount.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.create("+49123", "Test Account", tenantId))
                .assertNext(
                        a -> {
                            assertThat(a.getStatus()).isEqualTo(TelegramAccountStatus.UNCONFIGURED);
                            assertThat(a.getPhoneNumber()).isEqualTo("+49123");
                            assertThat(a.getTenantId()).isEqualTo(tenantId);
                            assertThat(a.getCreatedAt()).isNotNull();
                        })
                .verifyComplete();
    }

    @Test
    void delete_callsDeleteById() {
        UUID id = UUID.randomUUID();
        when(repository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(id)).verifyComplete();
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

> **Note on `TenantContext` methods:** Read the actual `TenantContext` class to confirm `setAdminMode(boolean)`, `setTenantId(String)`, and `clear()` are the correct method names before writing tests.
>
> **Note on `@InjectMocks` and `@Value`:** `TelegramAccountService` will have `@Value("${telegram.api-id}")` and `@Value("${telegram.api-hash}")`. These will be 0 and `""` respectively in Mockito tests (defaults for injected primitives). Add a `@BeforeEach` that uses `ReflectionTestUtils.setField(service, "telegramApiId", 12345)` and `ReflectionTestUtils.setField(service, "telegramApiHash", "abc-hash")` if the `create` test needs them.

- [ ] **Step 2: Run test to confirm failure**

```bash
mvn test -pl emcip-admin-api -Dtest=TelegramAccountServiceTest -q 2>&1 | tail -5
```

- [ ] **Step 3: Implement `TelegramAccountService`**

Read the full `TelegramAccountController.java` first. Then implement:

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.entity.AccountWatchedGroup;
import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.common.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Service
@Slf4j
public class TelegramAccountService {

    private final TelegramAccountRepository repository;
    private final AccountWatchedGroupRepository watchedGroupRepository;
    private final GroupProfileRepository groupProfileRepository;
    private final R2dbcEntityTemplate r2dbcEntityTemplate;
    private final WebClient tdlibClient;

    @Value("${telegram.api-id}")
    private int telegramApiId;

    @Value("${telegram.api-hash}")
    private String telegramApiHash;

    public TelegramAccountService(
            TelegramAccountRepository repository,
            AccountWatchedGroupRepository watchedGroupRepository,
            GroupProfileRepository groupProfileRepository,
            R2dbcEntityTemplate r2dbcEntityTemplate,
            @Qualifier("tdlibWebClient") WebClient tdlibClient) {
        this.repository = repository;
        this.watchedGroupRepository = watchedGroupRepository;
        this.groupProfileRepository = groupProfileRepository;
        this.r2dbcEntityTemplate = r2dbcEntityTemplate;
        this.tdlibClient = tdlibClient;
    }

    // ── Find ──────────────────────────────────────────────────────────────────

    public Flux<TelegramAccount> findAll() {
        if (TenantContext.isAdminMode()) {
            return repository.findAll();
        }
        return repository.findAllByTenantId(TenantContext.getTenantId());
    }

    public Mono<TelegramAccount> getById(UUID id) {
        if (TenantContext.isAdminMode()) {
            return repository.findById(id).switchIfEmpty(notFound(id));
        }
        return repository
                .findByIdAndTenantId(id, TenantContext.getTenantId())
                .switchIfEmpty(notFound(id));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public Mono<TelegramAccount> create(String phoneNumber, String displayName, UUID tenantId) {
        TelegramAccount account = new TelegramAccount();
        account.setId(UUID.randomUUID());
        account.setPhoneNumber(phoneNumber);
        account.setDisplayName(displayName);
        account.setApiId(telegramApiId);
        account.setApiHash(telegramApiHash);
        account.setStatus(TelegramAccountStatus.UNCONFIGURED);
        account.setTenantId(tenantId);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        return r2dbcEntityTemplate.insert(account);
    }

    public Mono<Void> delete(UUID id) {
        return repository.deleteById(id);
    }

    // ── Auth flow ─────────────────────────────────────────────────────────────

    public Mono<TelegramAccount> getStatus(UUID id) {
        return getById(id)
                .flatMap(
                        account ->
                                tdlibClient
                                        .get()
                                        .uri("/api/auth/{id}/status", id)
                                        .retrieve()
                                        .bodyToMono(
                                                new org.springframework.core.ParameterizedTypeReference<
                                                        Map<String, Object>>() {})
                                        .flatMap(
                                                statusBody -> {
                                                    String tdStatus =
                                                            (String) statusBody.get("status");
                                                    String tdError =
                                                            (String) statusBody.get("error");
                                                    boolean changed =
                                                            !account.getStatus()
                                                                            .name()
                                                                            .equals(tdStatus)
                                                                    || (tdError != null
                                                                            && !tdError.equals(
                                                                                    account
                                                                                            .getLastError()));
                                                    if (changed && tdStatus != null) {
                                                        account.setStatus(
                                                                TelegramAccountStatus.valueOf(
                                                                        tdStatus));
                                                        account.setLastError(tdError);
                                                        account.setUpdatedAt(Instant.now());
                                                        return repository.save(account);
                                                    }
                                                    return Mono.just(account);
                                                })
                                        .onErrorResume(
                                                e -> {
                                                    log.warn(
                                                            "Could not fetch tdlib status for {}: {}",
                                                            id,
                                                            e.getMessage());
                                                    return Mono.just(account);
                                                }));
    }

    public Mono<TelegramAccount> reconnect(UUID id) {
        return getById(id)
                .flatMap(
                        account ->
                                initializeAccount(account)
                                        .then(pushWatchedGroups(id))
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            account.setStatus(
                                                                    TelegramAccountStatus
                                                                            .AWAITING_CODE);
                                                            account.setUpdatedAt(Instant.now());
                                                            return repository.save(account);
                                                        })));
    }

    public Mono<Void> submitCode(UUID id, String code) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/code", id)
                .bodyValue(Map.of("code", code))
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Mono<Void> submitPassword(UUID id, String password) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/password", id)
                .bodyValue(Map.of("password", password))
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Mono<Void> logout(UUID id) {
        return getById(id)
                .flatMap(
                        account ->
                                tdlibClient
                                        .post()
                                        .uri("/api/auth/{id}/logout", id)
                                        .retrieve()
                                        .bodyToMono(Void.class)
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            account.setStatus(
                                                                    TelegramAccountStatus
                                                                            .DISCONNECTED);
                                                            account.setUpdatedAt(Instant.now());
                                                            return repository
                                                                    .save(account)
                                                                    .then();
                                                        })));
    }

    // ── Group watching ────────────────────────────────────────────────────────

    public Mono<Void> sync() {
        return repository
                .findByStatus(TelegramAccountStatus.ACTIVE)
                .flatMap(account -> pushWatchedGroups(account.getId()))
                .then();
    }

    public Flux<GroupProfile> findWatchedGroups(UUID accountId) {
        return watchedGroupRepository
                .findByAccountId(accountId)
                .flatMap(awg -> groupProfileRepository.findById(awg.getGroupProfileId()));
    }

    public Mono<GroupProfile> watchGroup(UUID accountId, long chatId, String title) {
        Mono<GroupProfile> findOrCreate;
        if (TenantContext.isAdminMode()) {
            findOrCreate =
                    groupProfileRepository
                            .findByTelegramChatId(chatId)
                            .switchIfEmpty(createGroupProfile(chatId, title, null));
        } else {
            UUID tenantId = TenantContext.getTenantId();
            findOrCreate =
                    groupProfileRepository
                            .findByTelegramChatIdAndTenantId(chatId, tenantId)
                            .switchIfEmpty(createGroupProfile(chatId, title, tenantId));
        }
        return findOrCreate.flatMap(
                gp ->
                        watchedGroupRepository
                                .existsByAccountIdAndGroupProfileId(accountId, gp.getId())
                                .flatMap(
                                        exists -> {
                                            if (exists) return Mono.just(gp);
                                            AccountWatchedGroup awg = new AccountWatchedGroup();
                                            awg.setAccountId(accountId);
                                            awg.setGroupProfileId(gp.getId());
                                            awg.setCreatedAt(Instant.now());
                                            return r2dbcEntityTemplate
                                                    .insert(awg)
                                                    .then(pushWatchedGroups(accountId))
                                                    .thenReturn(gp);
                                        }));
    }

    public Mono<Void> unwatchGroup(UUID accountId, long chatId) {
        return groupProfileRepository
                .findByTelegramChatId(chatId)
                .flatMap(
                        gp ->
                                watchedGroupRepository
                                        .deleteByAccountIdAndGroupProfileId(
                                                accountId, gp.getId())
                                        .then(pushWatchedGroups(accountId)));
    }

    /**
     * Pushes the current watched-group list to tdlib. Public so
     * {@link io.emcip.admin.api.config.TelegramSessionResumeRunner} can call it on startup
     * without duplicating logic.
     */
    public Mono<Void> pushWatchedGroups(UUID accountId) {
        return watchedGroupRepository
                .findByAccountId(accountId)
                .flatMap(awg -> groupProfileRepository.findById(awg.getGroupProfileId()))
                .map(GroupProfile::getTelegramChatId)
                .collectList()
                .flatMap(
                        chatIds ->
                                tdlibClient
                                        .post()
                                        .uri("/internal/watched-groups/{id}", accountId)
                                        .bodyValue(Map.of("chatIds", chatIds))
                                        .retrieve()
                                        .bodyToMono(Void.class)
                                        .retryWhen(
                                                Retry.backoff(5, Duration.ofSeconds(2))
                                                        .maxBackoff(Duration.ofSeconds(30))
                                                        .doBeforeRetry(
                                                                signal ->
                                                                        log.warn(
                                                                                "[{}] Retrying"
                                                                                    + " watched-groups"
                                                                                    + " push"
                                                                                    + " (attempt"
                                                                                    + " {}): {}",
                                                                                accountId,
                                                                                signal.totalRetries()
                                                                                        + 1,
                                                                                signal.failure()
                                                                                        .getMessage())))
                                        .onErrorResume(
                                                e -> {
                                                    log.error(
                                                            "[{}] Failed to push watched groups"
                                                                    + " after retries: {}",
                                                            accountId,
                                                            e.getMessage());
                                                    return Mono.empty();
                                                }))
                .then();
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Mono<List<Map<String, Object>>> discoverChats(UUID accountId) {
        return tdlibClient
                .get()
                .uri("/api/auth/{id}/chats", accountId)
                .retrieve()
                .bodyToMono(
                        new org.springframework.core.ParameterizedTypeReference<
                                List<Map<String, Object>>>() {})
                .onErrorResume(
                        e -> {
                            log.warn("Failed to discover chats for {}: {}", accountId, e.getMessage());
                            return Mono.just(List.of());
                        });
    }

    // ── Package-scoped: used by TelegramSessionResumeRunner ──────────────────

    public Mono<Void> initializeAccount(TelegramAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phoneNumber", account.getPhoneNumber());
        payload.put("apiId", account.getApiId());
        payload.put("apiHash", account.getApiHash());
        payload.put("sessionString", account.getSessionString());

        return tdlibClient
                .post()
                .uri("/api/auth/{id}/initialize", account.getId())
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Failed to initialize account {}: {}",
                                    account.getId(),
                                    e.getMessage());
                            account.setStatus(TelegramAccountStatus.DISCONNECTED);
                            account.setLastError("Initialization failed: " + e.getMessage());
                            account.setUpdatedAt(Instant.now());
                            return repository.save(account).then();
                        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Mono<GroupProfile> createGroupProfile(long chatId, String title, UUID tenantId) {
        GroupProfile gp = new GroupProfile();
        gp.setTelegramChatId(chatId);
        gp.setName(title != null ? title : "Group " + chatId);
        gp.setTenantId(tenantId);
        gp.setCreatedAt(Instant.now());
        gp.setUpdatedAt(Instant.now());
        return groupProfileRepository.save(gp);
    }

    private <T> Mono<T> notFound(UUID id) {
        return Mono.error(
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account not found: " + id));
    }
}
```

> **Important:** Read `TelegramAccountController.java` fully before implementing. The controller has additional logic (e.g. `watchGroup` constructs a `WatchRequest` inner record with `chatId` and `title`) that must be replicated accurately. Adjust method signatures if the controller's request records have different field names.

- [ ] **Step 4: Run service tests**

```bash
mvn test -pl emcip-admin-api -Dtest=TelegramAccountServiceTest -q 2>&1 | tail -5
```
Expected: 5 tests, 0 failures

- [ ] **Step 5: Update `TelegramSessionResumeRunner` to delegate to service**

Replace the class body — remove the two private methods and inject `TelegramAccountService` instead:
```java
package io.emcip.admin.api.config;

import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.admin.api.service.TelegramAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramSessionResumeRunner {

    private final TelegramAccountRepository repository;
    private final TelegramAccountService telegramAccountService;

    @EventListener(ApplicationReadyEvent.class)
    public void resumeActiveSessions() {
        repository
                .findByStatus(TelegramAccountStatus.ACTIVE)
                .flatMap(
                        account ->
                                telegramAccountService
                                        .initializeAccount(account)
                                        .then(
                                                telegramAccountService.pushWatchedGroups(
                                                        account.getId())))
                .subscribe(
                        null, err -> log.warn("Session resume error: {}", err.getMessage()));
    }
}
```

- [ ] **Step 6: Update `TelegramAccountController` to delegate to service**

Read the full existing controller, then rewrite it as an HTTP-mapping-only controller. Keep all inner request/response records and `toSafeMap`/`toWatchedMap` private helpers in the controller (they're presentation concerns). Replace all business logic with service calls:

```java
package io.emcip.admin.api.controller;

// [Keep all existing imports, add TelegramAccountService import]
// [Remove: TelegramAccountRepository, AccountWatchedGroupRepository, GroupProfileRepository,
//          R2dbcEntityTemplate, WebClient, @Value fields]

@RestController
@RequestMapping("/api/telegram/accounts")
@RequiredArgsConstructor
@Tag(name = "Telegram Accounts", description = "Manage Telegram accounts and group watching")
public class TelegramAccountController {

    private final TelegramAccountService telegramAccountService;

    @GetMapping
    public Mono<List<Map<String, Object>>> listAccounts() {
        return telegramAccountService.findAll()
                .map(this::toSafeMap)
                .collectList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> createAccount(@RequestBody CreateAccountRequest req) {
        UUID tenantId = TenantContext.isAdminMode() ? null : TenantContext.getTenantId();
        return telegramAccountService
                .create(req.phoneNumber(), req.displayName(), tenantId)
                .map(this::toSafeMap);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteAccount(@PathVariable UUID id) {
        return telegramAccountService.delete(id);
    }

    @GetMapping("/{id}/status")
    public Mono<Map<String, Object>> getStatus(@PathVariable UUID id) {
        return telegramAccountService.getStatus(id).map(this::toSafeMap);
    }

    @PostMapping("/{id}/reconnect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> reconnect(@PathVariable UUID id) {
        return telegramAccountService.reconnect(id).map(this::toSafeMap);
    }

    @PostMapping("/{id}/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> submitCode(@PathVariable UUID id, @RequestBody CodeRequest req) {
        return telegramAccountService.submitCode(id, req.code());
    }

    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> submitPassword(@PathVariable UUID id, @RequestBody PasswordRequest req) {
        return telegramAccountService.submitPassword(id, req.password());
    }

    @PostMapping("/{id}/logout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> logout(@PathVariable UUID id) {
        return telegramAccountService.logout(id);
    }

    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> sync() {
        return telegramAccountService.sync();
    }

    @GetMapping("/{id}/chats")
    public Mono<List<Map<String, Object>>> discoverChats(@PathVariable UUID id) {
        return telegramAccountService.discoverChats(id);
    }

    @GetMapping("/{id}/watched")
    public Mono<List<Map<String, Object>>> getWatched(@PathVariable UUID id) {
        return telegramAccountService.findWatchedGroups(id)
                .map(this::toWatchedMap)
                .collectList();
    }

    @PostMapping("/{id}/watch")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> watchGroup(@PathVariable UUID id, @RequestBody WatchRequest req) {
        return telegramAccountService
                .watchGroup(id, req.chatId(), req.title())
                .map(this::toWatchedMap);
    }

    @DeleteMapping("/{id}/watch/{chatId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unwatchGroup(@PathVariable UUID id, @PathVariable long chatId) {
        return telegramAccountService.unwatchGroup(id, chatId);
    }

    // ── Presentation helpers (stay in controller — not domain logic) ──────────

    private Map<String, Object> toSafeMap(TelegramAccount account) {
        // Copy from existing controller — strips apiHash and sessionString,
        // adds sessionStringSet boolean
        // Read the existing controller for the exact implementation
        throw new UnsupportedOperationException("Copy from existing controller");
    }

    private Map<String, Object> toWatchedMap(GroupProfile profile) {
        // Copy from existing controller
        throw new UnsupportedOperationException("Copy from existing controller");
    }

    // ── Request records (keep here — they carry @Schema annotations) ──────────
    private record CreateAccountRequest(String phoneNumber, String displayName) {}
    private record CodeRequest(String code) {}
    private record PasswordRequest(String password) {}
    private record WatchRequest(long chatId, String title) {}
}
```

> **CRITICAL:** Replace the `throw new UnsupportedOperationException(...)` stubs with the actual implementations copied verbatim from the existing controller. Also copy all inner request/response record definitions that exist in the controller. Read the full existing controller and replicate them exactly.

- [ ] **Step 7: Update `TelegramAccountControllerTest`**

Read existing test, then replace with service-mocked version. The controller tests should only verify HTTP mapping:

```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.service.TelegramAccountService;
import io.emcip.common.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class TelegramAccountControllerTest {

    @Mock private TelegramAccountService telegramAccountService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        TenantContext.setAdminMode(true);
        webTestClient =
                WebTestClient.bindToController(
                                new TelegramAccountController(telegramAccountService))
                        .build();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private TelegramAccount account() {
        TelegramAccount a = new TelegramAccount();
        a.setId(UUID.randomUUID());
        a.setPhoneNumber("+49123456789");
        a.setStatus(TelegramAccountStatus.ACTIVE);
        a.setApiHash("hidden");
        return a;
    }

    @Test
    void listAccounts_sessionStringSensitiveFieldStripped() {
        TelegramAccount a = account();
        a.setSessionString("secret-session");
        when(telegramAccountService.findAll()).thenReturn(Flux.just(a));

        webTestClient
                .get()
                .uri("/api/telegram/accounts")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].sessionString")
                .doesNotExist()
                .jsonPath("$[0].sessionStringSet")
                .isEqualTo(true);
    }

    @Test
    void createAccount_returns201() {
        when(telegramAccountService.create(anyString(), any(), any()))
                .thenReturn(Mono.just(account()));

        webTestClient
                .post()
                .uri("/api/telegram/accounts")
                .bodyValue(Map.of("phoneNumber", "+49123", "displayName", "Test"))
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void deleteAccount_returns204() {
        when(telegramAccountService.delete(any())).thenReturn(Mono.empty());

        webTestClient
                .delete()
                .uri("/api/telegram/accounts/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void getStatus_returns200() {
        when(telegramAccountService.getStatus(any())).thenReturn(Mono.just(account()));

        webTestClient
                .get()
                .uri("/api/telegram/accounts/" + UUID.randomUUID() + "/status")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void logout_returns202() {
        when(telegramAccountService.logout(any())).thenReturn(Mono.empty());

        webTestClient
                .post()
                .uri("/api/telegram/accounts/" + UUID.randomUUID() + "/logout")
                .exchange()
                .expectStatus()
                .isAccepted();
    }

    @Test
    void getWatched_returns200() {
        when(telegramAccountService.findWatchedGroups(any())).thenReturn(Flux.empty());

        webTestClient
                .get()
                .uri("/api/telegram/accounts/" + UUID.randomUUID() + "/watched")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void watchGroup_returns201() {
        GroupProfile gp = new GroupProfile();
        gp.setTelegramChatId(100L);
        when(telegramAccountService.watchGroup(any(), anyLong(), any()))
                .thenReturn(Mono.just(gp));

        webTestClient
                .post()
                .uri("/api/telegram/accounts/" + UUID.randomUUID() + "/watch")
                .bodyValue(Map.of("chatId", 100L, "title", "Group"))
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void unwatchGroup_returns204() {
        when(telegramAccountService.unwatchGroup(any(), anyLong())).thenReturn(Mono.empty());

        webTestClient
                .delete()
                .uri("/api/telegram/accounts/" + UUID.randomUUID() + "/watch/100")
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void discoverChats_returnsEmptyOnError() {
        when(telegramAccountService.discoverChats(any()))
                .thenReturn(Mono.just(List.of()));

        webTestClient
                .get()
                .uri("/api/telegram/accounts/" + UUID.randomUUID() + "/chats")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isArray();
    }
}
```

- [ ] **Step 8: Run all admin-api tests**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -15
```
Expected: BUILD SUCCESS, 0 failures. All previously passing tests still pass.

- [ ] **Step 9: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "refactor(admin-api): extract TelegramAccountService; eliminate pushWatchedGroups duplicate in TelegramSessionResumeRunner"
```

---

## Self-Review

**Spec coverage check:**

| Item | Task |
|------|------|
| Extract auth + token gen | Task 1 ✅ |
| Move TokenResponse to dto | Task 1 ✅ |
| Expose EXPIRY_MS publicly | Task 1 ✅ |
| Extract tenant CRUD | Task 2 ✅ |
| Extract flag routing + validation | Task 3 ✅ |
| Extract multi-tenant group CRUD with merge | Task 4 ✅ |
| Extract Kafka event building + inject ObjectMapper | Task 5 ✅ |
| Extract all Telegram management | Task 6 ✅ |
| Eliminate pushWatchedGroups duplicate | Task 6 ✅ |
| All controller tests updated to mock service | Tasks 1–6 ✅ |
| All service tests created (TDD) | Tasks 1–6 ✅ |

**Out of scope (separate SCs):**
- SC2: Input validation (`@Valid` annotations) — not in this plan
- SC3: Replace ThreadLocal with Reactor Context — services replicate existing TenantContext usage as-is
- Thin proxy controllers (AIProxy, ModerationRule, PolicyRule, Audit) — already delegate to clients, no business logic to extract

**Thin proxy controllers left unchanged:**
- `AIProxyController` — pure passthrough to `orchestratorWebClient`
- `ModerationRuleController` — delegates entirely to `ModerationServiceClient`
- `PolicyRuleController` — delegates entirely to `PolicyEngineClient`
- `AuditController` — delegates entirely to `AuditServiceClient`

These are already correctly structured (controller = HTTP mapping, client = downstream call). No service layer needed.
