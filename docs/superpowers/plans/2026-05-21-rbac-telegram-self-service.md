# RBAC + Telegram Self-Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a `TENANT_ADMIN` role with a fixed permission matrix, embed tenant ID in JWT (S10 fix), add user management API + UI, and add a global tenant switcher to the admin sidebar.

**Architecture:** Backend adds `Role`/`Permission` enums + `RolePermissions` matrix; `JwtAuthenticationFilter` converts role→permissions as `GrantedAuthority` entries and stores `tenantId` in auth details; `AdminTenantContextFilter` reads tenant from JWT for `TENANT_ADMIN` and from header for `ADMIN`. `TenantController` and `AIProxyController` are restricted with `@PreAuthorize`. A new `UserManagementController` provides CRUD for `admin_users`. Frontend expands `AuthContext` to decode `role`/`tenantId` from JWT, adds a tenant switcher to the sidebar, hides restricted menu items, and adds a Users page.

**Tech Stack:** Java 21, Spring Boot 4, Spring Security 6 WebFlux, Spring Data R2DBC, Liquibase, React 18, Vite

---

## File Map

### New backend files
| File | Purpose |
|------|---------|
| `emcip-admin-api/src/main/java/io/emcip/admin/api/security/Role.java` | `ADMIN`, `TENANT_ADMIN` enum |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/security/Permission.java` | All permission constants |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/security/RolePermissions.java` | Static role→permission map |
| `emcip-admin-api/src/main/resources/db/changelog/changes/012-admin-users-add-tenant.xml` | Adds `tenant_id` to `admin_users` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/UserRequest.java` | Create/update user DTO |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/UserResponse.java` | User response DTO |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/PasswordResetRequest.java` | Password reset DTO |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/service/UserManagementService.java` | User CRUD business logic |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/UserManagementController.java` | `GET/POST/PUT/DELETE /api/users` |

### Modified backend files
| File | Change |
|------|--------|
| `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/AdminUser.java` | Add `tenantId UUID`, change `String role` → `Role role` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AdminUserRepository.java` | Add `countByRoleAndEnabled` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/security/JwtService.java` | Add `tenantId`/`tenantName` claims, `extractTenantId`/`extractTenantName` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java` | Pass `tenantId`/`tenantName` to `generateToken` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/security/JwtAuthenticationFilter.java` | Convert role→permissions, store tenantId in `auth.details` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/security/AdminTenantContextFilter.java` | Read tenantId from auth details for `TENANT_ADMIN` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java` | Add `@EnableReactiveMethodSecurity`, add `X-Tenant-Id` to CORS |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TenantController.java` | Add `@PreAuthorize("hasAuthority('TENANTS_READ')")` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java` | Add `@PreAuthorize("hasAuthority('AI_CONFIG_READ')")` |
| `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml` | Include migration 012 |

### New frontend files
| File | Purpose |
|------|---------|
| `emcip-admin-ui/src/main/frontend/src/auth/permissions.js` | `ROLE_PERMISSIONS` map + `hasPermission` helper |
| `emcip-admin-ui/src/main/frontend/src/pages/Users/Users.jsx` | User management page |
| `emcip-admin-ui/src/main/frontend/src/api/usersApi.js` | Users API module |

### Modified frontend files
| File | Change |
|------|--------|
| `emcip-admin-ui/src/main/frontend/src/auth/AuthContext.jsx` | Add `role`, `tenantId`, `currentTenant`, `setCurrentTenant` |
| `emcip-admin-ui/src/main/frontend/src/api/client.js` | Add `X-Tenant-Id` header for `ADMIN` with `currentTenant` set |
| `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx` | Tenant switcher + permission gating |
| `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.module.css` | Styles for tenant switcher |
| `emcip-admin-ui/src/main/frontend/src/App.jsx` | Add `/users` route |

---

### Task 1: Role/Permission model

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/Role.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/Permission.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/RolePermissions.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/security/RolePermissionsTest.java`

- [ ] **Step 1: Write the failing test**

```java
// emcip-admin-api/src/test/java/io/emcip/admin/api/security/RolePermissionsTest.java
package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import java.util.Set;

class RolePermissionsTest {

    @Test
    void admin_hasAllPermissions() {
        Set<Permission> perms = RolePermissions.permissionsFor(Role.ADMIN);
        assertThat(perms).containsAll(Set.of(Permission.values()));
    }

    @Test
    void tenantAdmin_hasExpectedPermissions() {
        Set<Permission> perms = RolePermissions.permissionsFor(Role.TENANT_ADMIN);
        assertThat(perms).contains(
            Permission.GROUPS_READ, Permission.GROUPS_WRITE,
            Permission.POLICY_RULES_READ, Permission.POLICY_RULES_WRITE,
            Permission.MODERATION_RULES_READ, Permission.MODERATION_RULES_WRITE,
            Permission.AUDIT_READ,
            Permission.TELEGRAM_READ, Permission.TELEGRAM_WRITE,
            Permission.SIMULATE_WRITE
        );
    }

    @Test
    void tenantAdmin_lacksAdminOnlyPermissions() {
        Set<Permission> perms = RolePermissions.permissionsFor(Role.TENANT_ADMIN);
        assertThat(perms).doesNotContain(
            Permission.AI_CONFIG_READ, Permission.AI_CONFIG_WRITE,
            Permission.TENANTS_READ, Permission.TENANTS_WRITE,
            Permission.USERS_READ, Permission.USERS_WRITE
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=RolePermissionsTest -q 2>&1 | tail -5
```
Expected: compilation error — `RolePermissions`, `Role`, `Permission` not found.

- [ ] **Step 3: Create `Role.java`**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/security/Role.java
package io.emcip.admin.api.security;

public enum Role {
    ADMIN,
    TENANT_ADMIN
}
```

- [ ] **Step 4: Create `Permission.java`**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/security/Permission.java
package io.emcip.admin.api.security;

public enum Permission {
    GROUPS_READ, GROUPS_WRITE,
    POLICY_RULES_READ, POLICY_RULES_WRITE,
    MODERATION_RULES_READ, MODERATION_RULES_WRITE,
    AUDIT_READ,
    TELEGRAM_READ, TELEGRAM_WRITE,
    SIMULATE_WRITE,
    AI_CONFIG_READ, AI_CONFIG_WRITE,
    TENANTS_READ, TENANTS_WRITE,
    USERS_READ, USERS_WRITE
}
```

- [ ] **Step 5: Create `RolePermissions.java`**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/security/RolePermissions.java
package io.emcip.admin.api.security;

import java.util.EnumSet;
import java.util.Set;

public final class RolePermissions {

    private static final Set<Permission> ADMIN_PERMISSIONS =
            EnumSet.allOf(Permission.class);

    private static final Set<Permission> TENANT_ADMIN_PERMISSIONS = EnumSet.of(
            Permission.GROUPS_READ, Permission.GROUPS_WRITE,
            Permission.POLICY_RULES_READ, Permission.POLICY_RULES_WRITE,
            Permission.MODERATION_RULES_READ, Permission.MODERATION_RULES_WRITE,
            Permission.AUDIT_READ,
            Permission.TELEGRAM_READ, Permission.TELEGRAM_WRITE,
            Permission.SIMULATE_WRITE
    );

