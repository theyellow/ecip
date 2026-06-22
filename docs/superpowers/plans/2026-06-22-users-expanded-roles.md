# Users: Expanded Roles + lastLogin/createdAt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add MODERATOR, ANALYST, and VIEWER roles, and expose `lastLogin` (already in DB) in the API response and record it on successful authentication.

**Architecture:** `Role` enum gains three entries; `RolePermissions` maps each to a permission set; `AuthService.authenticate()` saves `lastLogin` before issuing the token; `UserResponse` DTO gains a `lastLogin` field; `UserManagementService.validateRequest()` is tightened so all non-ADMIN roles require `tenantId`; `Users.jsx` adds the three roles to its constants, shows the `lastLogin` column, and shows the tenant selector for all non-ADMIN roles.

**Tech Stack:** Java 21, Spring Boot 4, R2DBC (admin-api is reactive), Lombok, JUnit 5 + Mockito + AssertJ + StepVerifier; React 18, CSS Modules.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/security/Role.java` | Add MODERATOR, ANALYST, VIEWER |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/security/RolePermissions.java` | Add three permission sets, extend switch |
| Modify | `emcip-admin-api/src/test/java/io/emcip/admin/api/security/RolePermissionsTest.java` | Tests for new role permission sets |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/UserResponse.java` | Add `lastLogin` field |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/service/UserManagementService.java` | Map `lastLogin` in `toResponse()`; extend `validateRequest()` for new roles |
| Modify | `emcip-admin-api/src/test/java/io/emcip/admin/api/service/UserManagementServiceTest.java` | Tests for new role validation |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java` | Save `lastLogin` before issuing token |
| Modify | `emcip-admin-api/src/test/java/io/emcip/admin/api/service/AuthServiceTest.java` | Update existing test + add lastLogin verification |
| Modify | `emcip-admin-ui/src/main/frontend/src/pages/Users/Users.jsx` | New ROLES/ROLE_VARIANT, lastLogin column, tenant selector for all non-ADMIN roles |

No Liquibase migration needed — `last_login` and `created_at` already exist in `admin_users` and are already mapped in `AdminUser.java`. No new DB columns.

---

## Task 1: Role enum + RolePermissions + tests (TDD)

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/Role.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/RolePermissions.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/security/RolePermissionsTest.java`

**Context — current state of `RolePermissions.java`:**
```java
public final class RolePermissions {
    private static final Set<Permission> ADMIN_PERMISSIONS = EnumSet.allOf(Permission.class);
    private static final Set<Permission> TENANT_ADMIN_PERMISSIONS = EnumSet.of(
            Permission.GROUPS_READ, Permission.GROUPS_WRITE,
            Permission.POLICY_RULES_READ, Permission.POLICY_RULES_WRITE,
            Permission.MODERATION_RULES_READ, Permission.MODERATION_RULES_WRITE,
            Permission.AUDIT_READ, Permission.TELEGRAM_READ, Permission.TELEGRAM_WRITE,
            Permission.SIMULATE_WRITE, Permission.COSTS_READ,
            Permission.RESOLUTION_REVIEW_READ, Permission.RESOLUTION_REVIEW_WRITE,
            Permission.KNOWLEDGE_READ, Permission.KNOWLEDGE_WRITE,
            Permission.INTEGRATIONS_TENANT_MANAGE);
    private RolePermissions() {}
    public static Set<Permission> permissionsFor(Role role) {
        return switch (role) {
            case ADMIN -> ADMIN_PERMISSIONS;
            case TENANT_ADMIN -> TENANT_ADMIN_PERMISSIONS;
        };
    }
}
```

Permission enum values (all 22): `GROUPS_READ, GROUPS_WRITE, POLICY_RULES_READ, POLICY_RULES_WRITE, MODERATION_RULES_READ, MODERATION_RULES_WRITE, AUDIT_READ, TELEGRAM_READ, TELEGRAM_WRITE, SIMULATE_WRITE, AI_CONFIG_READ, AI_CONFIG_WRITE, TENANTS_READ, TENANTS_WRITE, USERS_READ, USERS_WRITE, COSTS_READ, RESOLUTION_REVIEW_READ, RESOLUTION_REVIEW_WRITE, KNOWLEDGE_READ, KNOWLEDGE_WRITE, INTEGRATIONS_GLOBAL_MANAGE, INTEGRATIONS_TENANT_MANAGE`