    private RolePermissions() {}

    public static Set<Permission> permissionsFor(Role role) {
        return switch (role) {
            case ADMIN -> ADMIN_PERMISSIONS;
            case TENANT_ADMIN -> TENANT_ADMIN_PERMISSIONS;
        };
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=RolePermissionsTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 7: Commit**

```bash
cd /home/ben/Development/ecip && git add emcip-admin-api/src/main/java/io/emcip/admin/api/security/Role.java emcip-admin-api/src/main/java/io/emcip/admin/api/security/Permission.java emcip-admin-api/src/main/java/io/emcip/admin/api/security/RolePermissions.java emcip-admin-api/src/test/java/io/emcip/admin/api/security/RolePermissionsTest.java && git commit -m "feat(admin-api): add Role/Permission enums and RolePermissions matrix"
```

---

### Task 2: Data layer — Liquibase migration + entity + repository

**Files:**
- Create: `emcip-admin-api/src/main/resources/db/changelog/changes/012-admin-users-add-tenant.xml`
- Modify: `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/AdminUser.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AdminUserRepository.java`

- [ ] **Step 1: Create Liquibase migration**

```xml
<!-- emcip-admin-api/src/main/resources/db/changelog/changes/012-admin-users-add-tenant.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="012-admin-users-add-tenant" author="emcip-team">
        <comment>Add tenant_id to admin_users for TENANT_ADMIN role scoping</comment>
        <addColumn tableName="admin_users">
            <column name="tenant_id" type="UUID">
                <constraints nullable="true"
                             foreignKeyName="fk_admin_users_tenant"
                             references="tenants(id)"/>
            </column>
        </addColumn>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Register migration in master changelog**

In `db.changelog-master.xml`, add after the last `<include>` line (after `011-refresh-tokens.xml`):
```xml
    <include file="db/changelog/changes/012-admin-users-add-tenant.xml"/>
```

- [ ] **Step 3: Update `AdminUser` entity**

Replace the full content of `AdminUser.java`:

```java
package io.emcip.admin.api.entity;

import io.emcip.admin.api.security.Role;
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

@Table("admin_users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUser {

    @Id private Long id;

    private String username;

    private String email;

    @Column("password_hash")
    private String passwordHash;

    private Role role;

    private boolean enabled;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("last_login")
    private Instant lastLogin;

    @Column("created_at")
    private Instant createdAt;
}
```

- [ ] **Step 4: Add `countByRoleAndEnabled` to repository**

```java
package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.AdminUser;
import io.emcip.admin.api.security.Role;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface AdminUserRepository extends ReactiveCrudRepository<AdminUser, Long> {

    Mono<AdminUser> findByUsername(String username);

    Mono<Long> countByRoleAndEnabled(Role role, boolean enabled);
}
```

- [ ] **Step 5: Fix compilation in `AuthServiceTest` — update `enabledUser()` to use `Role` enum**

In `AuthServiceTest.java`, change `enabledUser()`:
```java
private AdminUser enabledUser() {
    return AdminUser.builder()
            .id(1L)
            .username("admin")
            .passwordHash("$2a$hash")
            .role(Role.ADMIN)
            .enabled(true)
            .build();
}
```
Also add import: `import io.emcip.admin.api.security.Role;`

- [ ] **Step 6: Compile to verify no errors**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api compile -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS` (or only warnings, no errors).

- [ ] **Step 7: Commit**

```bash
cd /home/ben/Development/ecip && git add emcip-admin-api/src/main/resources/db/changelog/ emcip-admin-api/src/main/java/io/emcip/admin/api/entity/AdminUser.java emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AdminUserRepository.java emcip-admin-api/src/test/java/io/emcip/admin/api/service/AuthServiceTest.java && git commit -m "feat(admin-api): add tenant_id to admin_users, Role enum on entity"
```

---

### Task 3: JWT service changes (S10 fix) + AuthService update

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/JwtService.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/security/JwtServiceTest.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/AuthServiceTest.java`

- [ ] **Step 1: Write failing tests for new JWT behavior**

Add to `JwtServiceTest.java` (below existing tests):
```java
@Test
void generateToken_tenantAdmin_includesTenantClaims() {
    UUID tenantId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    String token = jwtService.generateToken("alice", "TENANT_ADMIN", tenantId, "Acme Corp");

    assertThat(jwtService.extractTenantId(token)).isEqualTo(tenantId.toString());
    assertThat(jwtService.extractTenantName(token)).isEqualTo("Acme Corp");
}

@Test
void generateToken_admin_noTenantClaims() {
    String token = jwtService.generateToken("admin", "ADMIN", null, null);

    assertThat(jwtService.extractTenantId(token)).isNull();
    assertThat(jwtService.extractTenantName(token)).isNull();
}
```

Also add `import java.util.UUID;` to the test file.

- [ ] **Step 2: Run failing tests**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=JwtServiceTest -q 2>&1 | tail -10
```
Expected: compilation error — `generateToken` with 4 params and `extractTenantId`/`extractTenantName` not found.

- [ ] **Step 3: Update `JwtService.java`**

```java
package io.emcip.admin.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    @Value("${admin.jwt.secret}")
    private String secret;

    public static final long EXPIRY_MS = 60 * 60 * 1000L;

    public static final long REFRESH_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L;

    @PostConstruct
    void validateSecret() {
        if ("changeme-in-production-32chars-secret".equals(secret)) {
            throw new IllegalStateException(
                    "ADMIN_JWT_SECRET must be set to a strong random value. "
                            + "The default 'changeme-in-production-32chars-secret' is not"
                            + " acceptable for production.");
        }
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(
            String username, String role, @Nullable UUID tenantId, @Nullable String tenantName) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRY_MS);
        var builder = Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry);
        if (tenantId != null) {
            builder.claim("tenantId", tenantId.toString());
        }
        if (tenantName != null) {
            builder.claim("tenantName", tenantName);
        }
        return builder.signWith(signingKey(), Jwts.SIG.HS256).compact();
    }

    public Claims validateToken(String token) {
        return Jwts.parser().verifyWith(signingKey()).build().parseSignedClaims(token).getPayload();
    }

    public String extractUsername(String token) {
        return validateToken(token).getSubject();
    }

    public String extractRole(String token) {
        return validateToken(token).get("role", String.class);
    }

    @Nullable
    public String extractTenantId(String token) {
        return validateToken(token).get("tenantId", String.class);
    }

    @Nullable
    public String extractTenantName(String token) {
        return validateToken(token).get("tenantName", String.class);
    }
}
```

- [ ] **Step 4: Fix existing `JwtServiceTest` calls** — the old `generateToken(username, role)` is gone; update the 3 existing tests that call it:

```java
// generateToken_producesValidToken
String token = jwtService.generateToken("admin", "ADMIN", null, null);

// validateToken_extractsCorrectClaims
String token = jwtService.generateToken("admin", "ADMIN", null, null);

// extractUsername_returnsCorrectUsername
String token = jwtService.generateToken("testuser", "OPERATOR", null, null);

// extractRole_returnsCorrectRole
String token = jwtService.generateToken("testuser", "OPERATOR", null, null);
```

- [ ] **Step 5: Run `JwtServiceTest` — all tests must pass**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=JwtServiceTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Update `AuthService.java`**

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.repository.TenantRepository;
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
    private final RefreshTokenService refreshTokenService;
    private final TenantRepository tenantRepository;

    public Mono<TokenResponse> authenticate(String username, String password) {
        return userRepository
                .findByUsername(username)
                .filter(user -> user.isEnabled()
                        && passwordEncoder.matches(password, user.getPasswordHash()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid credentials")))
                .flatMap(user -> resolveTenantName(user.getTenantId() != null
                        ? user.getTenantId().toString() : null)
                        .flatMap(tenantName -> refreshTokenService
                                .issue(user.getId())
                                .map(rawRefresh -> new TokenResponse(
                                        jwtService.generateToken(
                                                user.getUsername(),
                                                user.getRole().name(),
                                                user.getTenantId(),
                                                tenantName.isEmpty() ? null : tenantName),
                                        Instant.now().plusMillis(JwtService.EXPIRY_MS),
                                        rawRefresh))));
    }

    public Mono<TokenResponse> refresh(String rawRefreshToken) {
        return refreshTokenService
                .rotate(rawRefreshToken)
                .flatMap(result -> userRepository
                        .findById(result.userId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.UNAUTHORIZED, "User not found")))
                        .flatMap(user -> resolveTenantName(user.getTenantId() != null
                                ? user.getTenantId().toString() : null)
                                .map(tenantName -> new TokenResponse(
                                        jwtService.generateToken(
                                                user.getUsername(),
                                                user.getRole().name(),
                                                user.getTenantId(),
                                                tenantName.isEmpty() ? null : tenantName),
                                        Instant.now().plusMillis(JwtService.EXPIRY_MS),
                                        result.newRawToken()))));
    }

    /** Returns tenant name, or empty string if tenantId is null or tenant not found. */
    private Mono<String> resolveTenantName(String tenantIdStr) {
        if (tenantIdStr == null) return Mono.just("");
        return tenantRepository
                .findById(java.util.UUID.fromString(tenantIdStr))
                .map(t -> t.getName())
                .defaultIfEmpty("");
    }
}
```

- [ ] **Step 7: Update `AuthServiceTest`** — the mock expectation for `jwtService.generateToken` changed signature:

```java
// In authenticate_validCredentials_returnsTokenWithRefresh:
when(jwtService.generateToken("admin", "ADMIN", null, null)).thenReturn("jwt-abc");

// In the refresh test (if it exists), similarly update the mock.
```

Also add `@Mock private TenantRepository tenantRepository;` to the test class, and `import io.emcip.admin.api.repository.TenantRepository;`.

- [ ] **Step 8: Run `AuthServiceTest`**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=AuthServiceTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 9: Commit**

```bash
cd /home/ben/Development/ecip && git add emcip-admin-api/src/main/java/io/emcip/admin/api/security/JwtService.java emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java emcip-admin-api/src/test/java/io/emcip/admin/api/security/JwtServiceTest.java emcip-admin-api/src/test/java/io/emcip/admin/api/service/AuthServiceTest.java && git commit -m "feat(admin-api): add tenantId/tenantName JWT claims (S10 fix)"
```

---

### Task 4: JwtAuthenticationFilter — role→permissions + tenantId in details

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/JwtAuthenticationFilter.java`

- [ ] **Step 1: Write a failing test (add to `SecurityFilterChainTest.java` or create if needed)**

Add to `SecurityFilterChainTest.java`:
```java
@Test
void validTenantAdminJwt_populatesPermissionsAndDetails() {
    // Given a TENANT_ADMIN token
    JwtService jwtService = new JwtService();
    ReflectionTestUtils.setField(jwtService, "secret",
            "test-secret-key-must-be-32-chars-minimum!!");
    String token = jwtService.generateToken("alice", "TENANT_ADMIN",
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), "Acme Corp");

    MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/telegram/accounts")
                    .header("Authorization", "Bearer " + token).build());

    AtomicReference<Authentication> capturedAuth = new AtomicReference<>();
    WebFilterChain chain = ex ->
            ReactiveSecurityContextHolder.getContext()
                    .doOnNext(ctx -> capturedAuth.set(ctx.getAuthentication()))
                    .then();

    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);
    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    Authentication auth = capturedAuth.get();
    assertThat(auth).isNotNull();
    assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .contains("ROLE_TENANT_ADMIN", "TELEGRAM_READ", "TELEGRAM_WRITE")
            .doesNotContain("AI_CONFIG_READ", "TENANTS_READ");
    assertThat(auth.getDetails()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
}
```

Add imports needed:
```java
import io.emcip.admin.api.security.JwtService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
```

- [ ] **Step 2: Run failing test**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=SecurityFilterChainTest -q 2>&1 | tail -10
```
Expected: test fails — authorities still contain only `ROLE_TENANT_ADMIN` without permissions, no details set.

- [ ] **Step 3: Update `JwtAuthenticationFilter.java`**

```java
package io.emcip.admin.api.security;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            String username = jwtService.extractUsername(token);
            String roleStr = jwtService.extractRole(token);
            Role role = Role.valueOf(roleStr);
            String tenantId = jwtService.extractTenantId(token);

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            RolePermissions.permissionsFor(role).forEach(
                    p -> authorities.add(new SimpleGrantedAuthority(p.name())));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            authentication.setDetails(tenantId); // null for ADMIN, UUID string for TENANT_ADMIN

            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return chain.filter(exchange);
        }
    }
}
```

- [ ] **Step 4: Run test**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=SecurityFilterChainTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip && git add emcip-admin-api/src/main/java/io/emcip/admin/api/security/JwtAuthenticationFilter.java emcip-admin-api/src/test/java/io/emcip/admin/api/security/SecurityFilterChainTest.java && git commit -m "feat(admin-api): JwtAuthenticationFilter converts role to permission authorities"
```

---

### Task 5: AdminTenantContextFilter + SecurityConfig

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/AdminTenantContextFilter.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/security/AdminTenantContextFilterTest.java`

- [ ] **Step 1: Write failing tests for new TENANT_ADMIN path**

Add to `AdminTenantContextFilterTest.java`:
```java
@Test
void tenantAdminWithDetailsInAuth_setsTenantContext() {
    MockServerWebExchange exchange =
            MockServerWebExchange.from(MockServerHttpRequest.get("/api/groups").build());

    var auth = new UsernamePasswordAuthenticationToken(
            "alice", null,
            List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
    auth.setDetails("550e8400-e29b-41d4-a716-446655440000");

    AtomicReference<String> capturedTenant = new AtomicReference<>();
    WebFilterChain chain = ex ->
            ReactorTenantContext.getTenantId()
                    .doOnNext(capturedTenant::set)
                    .then();

    StepVerifier.create(
            filter.filter(exchange, chain)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
            .verifyComplete();

    assertThat(capturedTenant.get()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
}

@Test
void tenantAdmin_ignoresXTenantIdHeader() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/groups")
                    .header("X-Tenant-Id", "spoofed-tenant-id")
                    .build());

    var auth = new UsernamePasswordAuthenticationToken(
            "alice", null,
            List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
    auth.setDetails("real-tenant-uuid");

    AtomicReference<String> capturedTenant = new AtomicReference<>();
    WebFilterChain chain = ex ->
            ReactorTenantContext.getTenantId()
                    .doOnNext(capturedTenant::set)
                    .then();

    StepVerifier.create(
            filter.filter(exchange, chain)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
            .verifyComplete();

    assertThat(capturedTenant.get()).isEqualTo("real-tenant-uuid");
}
```

- [ ] **Step 2: Run failing tests**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=AdminTenantContextFilterTest -q 2>&1 | tail -10
```
Expected: new tests fail — filter doesn't read from `auth.getDetails()` yet.

- [ ] **Step 3: Update `AdminTenantContextFilter.java`**

```java
package io.emcip.admin.api.security;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

@Slf4j
public class AdminTenantContextFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator")
                || path.equals("/api/auth/token")
                || path.equals("/auth/token")
                || path.equals("/api/auth/refresh")) {
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
                .flatMap(secCtx -> {
                    var auth = secCtx.getAuthentication();
                    if (auth == null) {
                        return chain.filter(exchange);
                    }

                    boolean isAdmin = auth.getAuthorities().stream()
                            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

                    if (isAdmin) {
                        String headerTenantId = exchange.getRequest().getHeaders()
                                .getFirst(TenantContext.HEADER_NAME);
                        if (headerTenantId != null && !headerTenantId.isBlank()) {
                            return chain.filter(exchange)
                                    .contextWrite(ctx ->
                                            ReactorTenantContext.withTenant(ctx, headerTenantId));
                        }
                        return chain.filter(exchange)
                                .contextWrite(ReactorTenantContext::withAdminMode);
                    }

                    // TENANT_ADMIN: read tenantId from JWT (stored in auth.details)
                    String tenantId = (String) auth.getDetails();
                    if (tenantId != null && !tenantId.isBlank()) {
                        return chain.filter(exchange)
                                .contextWrite(ctx ->
                                        ReactorTenantContext.withTenant(ctx, tenantId));
                    }

                    log.debug("Rejected {} — authenticated user has no tenant context",
                            exchange.getRequest().getPath());
                    exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                    return exchange.getResponse().setComplete();
                })
                .switchIfEmpty(chain.filter(exchange));
    }
}
```

- [ ] **Step 4: Add `@EnableReactiveMethodSecurity` and `X-Tenant-Id` CORS header to `SecurityConfig.java`**

Add `@EnableReactiveMethodSecurity` annotation and update `corsConfigurationSource`:

```java
// Add to class-level annotations:
@EnableReactiveMethodSecurity

// In corsConfigurationSource(), change:
config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Tenant-Id"));
```

Full import to add: `import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;`

- [ ] **Step 5: Run all `AdminTenantContextFilterTest` tests**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=AdminTenantContextFilterTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip && git add emcip-admin-api/src/main/java/io/emcip/admin/api/security/AdminTenantContextFilter.java emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java emcip-admin-api/src/test/java/io/emcip/admin/api/security/AdminTenantContextFilterTest.java && git commit -m "feat(admin-api): read tenant from JWT details for TENANT_ADMIN (S10 fix)"
```

---

### Task 6: Restrict ADMIN-only controllers with `@PreAuthorize`

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TenantController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java`

Note: `WebTestClient.bindToController(...)` unit tests do NOT run the method security interceptors. The `@PreAuthorize` annotations are verified by integration tests (or the `SecurityFilterChainTest`). Add the annotations and verify compilation only.

- [ ] **Step 1: Add `@PreAuthorize` to `TenantController`**

Add class-level annotation (blocks `TENANT_ADMIN` from all tenant endpoints since they lack `TENANTS_READ`):

```java
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TENANTS_READ')")
@Tag(name = "Tenants", description = "Manage EMCIP tenants")
public class TenantController {
```

- [ ] **Step 2: Add `@PreAuthorize` to `AIProxyController`**

Add class-level annotation:
```java
import org.springframework.security.access.prepost.PreAuthorize;

@Slf4j
@RestController
@RequestMapping("/api/ai")
@PreAuthorize("hasAuthority('AI_CONFIG_READ')")
@Tag(name = "AI Proxy", description = "Proxy to llm-orchestrator model and template management")
public class AIProxyController {
```

- [ ] **Step 3: Verify compilation and existing tests pass**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest="TenantControllerTest,AIProxyControllerTest" -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS` (unit tests don't enforce method security, so they pass unchanged).

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip && git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TenantController.java emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java && git commit -m "feat(admin-api): restrict TenantController and AIProxyController to TENANTS_READ/AI_CONFIG_READ"
```

---

### Task 7: User Management API

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/UserRequest.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/UserResponse.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/PasswordResetRequest.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/UserManagementService.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/UserManagementController.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/UserManagementServiceTest.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/UserManagementControllerTest.java`

- [ ] **Step 1: Create DTOs**

```java
// UserRequest.java
package io.emcip.admin.api.dto;

import io.emcip.admin.api.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
public class UserRequest {
    @NotBlank private String username;
    @NotBlank @Email private String email;
    private String password; // required on create, optional on update
    @NotNull private Role role;
    private UUID tenantId;   // required when role == TENANT_ADMIN
    private Boolean enabled; // optional; only applied on update when non-null
}
```

```java
// UserResponse.java
package io.emcip.admin.api.dto;

import io.emcip.admin.api.security.Role;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UserResponse {
    Long id;
    String username;
    String email;
    Role role;
    UUID tenantId;
    String tenantName;
    boolean enabled;
    Instant createdAt;
}
```

```java
// PasswordResetRequest.java
package io.emcip.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PasswordResetRequest {
    @NotBlank private String newPassword;
}
```

- [ ] **Step 2: Write failing service tests**

```java
// emcip-admin-api/src/test/java/io/emcip/admin/api/service/UserManagementServiceTest.java
package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.dto.UserRequest;
import io.emcip.admin.api.dto.UserResponse;
import io.emcip.admin.api.entity.AdminUser;
import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.repository.TenantRepository;
import io.emcip.admin.api.security.Role;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    @Mock private AdminUserRepository userRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private UserManagementService userManagementService;

    @Test
    void createUser_tenantAdmin_requiresTenantId() {
        UserRequest req = new UserRequest();
        req.setUsername("alice");
        req.setEmail("alice@example.com");
        req.setPassword("secret");
        req.setRole(Role.TENANT_ADMIN);
        req.setTenantId(null); // missing

        StepVerifier.create(userManagementService.create(req))
                .expectErrorMatches(e -> e instanceof ResponseStatusException
                        && e.getMessage().contains("400"))
                .verify();
    }

    @Test
    void createUser_tenantAdmin_validRequest_savesUser() {
        UUID tenantId = UUID.randomUUID();
        UserRequest req = new UserRequest();
        req.setUsername("alice");
        req.setEmail("alice@example.com");
        req.setPassword("secret");
        req.setRole(Role.TENANT_ADMIN);
        req.setTenantId(tenantId);

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Acme Corp");

        when(tenantRepository.existsById(tenantId)).thenReturn(Mono.just(true));
        when(passwordEncoder.encode("secret")).thenReturn("$2a$hash");
        when(userRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(tenantRepository.findById(tenantId)).thenReturn(Mono.just(tenant));

        StepVerifier.create(userManagementService.create(req))
                .assertNext(resp -> {
                    assertThat(resp.getUsername()).isEqualTo("alice");
                    assertThat(resp.getRole()).isEqualTo(Role.TENANT_ADMIN);
                    assertThat(resp.getTenantId()).isEqualTo(tenantId);
                    assertThat(resp.getTenantName()).isEqualTo("Acme Corp");
                })
                .verifyComplete();
    }

    @Test
    void deleteUser_lastAdmin_rejected() {
        AdminUser adminUser = AdminUser.builder()
                .id(1L).username("admin").role(Role.ADMIN).enabled(true).build();

        when(userRepository.findById(1L)).thenReturn(Mono.just(adminUser));
        when(userRepository.countByRoleAndEnabled(Role.ADMIN, true)).thenReturn(Mono.just(1L));

        StepVerifier.create(userManagementService.delete(1L, "admin"))
                .expectErrorMatches(e -> e instanceof ResponseStatusException
                        && e.getMessage().contains("400"))
                .verify();
    }
}
```

- [ ] **Step 3: Run failing tests**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=UserManagementServiceTest -q 2>&1 | tail -5
```
Expected: compilation error — `UserManagementService` not found.

- [ ] **Step 4: Create `UserManagementService.java`**

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.dto.UserRequest;
import io.emcip.admin.api.dto.UserResponse;
import io.emcip.admin.api.entity.AdminUser;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.repository.TenantRepository;
import io.emcip.admin.api.security.Role;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final AdminUserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public Flux<UserResponse> findAll() {
        return userRepository.findAll()
                .flatMap(this::toResponse);
    }

    public Mono<UserResponse> create(UserRequest req) {
        return validateRequest(req)
                .then(Mono.defer(() -> {
                    AdminUser user = AdminUser.builder()
                            .username(req.getUsername())
                            .email(req.getEmail())
                            .passwordHash(passwordEncoder.encode(req.getPassword()))
                            .role(req.getRole())
                            .tenantId(req.getTenantId())
                            .enabled(true)
                            .createdAt(Instant.now())
                            .build();
                    return userRepository.save(user);
                }))
                .flatMap(this::toResponse);
    }

    public Mono<UserResponse> update(Long id, UserRequest req) {
        return userRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(user -> validateRequest(req).thenReturn(user))
                .flatMap(user -> {
                    user.setRole(req.getRole());
                    user.setTenantId(req.getTenantId());
                    if (req.getEnabled() != null) user.setEnabled(req.getEnabled());
                    return userRepository.save(user);
                })
                .flatMap(this::toResponse);
    }

    public Mono<Void> delete(Long id, String callerUsername) {
        return userRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(user -> {
                    if (user.getUsername().equals(callerUsername)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Cannot delete your own account"));
                    }
                    if (user.getRole() == Role.ADMIN) {
                        return userRepository.countByRoleAndEnabled(Role.ADMIN, true)
                                .flatMap(count -> {
                                    if (count <= 1) {
                                        return Mono.error(new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "Cannot delete the last enabled admin user"));
                                    }
                                    return userRepository.delete(user);
                                });
                    }
                    return userRepository.delete(user);
                });
    }

    public Mono<Void> resetPassword(Long id, String newPassword) {
        return userRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(user -> {
                    user.setPasswordHash(passwordEncoder.encode(newPassword));
                    return userRepository.save(user);
                })
                .then();
    }

    private Mono<Void> validateRequest(UserRequest req) {
        if (req.getRole() == Role.TENANT_ADMIN && req.getTenantId() == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "tenantId is required for TENANT_ADMIN role"));
        }
        if (req.getRole() == Role.ADMIN && req.getTenantId() != null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "ADMIN role must not have a tenantId"));
        }
        if (req.getRole() == Role.TENANT_ADMIN && req.getTenantId() != null) {
            return tenantRepository.existsById(req.getTenantId())
                    .flatMap(exists -> exists
                            ? Mono.empty()
                            : Mono.error(new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "Tenant not found")));
        }
        return Mono.empty();
    }

    private Mono<UserResponse> toResponse(AdminUser user) {
        Mono<String> tenantNameMono = user.getTenantId() != null
                ? tenantRepository.findById(user.getTenantId())
                        .map(t -> t.getName())
                        .defaultIfEmpty("")
                : Mono.just("");

        return tenantNameMono.map(tenantName -> UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .tenantId(user.getTenantId())
                .tenantName(tenantName.isEmpty() ? null : tenantName)
                .enabled(user.isEnabled())
                .createdAt(user.getCreatedAt())
                .build());
    }
}
```

- [ ] **Step 5: Run service tests**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest=UserManagementServiceTest -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Create `UserManagementController.java`**

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.PasswordResetRequest;
import io.emcip.admin.api.dto.UserRequest;
import io.emcip.admin.api.dto.UserResponse;
import io.emcip.admin.api.service.UserManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('USERS_WRITE')")
@Tag(name = "Users", description = "Manage admin users and their roles")
public class UserManagementController {

    private final UserManagementService userManagementService;

    @Operation(summary = "List all admin users")
    @GetMapping
    public Flux<UserResponse> listUsers() {
        return userManagementService.findAll();
    }

    @Operation(summary = "Create a new admin user")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<UserResponse> createUser(@Valid @RequestBody UserRequest req) {
        return userManagementService.create(req);
    }

    @Operation(summary = "Update a user's role, tenant, or enabled status")
    @PutMapping("/{id}")
    public Mono<UserResponse> updateUser(
            @PathVariable Long id, @Valid @RequestBody UserRequest req) {
        return userManagementService.update(id, req);
    }

    @Operation(summary = "Delete a user")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteUser(@PathVariable Long id, Principal principal) {
        return userManagementService.delete(id, principal.getName());
    }

    @Operation(summary = "Reset a user's password")
    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> resetPassword(
            @PathVariable Long id, @Valid @RequestBody PasswordResetRequest req) {
        return userManagementService.resetPassword(id, req.getNewPassword());
    }
}
```