- [ ] **Step 1: Write the failing tests in `RolePermissionsTest.java`**

Add these tests after the existing `tenantAdmin_lacksIntegrationsGlobalManage` test:

```java
@Test
void moderator_hasExpectedPermissions() {
    Set<Permission> perms = RolePermissions.permissionsFor(Role.MODERATOR);
    assertThat(perms)
            .contains(
                    Permission.GROUPS_READ,
                    Permission.GROUPS_WRITE,
                    Permission.POLICY_RULES_READ,
                    Permission.POLICY_RULES_WRITE,
                    Permission.MODERATION_RULES_READ,
                    Permission.MODERATION_RULES_WRITE,
                    Permission.AUDIT_READ,
                    Permission.TELEGRAM_READ,
                    Permission.SIMULATE_WRITE,
                    Permission.RESOLUTION_REVIEW_READ,
                    Permission.RESOLUTION_REVIEW_WRITE,
                    Permission.KNOWLEDGE_READ);
}

@Test
void moderator_lacksElevatedPermissions() {
    Set<Permission> perms = RolePermissions.permissionsFor(Role.MODERATOR);
    assertThat(perms)
            .doesNotContain(
                    Permission.AI_CONFIG_READ,
                    Permission.AI_CONFIG_WRITE,
                    Permission.TENANTS_READ,
                    Permission.TENANTS_WRITE,
                    Permission.USERS_READ,
                    Permission.USERS_WRITE,
                    Permission.TELEGRAM_WRITE,
                    Permission.KNOWLEDGE_WRITE,
                    Permission.INTEGRATIONS_GLOBAL_MANAGE,
                    Permission.INTEGRATIONS_TENANT_MANAGE);
}

@Test
void analyst_hasReadOnlyPermissions() {
    Set<Permission> perms = RolePermissions.permissionsFor(Role.ANALYST);
    assertThat(perms)
            .contains(
                    Permission.GROUPS_READ,
                    Permission.POLICY_RULES_READ,
                    Permission.MODERATION_RULES_READ,
                    Permission.AUDIT_READ,
                    Permission.TELEGRAM_READ,
                    Permission.COSTS_READ,
                    Permission.RESOLUTION_REVIEW_READ,
                    Permission.KNOWLEDGE_READ);
}

@Test
void analyst_lacksWritePermissions() {
    Set<Permission> perms = RolePermissions.permissionsFor(Role.ANALYST);
    assertThat(perms)
            .doesNotContain(
                    Permission.GROUPS_WRITE,
                    Permission.POLICY_RULES_WRITE,
                    Permission.MODERATION_RULES_WRITE,
                    Permission.SIMULATE_WRITE,
                    Permission.KNOWLEDGE_WRITE,
                    Permission.USERS_READ,
                    Permission.USERS_WRITE,
                    Permission.AI_CONFIG_READ);
}

@Test
void viewer_hasMinimalPermissions() {
    Set<Permission> perms = RolePermissions.permissionsFor(Role.VIEWER);
    assertThat(perms)
            .containsExactlyInAnyOrder(
                    Permission.GROUPS_READ,
                    Permission.AUDIT_READ,
                    Permission.TELEGRAM_READ);
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -Dtest=RolePermissionsTest -q 2>&1 | tail -10
```

Expected: compilation error — `Role.MODERATOR`, `Role.ANALYST`, `Role.VIEWER` do not exist yet.

- [ ] **Step 3: Extend `Role.java`**

Replace the file:

```java
package io.emcip.admin.api.security;

public enum Role {
    ADMIN,
    TENANT_ADMIN,
    MODERATOR,
    ANALYST,
    VIEWER
}
```

- [ ] **Step 4: Extend `RolePermissions.java`**

Replace the file:

```java
package io.emcip.admin.api.security;

import java.util.EnumSet;
import java.util.Set;

public final class RolePermissions {

    private static final Set<Permission> ADMIN_PERMISSIONS = EnumSet.allOf(Permission.class);

    private static final Set<Permission> TENANT_ADMIN_PERMISSIONS =
            EnumSet.of(
                    Permission.GROUPS_READ,
                    Permission.GROUPS_WRITE,
                    Permission.POLICY_RULES_READ,
                    Permission.POLICY_RULES_WRITE,
                    Permission.MODERATION_RULES_READ,
                    Permission.MODERATION_RULES_WRITE,
                    Permission.AUDIT_READ,
                    Permission.TELEGRAM_READ,
                    Permission.TELEGRAM_WRITE,
                    Permission.SIMULATE_WRITE,
                    Permission.COSTS_READ,
                    Permission.RESOLUTION_REVIEW_READ,
                    Permission.RESOLUTION_REVIEW_WRITE,
                    Permission.KNOWLEDGE_READ,
                    Permission.KNOWLEDGE_WRITE,
                    Permission.INTEGRATIONS_TENANT_MANAGE);

    private static final Set<Permission> MODERATOR_PERMISSIONS =
            EnumSet.of(
                    Permission.GROUPS_READ,
                    Permission.GROUPS_WRITE,
                    Permission.POLICY_RULES_READ,
                    Permission.POLICY_RULES_WRITE,
                    Permission.MODERATION_RULES_READ,
                    Permission.MODERATION_RULES_WRITE,
                    Permission.AUDIT_READ,
                    Permission.TELEGRAM_READ,
                    Permission.SIMULATE_WRITE,
                    Permission.RESOLUTION_REVIEW_READ,
                    Permission.RESOLUTION_REVIEW_WRITE,
                    Permission.KNOWLEDGE_READ);

    private static final Set<Permission> ANALYST_PERMISSIONS =
            EnumSet.of(
                    Permission.GROUPS_READ,
                    Permission.POLICY_RULES_READ,
                    Permission.MODERATION_RULES_READ,
                    Permission.AUDIT_READ,
                    Permission.TELEGRAM_READ,
                    Permission.COSTS_READ,
                    Permission.RESOLUTION_REVIEW_READ,
                    Permission.KNOWLEDGE_READ);

    private static final Set<Permission> VIEWER_PERMISSIONS =
            EnumSet.of(
                    Permission.GROUPS_READ,
                    Permission.AUDIT_READ,
                    Permission.TELEGRAM_READ);

    private RolePermissions() {}

    public static Set<Permission> permissionsFor(Role role) {
        return switch (role) {
            case ADMIN -> ADMIN_PERMISSIONS;
            case TENANT_ADMIN -> TENANT_ADMIN_PERMISSIONS;
            case MODERATOR -> MODERATOR_PERMISSIONS;
            case ANALYST -> ANALYST_PERMISSIONS;
            case VIEWER -> VIEWER_PERMISSIONS;
        };
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -Dtest=RolePermissionsTest -q 2>&1 | tail -10
```

Expected: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 6: Run spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api spotless:apply -q 2>&1 | tail -3
git add \
  emcip-admin-api/src/main/java/io/emcip/admin/api/security/Role.java \
  emcip-admin-api/src/main/java/io/emcip/admin/api/security/RolePermissions.java \
  emcip-admin-api/src/test/java/io/emcip/admin/api/security/RolePermissionsTest.java
git commit -m "feat(41c): add MODERATOR, ANALYST, VIEWER roles with permission sets"
```

---

## Task 2: UserResponse + UserManagementService + tests

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/UserResponse.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/UserManagementService.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/UserManagementServiceTest.java`

**Context — current state of `UserResponse.java`:**
```java
@Value @Builder
@JsonDeserialize(builder = UserResponse.UserResponseBuilder.class)
public class UserResponse {
    Long id;
    String username;
    String email;
    Role role;
    UUID tenantId;
    String tenantName;
    boolean enabled;
    Instant createdAt;
    @JsonPOJOBuilder(withPrefix = "")
    public static final class UserResponseBuilder {}
}
```

**Context — current `validateRequest()` (lines 131–155 of UserManagementService):**
```java
private Mono<Void> validateRequest(UserRequest req) {
    if (req.getRole() == Role.TENANT_ADMIN && req.getTenantId() == null) {
        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "tenantId is required for TENANT_ADMIN role"));
    }
    if (req.getRole() == Role.ADMIN && req.getTenantId() != null) {
        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "ADMIN role must not have a tenantId"));
    }
    if (req.getRole() == Role.TENANT_ADMIN && req.getTenantId() != null) {
        return tenantRepository.existsById(req.getTenantId())
                .flatMap(exists -> exists ? Mono.empty()
                        : Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Tenant not found")));
    }
    return Mono.empty();
}
```