- [ ] **Step 7: Write and run controller test**

```java
// emcip-admin-api/src/test/java/io/emcip/admin/api/controller/UserManagementControllerTest.java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.dto.UserRequest;
import io.emcip.admin.api.dto.UserResponse;
import io.emcip.admin.api.security.Role;
import io.emcip.admin.api.service.UserManagementService;
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
class UserManagementControllerTest {

    @Mock private UserManagementService userManagementService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient
                .bindToController(new UserManagementController(userManagementService))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private UserResponse sampleUser() {
        return UserResponse.builder()
                .id(1L).username("alice").email("alice@example.com")
                .role(Role.TENANT_ADMIN).tenantId(UUID.randomUUID())
                .tenantName("Acme Corp").enabled(true).build();
    }

    @Test
    void listUsers_returns200() {
        when(userManagementService.findAll()).thenReturn(Flux.just(sampleUser()));

        webTestClient.get().uri("/api/users").exchange().expectStatus().isOk()
                .expectBodyList(UserResponse.class).hasSize(1);
    }

    @Test
    void createUser_returns201() {
        when(userManagementService.create(any())).thenReturn(Mono.just(sampleUser()));

        UserRequest req = new UserRequest();
        req.setUsername("alice");
        req.setEmail("alice@example.com");
        req.setPassword("secret");
        req.setRole(Role.TENANT_ADMIN);
        req.setTenantId(UUID.randomUUID());

        webTestClient.post().uri("/api/users")
                .bodyValue(req).exchange().expectStatus().isCreated();
    }

    @Test
    void deleteUser_returns204() {
        when(userManagementService.delete(any(), any())).thenReturn(Mono.empty());

        webTestClient.delete().uri("/api/users/1").exchange().expectStatus().isNoContent();
    }
}
```

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -Dtest="UserManagementServiceTest,UserManagementControllerTest" -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8: Run full admin-api test suite**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -q 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`. Fix any broken tests before proceeding.

- [ ] **Step 9: Apply Spotless**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api spotless:apply -q && mvn -pl emcip-admin-api spotless:check -q 2>&1 | tail -5
```
Expected: `0 were changed to be clean` (or apply formats them and check reports 0).

- [ ] **Step 10: Commit**

```bash
cd /home/ben/Development/ecip && git add emcip-admin-api/src/main/java/io/emcip/admin/api/dto/ emcip-admin-api/src/main/java/io/emcip/admin/api/service/UserManagementService.java emcip-admin-api/src/main/java/io/emcip/admin/api/controller/UserManagementController.java emcip-admin-api/src/test/java/io/emcip/admin/api/service/UserManagementServiceTest.java emcip-admin-api/src/test/java/io/emcip/admin/api/controller/UserManagementControllerTest.java && git commit -m "feat(admin-api): add UserManagementController and service (ADMIN-only CRUD)"
```

---

### Task 8: Frontend — Auth context, permissions helper, API client

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/auth/permissions.js`
- Modify: `emcip-admin-ui/src/main/frontend/src/auth/AuthContext.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/api/client.js`

- [ ] **Step 1: Create `permissions.js`**

```js
// emcip-admin-ui/src/main/frontend/src/auth/permissions.js
export const ROLE_PERMISSIONS = {
  ADMIN: [
    'GROUPS_READ', 'GROUPS_WRITE',
    'POLICY_RULES_READ', 'POLICY_RULES_WRITE',
    'MODERATION_RULES_READ', 'MODERATION_RULES_WRITE',
    'AUDIT_READ',
    'TELEGRAM_READ', 'TELEGRAM_WRITE',
    'SIMULATE_WRITE',
    'AI_CONFIG_READ', 'AI_CONFIG_WRITE',
    'TENANTS_READ', 'TENANTS_WRITE',
    'USERS_READ', 'USERS_WRITE',
  ],
  TENANT_ADMIN: [
    'GROUPS_READ', 'GROUPS_WRITE',
    'POLICY_RULES_READ', 'POLICY_RULES_WRITE',
    'MODERATION_RULES_READ', 'MODERATION_RULES_WRITE',
    'AUDIT_READ',
    'TELEGRAM_READ', 'TELEGRAM_WRITE',
    'SIMULATE_WRITE',
  ],
}