**Context — current `toResponse()` (lines 157–178):**
```java
private Mono<UserResponse> toResponse(AdminUser user) {
    Mono<String> tenantNameMono = user.getTenantId() != null
            ? tenantRepository.findById(user.getTenantId()).map(t -> t.getName()).defaultIfEmpty("")
            : Mono.just("");
    return tenantNameMono.map(tenantName ->
            UserResponse.builder()
                    .id(user.getId()).username(user.getUsername()).email(user.getEmail())
                    .role(user.getRole()).tenantId(user.getTenantId())
                    .tenantName(tenantName.isEmpty() ? null : tenantName)
                    .enabled(user.isEnabled()).createdAt(user.getCreatedAt())
                    .build());
}
```

- [ ] **Step 1: Write failing tests in `UserManagementServiceTest.java`**

Add these tests after the existing `updateUser_otherUser_succeeds` test. Add `import io.emcip.admin.api.security.Role;` if not already present (it is).

```java
@Test
void createUser_moderator_requiresTenantId() {
    UserRequest req = new UserRequest();
    req.setUsername("mod");
    req.setEmail("mod@example.com");
    req.setPassword("secret");
    req.setRole(Role.MODERATOR);
    req.setTenantId(null);

    StepVerifier.create(userManagementService.create(req))
            .expectErrorMatches(
                    e ->
                            e.getMessage() != null
                                    && e.getMessage().contains("tenantId is required"))
            .verify();
}

@Test
void createUser_analyst_requiresTenantId() {
    UserRequest req = new UserRequest();
    req.setUsername("analyst");
    req.setEmail("analyst@example.com");
    req.setPassword("secret");
    req.setRole(Role.ANALYST);
    req.setTenantId(null);

    StepVerifier.create(userManagementService.create(req))
            .expectErrorMatches(
                    e ->
                            e.getMessage() != null
                                    && e.getMessage().contains("tenantId is required"))
            .verify();
}

@Test
void createUser_viewer_requiresTenantId() {
    UserRequest req = new UserRequest();
    req.setUsername("viewer");
    req.setEmail("viewer@example.com");
    req.setPassword("secret");
    req.setRole(Role.VIEWER);
    req.setTenantId(null);

    StepVerifier.create(userManagementService.create(req))
            .expectErrorMatches(
                    e ->
                            e.getMessage() != null
                                    && e.getMessage().contains("tenantId is required"))
            .verify();
}

@Test
void createUser_moderator_validRequest_savesUser() {
    UserRequest req = new UserRequest();
    req.setUsername("mod");
    req.setEmail("mod@example.com");
    req.setPassword("secret");
    req.setRole(Role.MODERATOR);
    req.setTenantId(TENANT_ID);

    AdminUser moderator =
            AdminUser.builder()
                    .id(3L)
                    .username("mod")
                    .email("mod@example.com")
                    .passwordHash("$2a$encoded")
                    .role(Role.MODERATOR)
                    .tenantId(TENANT_ID)
                    .enabled(true)
                    .createdAt(Instant.now())
                    .build();

    when(tenantRepository.existsById(TENANT_ID)).thenReturn(Mono.just(true));
    when(passwordEncoder.encode("secret")).thenReturn("$2a$encoded");
    when(userRepository.save(any())).thenReturn(Mono.just(moderator));
    when(tenantRepository.findById(TENANT_ID)).thenReturn(Mono.just(tenant("Acme Corp")));

    StepVerifier.create(userManagementService.create(req))
            .assertNext(
                    resp -> {
                        assertThat(resp.getUsername()).isEqualTo("mod");
                        assertThat(resp.getRole()).isEqualTo(Role.MODERATOR);
                        assertThat(resp.getTenantId()).isEqualTo(TENANT_ID);
                        assertThat(resp.getTenantName()).isEqualTo("Acme Corp");
                    })
            .verifyComplete();
}

@Test
void toResponse_includesLastLogin() {
    Instant loginTime = Instant.parse("2026-06-22T10:00:00Z");
    AdminUser user =
            AdminUser.builder()
                    .id(1L)
                    .username("admin")
                    .email("admin@example.com")
                    .passwordHash("$2a$hash")
                    .role(Role.ADMIN)
                    .enabled(true)
                    .createdAt(Instant.now())
                    .lastLogin(loginTime)
                    .build();

    when(userRepository.findAll()).thenReturn(reactor.core.publisher.Flux.just(user));

    StepVerifier.create(userManagementService.findAll())
            .assertNext(resp -> assertThat(resp.getLastLogin()).isEqualTo(loginTime))
            .verifyComplete();
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -Dtest=UserManagementServiceTest -q 2>&1 | tail -15
```