export function hasPermission(role, permission) {
  return ROLE_PERMISSIONS[role]?.includes(permission) ?? false
}
```

- [ ] **Step 2: Update `AuthContext.jsx`**

Replace full content:
```jsx
import { createContext, useContext, useState } from 'react'
import { makeRefreshableRequest } from '../api/client'

const API_BASE = import.meta.env.VITE_API_BASE ?? ''
const AuthContext = createContext(null)

function decodeJwt(token) {
  try {
    const payload = token.split('.')[1]
    return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
  } catch {
    return null
  }
}

function storedTenant() {
  const raw = sessionStorage.getItem('emcip-current-tenant')
  return raw ? JSON.parse(raw) : null
}

export function AuthProvider({ children }) {
  const [token, setToken] = useState(() => sessionStorage.getItem('emcip-token'))
  const [role, setRole] = useState(() => {
    const t = sessionStorage.getItem('emcip-token')
    return t ? (decodeJwt(t)?.role ?? null) : null
  })
  const [tenantId, setTenantId] = useState(() => {
    const t = sessionStorage.getItem('emcip-token')
    return t ? (decodeJwt(t)?.tenantId ?? null) : null
  })
  const [currentTenant, setCurrentTenantState] = useState(() => storedTenant())

  const setCurrentTenant = (tenant) => {
    if (tenant) {
      sessionStorage.setItem('emcip-current-tenant', JSON.stringify(tenant))
    } else {
      sessionStorage.removeItem('emcip-current-tenant')
    }
    setCurrentTenantState(tenant)
  }

  const login = async (username, password) => {
    const res = await fetch(`${API_BASE}/api/auth/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    })
    if (!res.ok) throw new Error('Invalid credentials')
    const data = await res.json()
    const payload = decodeJwt(data.token)
    const newRole = payload?.role ?? null
    const newTenantId = payload?.tenantId ?? null

    sessionStorage.setItem('emcip-token', data.token)
    sessionStorage.setItem('emcip-refresh-token', data.refreshToken)
    setToken(data.token)
    setRole(newRole)
    setTenantId(newTenantId)

    // TENANT_ADMIN: lock currentTenant to their JWT-embedded tenant
    if (newRole === 'TENANT_ADMIN' && newTenantId) {
      const tenant = { id: newTenantId, name: payload?.tenantName ?? newTenantId }
      setCurrentTenant(tenant)
    } else {
      setCurrentTenant(null)
    }
  }

  const logout = () => {
    const rt = sessionStorage.getItem('emcip-refresh-token')
    if (rt) {
      fetch(`${API_BASE}/api/auth/logout`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: rt }),
      }).catch(() => {})
    }
    sessionStorage.removeItem('emcip-token')
    sessionStorage.removeItem('emcip-refresh-token')
    sessionStorage.removeItem('emcip-current-tenant')
    setToken(null)
    setRole(null)
    setTenantId(null)
    setCurrentTenantState(null)
  }

  const refresh = async () => {
    const rt = sessionStorage.getItem('emcip-refresh-token')
    if (!rt) throw new Error('No refresh token')
    const res = await fetch(`${API_BASE}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt }),
    })
    if (!res.ok) throw new Error('Refresh failed')
    const data = await res.json()
    sessionStorage.setItem('emcip-token', data.token)
    sessionStorage.setItem('emcip-refresh-token', data.refreshToken)
    setToken(data.token)
    return data.token
  }

  return (
    <AuthContext.Provider
      value={{ token, role, tenantId, currentTenant, setCurrentTenant, login, logout, refresh }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}

/** Returns a fetch function that auto-refreshes on 401 and logs out on refresh failure. */
export function useAuthRequest() {
  const { token, role, currentTenant, refresh, logout } = useAuth()
  return makeRefreshableRequest(token ?? '', role, currentTenant, refresh, logout)
}
```

- [ ] **Step 3: Update `client.js`** — add `X-Tenant-Id` for ADMIN with active tenant

```js
const API_BASE = import.meta.env.VITE_API_BASE ?? ''

function tenantHeader(role, currentTenant) {
  // Only ADMIN sends X-Tenant-Id (when a specific tenant is selected).
  // TENANT_ADMIN: tenantId is in JWT; backend reads it from there.
  if (role === 'ADMIN' && currentTenant?.id) {
    return { 'X-Tenant-Id': currentTenant.id }
  }
  return {}
}

async function doFetch(token, role, currentTenant, path, options) {
  return fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...tenantHeader(role, currentTenant),
      ...options.headers,
    },
  })
}

function parseResponse(res) {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  if (res.status === 204 || res.headers?.get('content-length') === '0') return null
  return res.json()
}

export function makeRequest(token, role, currentTenant) {
  return async function request(path, options = {}) {
    const res = await doFetch(token, role, currentTenant, path, options)
    return parseResponse(res)
  }
}

/**
 * Like makeRequest, but on 401 calls onRefresh() to get a new token and retries once.
 * If the retry also fails, calls onLogout() and rethrows.
 */
export function makeRefreshableRequest(token, role, currentTenant, onRefresh, onLogout) {
  return async function request(path, options = {}) {
    const res = await doFetch(token, role, currentTenant, path, options)
    if (res.status !== 401) return parseResponse(res)

    try {
      const newToken = await onRefresh()
      const retryRes = await doFetch(newToken, role, currentTenant, path, options)
      return parseResponse(retryRes)
    } catch {
      onLogout()
      throw new Error('Session expired. Please log in again.')
    }
  }
}
```

- [ ] **Step 4: Run frontend tests to confirm nothing is broken**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend && npm test -- --run 2>&1 | tail -20
```
Expected: all existing tests pass. Fix any failures before proceeding (the `client.test.js` may need updating if it calls `makeRefreshableRequest` with old signature).

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip && git add emcip-admin-ui/src/main/frontend/src/auth/permissions.js emcip-admin-ui/src/main/frontend/src/auth/AuthContext.jsx emcip-admin-ui/src/main/frontend/src/api/client.js && git commit -m "feat(admin-ui): expand AuthContext with role/tenant, add permissions helper, add X-Tenant-Id to API client"
```

---

### Task 9: Frontend — Sidebar tenant switcher + permission gating

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.module.css`

- [ ] **Step 1: Update `Sidebar.module.css`** — add styles for tenant switcher

Append to the end of `Sidebar.module.css`:
```css
.tenantSwitcher { padding: 0.75rem 1.25rem; border-bottom: 1px solid rgba(201, 168, 76, 0.10); }
.tenantLabel { font-size: 0.72rem; color: var(--sidebar-text); opacity: 0.65; margin-bottom: 0.3rem; text-transform: uppercase; letter-spacing: 0.04em; }
.tenantSelect { width: 100%; background: rgba(123, 108, 246, 0.08); border: 1px solid rgba(201, 168, 76, 0.20); border-radius: 4px; color: var(--sidebar-text); font-size: 0.8rem; padding: 0.35rem 0.5rem; cursor: pointer; }
.tenantSelect:focus { outline: none; border-color: rgba(201, 168, 76, 0.50); }
.tenantStaticName { font-size: 0.85rem; color: var(--sidebar-active-text); font-weight: 500; }
```

- [ ] **Step 2: Replace `Sidebar.jsx`**

```jsx
import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { hasPermission } from '../../auth/permissions'
import { tenantsApi } from '../../api/tenants'
import { Logo } from '../../logo/Logo'
import { useTheme } from '../../theme/ThemeContext'
import styles from './Sidebar.module.css'

const NAV = [
  { to: '/tenants',          label: 'Tenants',          icon: '⬡', permission: 'TENANTS_READ' },
  { to: '/policy-rules',     label: 'Policy Rules',     icon: '⚖', permission: 'POLICY_RULES_READ' },
  { to: '/moderation-rules', label: 'Moderation Rules', icon: '⊘', permission: 'MODERATION_RULES_READ' },
  { to: '/flags',            label: 'Flags',            icon: '⚑', permission: 'AUDIT_READ' },
  { to: '/groups',           label: 'Groups',           icon: '◈', permission: 'GROUPS_READ' },
  { to: '/audit-log',        label: 'Audit Log',        icon: '◎', permission: 'AUDIT_READ' },
  { to: '/simulate',         label: 'Simulate Event',   icon: '▶', permission: 'SIMULATE_WRITE' },
  { to: '/telegram',         label: 'Telegram',         icon: '⌘', permission: 'TELEGRAM_READ' },
  { to: '/ai-config',        label: 'AI Config',        icon: '✦', permission: 'AI_CONFIG_READ' },
  { to: '/users',            label: 'Users',            icon: '◉', permission: 'USERS_READ' },
]

export function Sidebar() {
  const { theme, toggleTheme } = useTheme()
  const { role, currentTenant, setCurrentTenant, logout } = useAuth()
  const request = useAuthRequest()
  const [tenants, setTenants] = useState([])

  useEffect(() => {
    if (role === 'ADMIN') {
      tenantsApi(request).list()
        .then(setTenants)
        .catch(() => {})
    }
  }, [role])

  const handleTenantChange = (e) => {
    const id = e.target.value
    if (!id) {
      setCurrentTenant(null)
    } else {
      const found = tenants.find(t => t.id === id)
      setCurrentTenant(found ? { id: found.id, name: found.name } : null)
    }
  }

  return (
    <aside className={styles.sidebar}>
      <div className={styles.brand}>
        <Logo size={32} className={styles.logo} />
        <span className={`emcip-wordmark ${styles.wordmark}`}>EMCIP</span>
      </div>

      <div className={styles.tenantSwitcher}>
        <div className={styles.tenantLabel}>Tenant</div>
        {role === 'ADMIN' ? (
          <select
            className={styles.tenantSelect}
            value={currentTenant?.id ?? ''}
            onChange={handleTenantChange}
            aria-label="Select active tenant"
          >
            <option value="">All Tenants</option>
            {tenants.map(t => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </select>
        ) : (
          <span className={styles.tenantStaticName}>
            {currentTenant?.name ?? '—'}
          </span>
        )}
      </div>

      <nav className={styles.nav}>
        {NAV.filter(({ permission }) => hasPermission(role, permission)).map(({ to, label, icon }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) =>
              `${styles.item} ${isActive ? styles.active : ''}`
            }
          >
            <span className={styles.icon}>{icon}</span>
            {label}
          </NavLink>
        ))}
      </nav>

      <div className={styles.footer}>
        <button
          className={styles.themeToggle}
          onClick={toggleTheme}
          aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
          title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
        >
          {theme === 'light' ? '☽' : '☀'}
        </button>
        <button
          className={styles.logoutBtn}
          onClick={logout}
          aria-label="Logout"
        >
          ⏻ Logout
        </button>
      </div>
    </aside>
  )
}
```

- [ ] **Step 3: Run frontend tests**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend && npm test -- --run 2>&1 | tail -20
```
Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip && git add emcip-admin-ui/src/main/frontend/src/layout/Sidebar/ && git commit -m "feat(admin-ui): add global tenant switcher and permission-gated sidebar nav"
```

---

### Task 10: Frontend — Users page + routing

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/api/usersApi.js`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Users/Users.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/App.jsx`

- [ ] **Step 1: Create `usersApi.js`**