Expected: compilation error or failures — `createUser_moderator_requiresTenantId` passes (no change yet), `toResponse_includesLastLogin` fails because `UserResponse` has no `getLastLogin()`.

- [ ] **Step 3: Add `lastLogin` to `UserResponse.java`**

Replace the file:

```java
package io.emcip.admin.api.dto;

import io.emcip.admin.api.security.Role;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Value
@Builder
@JsonDeserialize(builder = UserResponse.UserResponseBuilder.class)
public class UserResponse {
    Long id;
    String username;
    String email;
    Role role;
    UUID tenantId;
    String tenantName;
    boolean enabled;
    Instant createdAt;
    Instant lastLogin;

    @JsonPOJOBuilder(withPrefix = "")
    public static final class UserResponseBuilder {}
}
```

- [ ] **Step 4: Update `toResponse()` and `validateRequest()` in `UserManagementService.java`**

In `UserManagementService.java`, replace `toResponse()` at lines 157–178:

```java
private Mono<UserResponse> toResponse(AdminUser user) {
    Mono<String> tenantNameMono =
            user.getTenantId() != null
                    ? tenantRepository
                            .findById(user.getTenantId())
                            .map(t -> t.getName())
                            .defaultIfEmpty("")
                    : Mono.just("");

    return tenantNameMono.map(
            tenantName ->
                    UserResponse.builder()
                            .id(user.getId())
                            .username(user.getUsername())
                            .email(user.getEmail())
                            .role(user.getRole())
                            .tenantId(user.getTenantId())
                            .tenantName(tenantName.isEmpty() ? null : tenantName)
                            .enabled(user.isEnabled())
                            .createdAt(user.getCreatedAt())
                            .lastLogin(user.getLastLogin())
                            .build());
}
```

Replace `validateRequest()` at lines 131–155:

```java
private Mono<Void> validateRequest(UserRequest req) {
    if (req.getRole() != Role.ADMIN && req.getTenantId() == null) {
        return Mono.error(
                new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "tenantId is required for " + req.getRole() + " role"));
    }
    if (req.getRole() == Role.ADMIN && req.getTenantId() != null) {
        return Mono.error(
                new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "ADMIN role must not have a tenantId"));
    }
    if (req.getRole() != Role.ADMIN && req.getTenantId() != null) {
        return tenantRepository
                .existsById(req.getTenantId())
                .flatMap(
                        exists ->
                                exists
                                        ? Mono.empty()
                                        : Mono.error(
                                                new ResponseStatusException(
                                                        HttpStatus.BAD_REQUEST,
                                                        "Tenant not found")));
    }
    return Mono.empty();
}
```

- [ ] **Step 5: Run the tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -Dtest=UserManagementServiceTest -q 2>&1 | tail -10
```

Expected: `Tests run: 10, Failures: 0, Errors: 0` (5 original + 5 new).

- [ ] **Step 6: Run spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api spotless:apply -q 2>&1 | tail -3
git add \
  emcip-admin-api/src/main/java/io/emcip/admin/api/dto/UserResponse.java \
  emcip-admin-api/src/main/java/io/emcip/admin/api/service/UserManagementService.java \
  emcip-admin-api/src/test/java/io/emcip/admin/api/service/UserManagementServiceTest.java
git commit -m "feat(41c): expose lastLogin in UserResponse; validate tenantId for all non-ADMIN roles"
```

---