```js
// emcip-admin-ui/src/main/frontend/src/api/usersApi.js
export function usersApi(request) {
  return {
    list: () => request('/api/users'),
    create: body => request('/api/users', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) => request(`/api/users/${encodeURIComponent(id)}`, {
      method: 'PUT', body: JSON.stringify(body),
    }),
    remove: id => request(`/api/users/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    resetPassword: (id, newPassword) => request(`/api/users/${encodeURIComponent(id)}/password`, {
      method: 'POST', body: JSON.stringify({ newPassword }),
    }),
  }
}
```

- [ ] **Step 2: Create `Users.jsx`**

```jsx
// emcip-admin-ui/src/main/frontend/src/pages/Users/Users.jsx
import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { usersApi } from '../../api/usersApi'
import { tenantsApi } from '../../api/tenants'

const ROLES = ['ADMIN', 'TENANT_ADMIN']

export function Users() {
  const request = useAuthRequest()
  const api = usersApi(request)
  const tApi = tenantsApi(request)

  const [users, setUsers] = useState([])
  const [tenants, setTenants] = useState([])
  const [error, setError] = useState(null)

  // Modal state
  const [modal, setModal] = useState(null) // null | 'create' | 'edit' | 'password'
  const [selected, setSelected] = useState(null)
  const [form, setForm] = useState({ username: '', email: '', password: '', role: 'TENANT_ADMIN', tenantId: '' })
  const [newPassword, setNewPassword] = useState('')

  useEffect(() => {
    Promise.all([api.list(), tApi.list()])
      .then(([u, t]) => { setUsers(u); setTenants(t) })
      .catch(e => setError(e.message))
  }, [])

  const reload = () => api.list().then(setUsers).catch(e => setError(e.message))

  const openCreate = () => {
    setForm({ username: '', email: '', password: '', role: 'TENANT_ADMIN', tenantId: '' })
    setModal('create')
  }

  const openEdit = (user) => {
    setSelected(user)
    setForm({ username: user.username, email: user.email, password: '', role: user.role, tenantId: user.tenantId ?? '' })
    setModal('edit')
  }

  const openPasswordReset = (user) => { setSelected(user); setNewPassword(''); setModal('password') }

  const handleSubmit = async () => {
    const body = { ...form, tenantId: form.tenantId || null, password: form.password || undefined }
    try {
      if (modal === 'create') await api.create(body)
      else await api.update(selected.id, body)
      setModal(null)
      reload()
    } catch (e) { setError(e.message) }
  }

  const handleDelete = async (user) => {
    if (!confirm(`Delete user "${user.username}"?`)) return
    try { await api.remove(user.id); reload() } catch (e) { setError(e.message) }
  }

  const handlePasswordReset = async () => {
    try { await api.resetPassword(selected.id, newPassword); setModal(null) }
    catch (e) { setError(e.message) }
  }

  return (
    <div style={{ padding: '1.5rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
        <h2 style={{ margin: 0 }}>Users</h2>
        <button onClick={openCreate}>+ Add User</button>
      </div>

      {error && <p style={{ color: 'var(--color-error, red)' }}>{error}</p>}

      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            {['Username', 'Email', 'Role', 'Tenant', 'Enabled', 'Actions'].map(h => (
              <th key={h} style={{ textAlign: 'left', padding: '0.5rem', borderBottom: '1px solid rgba(201,168,76,0.2)' }}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {users.map(u => (
            <tr key={u.id}>
              <td style={{ padding: '0.5rem' }}>{u.username}</td>
              <td style={{ padding: '0.5rem' }}>{u.email}</td>
              <td style={{ padding: '0.5rem' }}>{u.role}</td>
              <td style={{ padding: '0.5rem' }}>{u.tenantName ?? '—'}</td>
              <td style={{ padding: '0.5rem' }}>{u.enabled ? '✓' : '✗'}</td>
              <td style={{ padding: '0.5rem', display: 'flex', gap: '0.5rem' }}>
                <button onClick={() => openEdit(u)}>Edit</button>
                <button onClick={() => openPasswordReset(u)}>Password</button>
                <button onClick={() => handleDelete(u)}>Delete</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {(modal === 'create' || modal === 'edit') && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ background: 'var(--bg-surface, #1e1e2e)', padding: '2rem', borderRadius: '8px', minWidth: '360px' }}>
            <h3>{modal === 'create' ? 'Add User' : 'Edit User'}</h3>
            <label>Username<br />
              <input value={form.username} onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
                disabled={modal === 'edit'} style={{ width: '100%', marginBottom: '0.75rem' }} />
            </label>
            <label>Email<br />
              <input value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
                style={{ width: '100%', marginBottom: '0.75rem' }} />
            </label>
            {modal === 'create' && (
              <label>Password<br />
                <input type="password" value={form.password} onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                  style={{ width: '100%', marginBottom: '0.75rem' }} />
              </label>
            )}
            <label>Role<br />
              <select value={form.role} onChange={e => setForm(f => ({ ...f, role: e.target.value, tenantId: '' }))}
                style={{ width: '100%', marginBottom: '0.75rem' }}>
                {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
              </select>
            </label>
            {form.role === 'TENANT_ADMIN' && (
              <label>Tenant<br />
                <select value={form.tenantId} onChange={e => setForm(f => ({ ...f, tenantId: e.target.value }))}
                  style={{ width: '100%', marginBottom: '0.75rem' }}>
                  <option value="">— select —</option>
                  {tenants.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                </select>
              </label>
            )}
            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
              <button onClick={() => setModal(null)}>Cancel</button>
              <button onClick={handleSubmit}>Save</button>
            </div>
          </div>
        </div>
      )}

      {modal === 'password' && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ background: 'var(--bg-surface, #1e1e2e)', padding: '2rem', borderRadius: '8px', minWidth: '320px' }}>
            <h3>Reset Password — {selected?.username}</h3>
            <label>New Password<br />
              <input type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)}
                style={{ width: '100%', marginBottom: '0.75rem' }} />
            </label>
            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
              <button onClick={() => setModal(null)}>Cancel</button>
              <button onClick={handlePasswordReset}>Save</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 3: Add `/users` route to `App.jsx`**

Add import and route:
```jsx
// Add import at the top with other page imports:
import { Users } from './pages/Users/Users'

// Add route inside the Route element={<AppShell />} block (after the flags route):
<Route path="users" element={<Users />} />
```

Also update `AuthGate` default redirect: ADMIN goes to `/tenants`, TENANT_ADMIN should go somewhere they can access. Update the `<Navigate>` default:
```jsx
// Replace:
<Route index element={<Navigate to="/tenants" replace />} />
// With:
<Route index element={<Navigate to="/telegram" replace />} />
```
(Telegram is accessible by both roles; ADMIN can click Tenants in sidebar if needed.)

- [ ] **Step 4: Run frontend tests**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend && npm test -- --run 2>&1 | tail -20
```
Expected: all tests pass.

- [ ] **Step 5: Final full backend test run + Spotless**

```bash
cd /home/ben/Development/ecip && mvn -pl emcip-admin-api test -q 2>&1 | tail -10 && mvn -pl emcip-admin-api spotless:apply -q
```
Expected: `BUILD SUCCESS`, 0 files reformatted.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip && git add emcip-admin-ui/src/main/frontend/src/api/usersApi.js emcip-admin-ui/src/main/frontend/src/pages/Users/ emcip-admin-ui/src/main/frontend/src/App.jsx && git commit -m "feat(admin-ui): add Users management page and route"
```