## Task 3: AuthService — record lastLogin on successful login

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/AuthServiceTest.java`

**Context — current `authenticate()` method (lines 27–67):**
```java
public Mono<TokenResponse> authenticate(String username, String password) {
    return userRepository
            .findByUsername(username)
            .filter(user -> user.isEnabled()
                    && passwordEncoder.matches(password, user.getPasswordHash()))
            .switchIfEmpty(Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Invalid credentials")))
            .flatMap(user ->
                    resolveTenantName(user.getTenantId())
                            .flatMap(tenantName ->
                                    refreshTokenService.issue(user.getId())
                                            .map(rawRefresh -> new TokenResponse(
                                                    jwtService.generateToken(
                                                            user.getUsername(),
                                                            user.getRole().name(),
                                                            user.getTenantId(),
                                                            tenantName.isEmpty() ? null : tenantName),
                                                    Instant.now().plusMillis(JwtService.EXPIRY_MS),
                                                    rawRefresh))));
}
```

The change: after `switchIfEmpty`, add a `flatMap` that sets `user.setLastLogin(Instant.now())` and calls `userRepository.save(user)`, then continues into the existing `resolveTenantName` chain. This is the standard reactive pattern — side effect before proceeding.

- [ ] **Step 1: Update `authenticate_validCredentials_returnsTokenWithRefresh` test to expect `userRepository.save()`**

In `AuthServiceTest.java`, add `import static org.mockito.ArgumentMatchers.any;` if not present. Replace the existing `authenticate_validCredentials_returnsTokenWithRefresh` test:

```java
@Test
void authenticate_validCredentials_returnsTokenAndRecordsLastLogin() {
    when(userRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
    when(passwordEncoder.matches("secret", "$2a$hash")).thenReturn(true);
    when(userRepository.save(any())).thenReturn(Mono.just(enabledUser()));
    when(jwtService.generateToken("admin", "ADMIN", null, null)).thenReturn("jwt-abc");
    when(refreshTokenService.issue(1L)).thenReturn(Mono.just("refresh-xyz"));

    StepVerifier.create(authService.authenticate("admin", "secret"))
            .assertNext(
                    resp -> {
                        assertThat(resp.token()).isEqualTo("jwt-abc");
                        assertThat(resp.refreshToken()).isEqualTo("refresh-xyz");
                        assertThat(resp.expiresAt()).isNotNull();
                    })
            .verifyComplete();
}

@Test
void authenticate_validCredentials_savesLastLogin() {
    when(userRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
    when(passwordEncoder.matches("secret", "$2a$hash")).thenReturn(true);
    when(userRepository.save(any())).thenReturn(Mono.just(enabledUser()));
    when(jwtService.generateToken("admin", "ADMIN", null, null)).thenReturn("jwt-abc");
    when(refreshTokenService.issue(1L)).thenReturn(Mono.just("refresh-xyz"));

    Instant before = Instant.now();
    StepVerifier.create(authService.authenticate("admin", "secret"))
            .assertNext(resp -> assertThat(resp.token()).isNotNull())
            .verifyComplete();

    org.mockito.ArgumentCaptor<AdminUser> captor =
            org.mockito.ArgumentCaptor.forClass(AdminUser.class);
    org.mockito.Mockito.verify(userRepository).save(captor.capture());
    assertThat(captor.getValue().getLastLogin()).isNotNull();
    assertThat(captor.getValue().getLastLogin()).isAfterOrEqualTo(before);
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -Dtest=AuthServiceTest -q 2>&1 | tail -10
```

Expected: `authenticate_validCredentials_savesLastLogin` FAILS (no save call yet); `authenticate_validCredentials_returnsTokenAndRecordsLastLogin` also FAILS (save not stubbed yet).

- [ ] **Step 3: Update `AuthService.authenticate()` to record lastLogin**

In `AuthService.java`, replace the `authenticate()` method (lines 27–67):

```java
public Mono<TokenResponse> authenticate(String username, String password) {
    return userRepository
            .findByUsername(username)
            .filter(
                    user ->
                            user.isEnabled()
                                    && passwordEncoder.matches(
                                            password, user.getPasswordHash()))
            .switchIfEmpty(
                    Mono.error(
                            new ResponseStatusException(
                                    HttpStatus.UNAUTHORIZED, "Invalid credentials")))
            .flatMap(
                    user -> {
                        user.setLastLogin(Instant.now());
                        return userRepository.save(user);
                    })
            .flatMap(
                    user ->
                            resolveTenantName(user.getTenantId())
                                    .flatMap(
                                            tenantName ->
                                                    refreshTokenService
                                                            .issue(user.getId())
                                                            .map(
                                                                    rawRefresh ->
                                                                            new TokenResponse(
                                                                                    jwtService
                                                                                            .generateToken(
                                                                                                    user
                                                                                                            .getUsername(),
                                                                                                    user.getRole()
                                                                                                            .name(),
                                                                                                    user
                                                                                                            .getTenantId(),
                                                                                                    tenantName
                                                                                                                    .isEmpty()
                                                                                                            ? null
                                                                                                            : tenantName),
                                                                                    Instant
                                                                                            .now()
                                                                                            .plusMillis(
                                                                                                    JwtService
                                                                                                            .EXPIRY_MS),
                                                                                    rawRefresh))));
}
```

- [ ] **Step 4: Run AuthServiceTest**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -Dtest=AuthServiceTest -q 2>&1 | tail -10
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Run all admin-api tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 6: Run spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api spotless:apply -q 2>&1 | tail -3
git add \
  emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java \
  emcip-admin-api/src/test/java/io/emcip/admin/api/service/AuthServiceTest.java
git commit -m "feat(41c): record lastLogin on successful authentication"
```

---

## Task 4: Frontend — Users.jsx

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Users/Users.jsx`

**Context — key parts of current `Users.jsx`:**

```jsx
// Line 11–12:
const ROLES = ['ADMIN', 'TENANT_ADMIN']
const ROLE_VARIANT = { ADMIN: 'red', TENANT_ADMIN: 'yellow' }

// Lines 78–94 (columns):
const columns = [
  { key: 'username', label: 'Username', mono: true, width: 140 },
  { key: 'email', label: 'Email', mono: true },
  { key: 'role', label: 'Role', width: 110, render: v => <Badge variant={ROLE_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'tenantName', label: 'Tenant', render: v => v || '\u2014' },
  { key: 'enabled', label: 'Enabled', width: 80, render: v => <Badge variant={v ? 'green' : 'gray'}>{v ? 'ON' : 'OFF'}</Badge> },
  { key: 'id', label: '', width: 80,
    render: (_, row) => (<Button variant="secondary" onClick={e => { e.stopPropagation(); openPasswordReset(row) }}>Password</Button>) },
]

// Line 154 (modal, tenant selector condition):
{form.role === 'TENANT_ADMIN' && (
  <div className={styles.field}>
    <label htmlFor="user-tenant">Tenant</label>
    <select ...>{tenants.map(...)}</select>
  </div>
)}
```

Three changes needed:
1. `ROLES` and `ROLE_VARIANT` — add three new roles
2. `columns` — add `lastLogin` column before the password button column
3. Modal tenant selector — change condition from `form.role === 'TENANT_ADMIN'` to `form.role !== 'ADMIN'`

- [ ] **Step 1: Update `ROLES` and `ROLE_VARIANT`**

Replace lines 11–12:

```jsx
const ROLES = ['ADMIN', 'TENANT_ADMIN', 'MODERATOR', 'ANALYST', 'VIEWER']
const ROLE_VARIANT = {
  ADMIN: 'red',
  TENANT_ADMIN: 'yellow',
  MODERATOR: 'blue',
  ANALYST: 'gray',
  VIEWER: 'gray',
}
```

- [ ] **Step 2: Add `lastLogin` column to `columns`**

The `columns` array is inside the component (because it references `openPasswordReset`). Add the lastLogin column before the password-button column:

Replace the `columns` definition (lines 78–94):

```jsx
const columns = [
  { key: 'username', label: 'Username', mono: true, width: 140 },
  { key: 'email', label: 'Email', mono: true },
  {
    key: 'role',
    label: 'Role',
    width: 120,
    render: (v) => <Badge variant={ROLE_VARIANT[v] ?? 'gray'}>{v}</Badge>,
  },
  { key: 'tenantName', label: 'Tenant', render: (v) => v || '\u2014' },
  {
    key: 'enabled',
    label: 'Enabled',
    width: 80,
    render: (v) => <Badge variant={v ? 'green' : 'gray'}>{v ? 'ON' : 'OFF'}</Badge>,
  },
  {
    key: 'lastLogin',
    label: 'Last Login',
    width: 160,
    mono: true,
    render: (v) => (v ? new Date(v).toLocaleString() : '\u2014'),
  },
  {
    key: 'id',
    label: '',
    width: 80,
    render: (_, row) => (
      <Button
        variant="secondary"
        onClick={(e) => {
          e.stopPropagation()
          openPasswordReset(row)
        }}
      >
        Password
      </Button>
    ),
  },
]
```

- [ ] **Step 3: Update modal tenant selector condition**

Replace line 154:
```jsx
{form.role === 'TENANT_ADMIN' && (
```
With:
```jsx
{form.role !== 'ADMIN' && (
```

- [ ] **Step 4: Build to verify**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-ui package -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Run spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-ui spotless:apply -q 2>&1 | tail -3
git add emcip-admin-ui/src/main/frontend/src/pages/Users/Users.jsx
git commit -m "feat(41c): add MODERATOR/ANALYST/VIEWER to Users page; show lastLogin; tenant selector for all non-ADMIN roles"
```

---

## Task 5: Full build + verify + backlog update

**Files:**
- No code changes
- Modify: `docs/superpowers/BACKLOG.md`

- [ ] **Step 1: Full clean build**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api,emcip-admin-ui clean verify -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, all tests pass.

If it fails, run without `-q`:
```bash
mvn -pl emcip-admin-api clean verify 2>&1 | grep -E "FAILED|ERROR|Tests run" | head -20
```

- [ ] **Step 2: Update `BACKLOG.md`**

Read `docs/superpowers/BACKLOG.md`.

In `§2 Open — Feature Work`, remove the `41c` row.

In `§5 Completed`, add before the `27B` row:
```
| 41c | Users: expanded roles (MODERATOR, ANALYST, VIEWER) + lastLogin column + tenant selector for all non-ADMIN roles | ✅ [PR] — 2026-06-22. Plan: `plans/2026-06-22-users-expanded-roles.md` |
```

Update the header date to `2026-06-22`.

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
git add docs/superpowers/BACKLOG.md
git commit -m "docs(41c): mark 41c complete in backlog"
```

---

## Self-Review

### Spec Coverage

| Requirement | Task |
|---|---|
| Add MODERATOR role | Task 1 (Role enum + RolePermissions) |
| Add ANALYST role | Task 1 |
| Add VIEWER role | Task 1 |
| Permission sets for new roles | Task 1 (MODERATOR: 12 perms, ANALYST: 8 read-only perms, VIEWER: 3 minimal perms) |
| `lastLogin` column (already in DB/entity) — expose in API | Task 2 (UserResponse.lastLogin, toResponse()) |
| Record `lastLogin` on successful auth | Task 3 (AuthService.authenticate()) |
| Frontend: new roles in Users page | Task 4 (ROLES, ROLE_VARIANT) |
| Frontend: lastLogin column in table | Task 4 (columns array) |
| Frontend: tenant selector for all non-ADMIN roles | Task 4 (modal condition) |
| Tests for new role validation (tenantId required) | Task 2 (UserManagementServiceTest) |
| Tests for permission sets | Task 1 (RolePermissionsTest) |
| Test that lastLogin is saved on auth | Task 3 (AuthServiceTest) |

### Placeholder Scan

No TBD or TODO in any step. All code blocks are complete and compilable. Test assertions are concrete. Commands have expected output.

### Type Consistency

- `Role.MODERATOR` — defined Task 1, used in Task 2 (validateRequest test), Task 4 (ROLE_VARIANT) ✅
- `UserResponse.getLastLogin()` — added Task 2, asserted in `toResponse_includesLastLogin` Task 2 ✅
- `AdminUser.setLastLogin()` / `getLastLogin()` — already exists (Lombok `@Setter`/`@Getter`) ✅
- `userRepository.save(any())` stub — added to `authenticate_validCredentials_*` in Task 3, matches new `AuthService.authenticate()` which calls `userRepository.save(user)` ✅
- `ROLES` array in Task 4 uses exact string values matching `Role` enum names ✅
- Tenant selector condition `form.role !== 'ADMIN'` covers TENANT_ADMIN, MODERATOR, ANALYST, VIEWER — consistent with `validateRequest()` change in Task 2 ✅
