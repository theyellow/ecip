# P1 — Critical Security Quick-Wins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the live privilege-escalation, tenant-isolation and audit-integrity holes identified in the 2026-07-18 review wave, as four independently shippable PRs.

**Architecture:** Four self-contained batches, each its own branch + PR off `main`. Batch A hardens admin-api authn/authz (WebFilter + method security). Batch B activates the audit hash chain and makes audit deletion impossible outside the sanctioned retention purge. Batch C closes two Kafka tenant-isolation gaps. Batch D is config/CI hardening with no runtime code. Batches are independent and may be done in any order, though A → B → C → D is recommended.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Security (reactive WebFlux for admin-api/audit-service), Spring Data R2DBC (audit-service), JPA (knowledge-engine), Kafka, Liquibase, JUnit 5 + AssertJ + Mockito, Maven.

## Global Constraints

- **LIQUIBASE ONLY** — never Flyway. New changesets go in the owning service's `db/changelog/`.
- **Run `mvn spotless:apply` before every commit.** Success indicator: `0 were changed to be clean`.
- **Lombok** — use `@Slf4j`, `@RequiredArgsConstructor`; never write manual getters/constructors.
- **No direct pushes to `main`.** Every batch: new branch → commit → push → `gh pr create`.
- **Cron timing** — never schedule at exact round times; always use offset seconds (e.g. `0 42 4 1 * *`).
- **Verify-first** — every task begins by re-confirming its finding against current code. The 2026-07-18 wave already produced **6 false/inaccurate findings** (see below). Do not implement a fix for a finding that no longer reproduces; mark it corrected in `BACKLOG.md §0` instead.
- Reactive services (admin-api, audit-service) use `Mono`/`Flux` — **no `@Transactional` on reactive paths**, use `TransactionalOperator`.

---

## Verification Findings (completed 2026-07-22, before planning)

These were checked against current `main`. **Three findings from the reports are wrong or overstated** — the plan is scoped accordingly.

| Finding | Report claim | Verified reality | Effect on plan |
|---------|--------------|------------------|----------------|
| RT2-003 (part 1) | JWT filter passes revoked tokens through | **TRUE** — `JwtAuthenticationFilter.java:39-42` returns `chain.filter(exchange)` with no 401 and no authentication | Task 1 |
| RT2-003 (part 2) | "Revocation not triggered on password/role/user change" | **FALSE** — `revocationService.revoke()` is already called on role change (`UserManagementService.java:143`), user deletion (`:177`), password reset (`:242`) and logout (`AuthController.java:72`) | **Dropped.** No work needed. |
| RT2-004 | TelegramAccountController "11 endpoints" / "13 endpoints" | **13 endpoints**, all with zero `@PreAuthorize` | Task 2 |
| RT2-004 | `AIProxyController.warmUp()` "unauthenticated" | **FALSE** — inherits class-level `@PreAuthorize("hasAuthority('AI_CONFIG_READ')")` (line 33). It is under-privileged, not unauthenticated. | Task 2 (still needs WRITE) |
| RT2-002 / B1 | `save()` called instead of `saveWithChain()`, with `.block()` | **TRUE** — `AuditEventConsumer.java:199` | Task 4 |
| RT2-016 | "Add a DELETE-prevention trigger" (15 min) | **Incomplete as specified** — a blanket trigger breaks the legitimate retention purge (`AuditService.deleteRecordsOlderThan()`, `AuditRetentionJob`, default `AUDIT_RETENTION=P10Y`). Needs a guarded design. | Task 5 — **re-estimated XS → M** |
| RT2-002 (follow-up) | "Schedule `verifyChain()` as a periodic job" | **Already done** — `AuditChainVerificationJobTest` / `AuditChainVerificationJob` exist | **Dropped.** |
| RT2-008 / RT2-009 | Missing / discarded tenant validation | **TRUE** — `ManualEnrichmentConsumer.java:33-49` has no call; `PolicyDecisionConsumer.java:49` discards the returned UUID | Task 6 |

---

# BATCH A — admin-api auth enforcement

**Branch:** `fix/p1-admin-api-auth-enforcement`

### Task 1: Reject revoked JWTs with 401 (RT2-003) + single-parse Claims (RT-F3)

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/JwtAuthenticationFilter.java`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/security/JwtAuthenticationFilterTest.java` (create)

**Interfaces:**
- Consumes: `JwtService.validateToken(String) -> io.jsonwebtoken.Claims`, `JwtRevocationService.isRevoked(String) -> boolean`, `RolePermissions.permissionsFor(Role) -> Set<Permission>`
- Produces: no new public API. Filter behaviour change only: revoked JTI ⇒ HTTP 401, response completed, chain not invoked.

**Context:** The filter currently calls `jwtService.extractJti/extractUsername/extractRole/extractTenantId`, each of which internally calls `validateToken()` → 4 full HMAC verifications per request. We replace all four with one `validateToken()` call and read fields off the returned `Claims`. This fixes RT-F3 in the same edit, since we are rewriting the same block.

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git pull --ff-only origin main
git checkout -b fix/p1-admin-api-auth-enforcement
```

- [ ] **Step 2: Verify the finding still reproduces**

Run: `sed -n '37,45p' emcip-admin-api/src/main/java/io/emcip/admin/api/security/JwtAuthenticationFilter.java`
Expected: line 41 is `return chain.filter(exchange);` inside the `isRevoked` branch. If it already returns 401, stop and mark RT2-003 corrected in `BACKLOG.md §0`.

- [ ] **Step 3: Write the failing test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/security/JwtAuthenticationFilterTest.java`:

```java
package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private JwtRevocationService revocationService;
    private JwtAuthenticationFilter filter;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        revocationService = mock(JwtRevocationService.class);
        filter = new JwtAuthenticationFilter(jwtService, revocationService);
        chain = mock(WebFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange exchangeWithToken(String token) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private Claims claimsFor(String jti, String username, String role, String tenantId) {
        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn(jti);
        when(claims.getSubject()).thenReturn(username);
        when(claims.get("role", String.class)).thenReturn(role);
        when(claims.get("tenantId", String.class)).thenReturn(tenantId);
        return claims;
    }

    @Test
    void revokedTokenIsRejectedWith401AndChainIsNotInvoked() {
        String jti = UUID.randomUUID().toString();
        when(jwtService.validateToken("revoked-token"))
                .thenReturn(claimsFor(jti, "alice", "ADMIN", null));
        when(revocationService.isRevoked(jti)).thenReturn(true);

        MockServerWebExchange exchange = exchangeWithToken("revoked-token");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void validTokenPassesThroughChain() {
        String jti = UUID.randomUUID().toString();
        when(jwtService.validateToken("good-token"))
                .thenReturn(claimsFor(jti, "bob", "VIEWER", null));
        when(revocationService.isRevoked(jti)).thenReturn(false);

        MockServerWebExchange exchange = exchangeWithToken("good-token");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tokenIsParsedExactlyOncePerRequest() {
        String jti = UUID.randomUUID().toString();
        when(jwtService.validateToken("good-token"))
                .thenReturn(claimsFor(jti, "bob", "VIEWER", null));
        when(revocationService.isRevoked(jti)).thenReturn(false);

        StepVerifier.create(filter.filter(exchangeWithToken("good-token"), chain)).verifyComplete();

        verify(jwtService, org.mockito.Mockito.times(1)).validateToken("good-token");
        verify(jwtService, never()).extractUsername(anyString());
        verify(jwtService, never()).extractRole(anyString());
        verify(jwtService, never()).extractJti(anyString());
        verify(jwtService, never()).extractTenantId(anyString());
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `mvn -q -pl emcip-admin-api test -Dtest=JwtAuthenticationFilterTest`
Expected: FAIL — `revokedTokenIsRejectedWith401AndChainIsNotInvoked` fails because status is null and the chain was invoked; `tokenIsParsedExactlyOncePerRequest` fails because `validateToken` is called 4×.

- [ ] **Step 5: Rewrite the filter body**

In `JwtAuthenticationFilter.java`, replace the entire `filter` method body (lines 28–63) with:

```java
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            Claims claims = jwtService.validateToken(token);

            String jti = claims.getId();
            if (jti != null && revocationService.isRevoked(jti)) {
                log.debug("Rejecting revoked JWT: jti={}", jti);
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            String username = claims.getSubject();
            Role role = Role.valueOf(claims.get("role", String.class));
            String tenantId = claims.get("tenantId", String.class);

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            RolePermissions.permissionsFor(role)
                    .forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            authentication.setDetails(tenantId);

            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return chain.filter(exchange);
        }
    }
```

Add these imports alongside the existing ones:

```java
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
```

**Note:** the `catch` block deliberately still passes through — an *invalid* token becomes an anonymous request which Spring Security's `anyExchange().authenticated()` rule rejects with 401. Only *revoked* tokens get the explicit early 401, which is what RT2-003 requires.

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q -pl emcip-admin-api test -Dtest=JwtAuthenticationFilterTest`
Expected: PASS, 3 tests.

- [ ] **Step 7: Run the full admin-api security test package for regressions**

Run: `mvn -q -pl emcip-admin-api test -Dtest='*Security*,*Jwt*,*RolePermissions*'`
Expected: PASS — `SecurityFilterChainTest`, `JwtServiceTest`, `JwtRevocationServiceTest`, `RolePermissionsTest` all green.

- [ ] **Step 8: Format and commit**

```bash
mvn spotless:apply
git add emcip-admin-api/src/main/java/io/emcip/admin/api/security/JwtAuthenticationFilter.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/security/JwtAuthenticationFilterTest.java
git commit -m "fix(admin-api): reject revoked JWTs with 401 and parse claims once

RT2-003: the filter checked isRevoked() but then called chain.filter(exchange),
letting the request proceed as anonymous instead of rejecting it. Revoked tokens
now get an explicit 401 and the chain is never invoked.

RT-F3: extractJti/extractUsername/extractRole/extractTenantId each re-parsed and
re-verified the HMAC, so every request verified the signature 4 times. Claims are
now parsed once and all fields read from the single object.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Add missing `@PreAuthorize` write permissions (RT2-004)

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TenantController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/security/ControllerAuthorizationTest.java` (create)

**Interfaces:**
- Consumes: `Permission.TELEGRAM_READ/TELEGRAM_WRITE`, `Permission.TENANTS_READ/TENANTS_WRITE`, `Permission.AI_CONFIG_READ/AI_CONFIG_WRITE` (all already exist in `Permission.java`).
- Produces: no new API. Authorization metadata only.

**Context:** `TelegramAccountController` has **zero** `@PreAuthorize` — 13 endpoints are reachable by any authenticated user. `TenantController` (line 30) and `AIProxyController` (line 33) carry class-level READ annotations that also cover their write methods, so a VIEWER-equivalent token with only the READ permission can create/update/delete. Method-level `@PreAuthorize` overrides the class-level annotation in Spring Security, so adding WRITE to mutating methods is sufficient — the class-level READ stays as the default for read methods.

- [ ] **Step 1: Verify the findings still reproduce**

Run:
```bash
grep -c '@PreAuthorize' emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java
sed -n '30p' emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TenantController.java
sed -n '33p' emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java
```
Expected: `0` for the first command; `@PreAuthorize("hasAuthority('TENANTS_READ')")` and `@PreAuthorize("hasAuthority('AI_CONFIG_READ')")` for the other two.

- [ ] **Step 2: Write the failing test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/security/ControllerAuthorizationTest.java`. This asserts the annotation contract by reflection, so it needs no Spring context and stays fast:

```java
package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.admin.api.controller.AIProxyController;
import io.emcip.admin.api.controller.TelegramAccountController;
import io.emcip.admin.api.controller.TenantController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ControllerAuthorizationTest {

    private String authorityOf(Class<?> controller, String methodName) {
        Method method =
                Arrays.stream(controller.getDeclaredMethods())
                        .filter(m -> m.getName().equals(methodName))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "No method " + methodName + " on " + controller));
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        return annotation == null ? null : annotation.value();
    }

    @Test
    void telegramWriteEndpointsRequireTelegramWrite() {
        List<String> writeMethods =
                List.of(
                        "createAccount",
                        "deleteAccount",
                        "reconnect",
                        "submitCode",
                        "submitPassword",
                        "logout",
                        "syncWatchedGroups",
                        "watchGroup",
                        "unwatchGroup");
        for (String m : writeMethods) {
            assertThat(authorityOf(TelegramAccountController.class, m))
                    .as("TelegramAccountController.%s must require TELEGRAM_WRITE", m)
                    .isEqualTo("hasAuthority('TELEGRAM_WRITE')");
        }
    }

    @Test
    void telegramReadEndpointsRequireTelegramRead() {
        List<String> readMethods = List.of("listAccounts", "getStatus", "discoverChats", "listWatched");
        for (String m : readMethods) {
            assertThat(authorityOf(TelegramAccountController.class, m))
                    .as("TelegramAccountController.%s must require TELEGRAM_READ", m)
                    .isEqualTo("hasAuthority('TELEGRAM_READ')");
        }
    }

    @Test
    void tenantWriteEndpointsRequireTenantsWrite() {
        for (String m : List.of("createTenant", "updateTenant", "deleteTenant")) {
            assertThat(authorityOf(TenantController.class, m))
                    .as("TenantController.%s must require TENANTS_WRITE", m)
                    .isEqualTo("hasAuthority('TENANTS_WRITE')");
        }
    }

    @Test
    void aiConfigWriteEndpointsRequireAiConfigWrite() {
        List<String> writeMethods =
                List.of(
                        "createModel",
                        "updateModel",
                        "deleteModel",
                        "createTemplate",
                        "updateTemplate",
                        "deleteTemplate",
                        "createProviderConfig",
                        "updateProviderConfig",
                        "deleteProviderConfig",
                        "warmUp");
        for (String m : writeMethods) {
            assertThat(authorityOf(AIProxyController.class, m))
                    .as("AIProxyController.%s must require AI_CONFIG_WRITE", m)
                    .isEqualTo("hasAuthority('AI_CONFIG_WRITE')");
        }
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q -pl emcip-admin-api test -Dtest=ControllerAuthorizationTest`
Expected: FAIL — all four tests fail; the Telegram assertions report `null`, the Tenant/AIProxy assertions report `null` (class-level annotation is not visible on the method).

- [ ] **Step 4: Annotate `TelegramAccountController`**

Add the import:

```java
import org.springframework.security.access.prepost.PreAuthorize;
```

Add a class-level default immediately above `public class TelegramAccountController {` (line 38):

```java
@PreAuthorize("hasAuthority('TELEGRAM_READ')")
```

Then add `@PreAuthorize("hasAuthority('TELEGRAM_WRITE')")` directly above each of these 9 mutating methods: `createAccount`, `deleteAccount`, `reconnect`, `submitCode`, `submitPassword`, `logout`, `syncWatchedGroups`, `watchGroup`, `unwatchGroup`.

Add `@PreAuthorize("hasAuthority('TELEGRAM_READ')")` explicitly above the 4 read methods `listAccounts`, `getStatus`, `discoverChats`, `listWatched` (the test asserts the annotation is present on the method, not merely inherited).

Example of the resulting shape:

```java
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TELEGRAM_WRITE')")
    public Mono<Map<String, Object>> createAccount(@Valid @RequestBody CreateAccountRequest req) {
```

- [ ] **Step 5: Annotate `TenantController`**

Leave the class-level `@PreAuthorize("hasAuthority('TENANTS_READ')")` on line 30. Add `@PreAuthorize("hasAuthority('TENANTS_WRITE')")` above `createTenant`, `updateTenant`, and `deleteTenant`.

- [ ] **Step 6: Annotate `AIProxyController`**

Leave the class-level `@PreAuthorize("hasAuthority('AI_CONFIG_READ')")` on line 33. Add `@PreAuthorize("hasAuthority('AI_CONFIG_WRITE')")` above `createModel`, `updateModel`, `deleteModel`, `createTemplate`, `updateTemplate`, `deleteTemplate`, `createProviderConfig`, `updateProviderConfig`, `deleteProviderConfig`, and `warmUp`.

`warmUp` triggers real LLM work and therefore counts as a write.

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn -q -pl emcip-admin-api test -Dtest=ControllerAuthorizationTest`
Expected: PASS, 4 tests.

- [ ] **Step 8: Confirm method security is actually enabled**

Run: `grep -rn 'EnableReactiveMethodSecurity\|EnableMethodSecurity' emcip-admin-api/src/main/java/`
Expected: at least one match. If there is **no** match, `@PreAuthorize` is inert — add `@EnableReactiveMethodSecurity` to the `SecurityConfig` class and re-run Step 7.

- [ ] **Step 9: Run the whole admin-api test suite**

Run: `mvn -q -pl emcip-admin-api test`
Expected: BUILD SUCCESS. If a controller test now fails with `AccessDeniedException`, that test was relying on the missing permission — update its mock authorities to include the WRITE permission.

- [ ] **Step 10: Format and commit**

```bash
mvn spotless:apply
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ \
        emcip-admin-api/src/test/java/io/emcip/admin/api/security/ControllerAuthorizationTest.java
git commit -m "fix(admin-api): require WRITE permissions on Telegram, Tenant and AI config writes

RT2-004: TelegramAccountController had no @PreAuthorize at all (13 endpoints
reachable by any authenticated user). TenantController and AIProxyController
carried class-level READ annotations that also covered their write methods, so a
READ-only principal could create, update and delete tenants and AI configuration.

Adds method-level WRITE permissions to 22 mutating endpoints and explicit READ
annotations to the Telegram read endpoints.

Correction to the report: warmUp() was not unauthenticated, it inherited the
class-level AI_CONFIG_READ. It is treated as a write because it triggers LLM work.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Collapse the double save on login (RT-F4)

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java:44-62`

**Interfaces:**
- Consumes: `AdminUserRepository.save(AdminUser) -> Mono<AdminUser>`, `JwtService.generateTokenWithJti(...) -> TokenWithJti`
- Produces: unchanged `Mono<TokenResponse> authenticate(String, String)`.

**Context:** `authenticate()` saves the user twice — once for `lastLogin` (line 45) and again for `currentJti` (line 62). Both fields can be set before a single save. This is a fold-in because we are already touching this area; it is not a security fix.

- [ ] **Step 1: Verify the finding still reproduces**

Run: `grep -n 'userRepository.save\|\.save(user)' emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java`
Expected: two save calls inside `authenticate()` (around lines 45 and 62). If only one, skip this task and mark RT-F4 corrected.

- [ ] **Step 2: Read the current method in full**

Run: `sed -n '30,90p' emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java`

Note the nesting: the first `save` result feeds a `flatMap` that later generates the token and performs the second save.

- [ ] **Step 3: Restructure to a single save**

Reorder so the token is generated **before** persisting, then set both fields and save once. The shape to produce:

```java
                            user.setLastLogin(Instant.now());
                            var tokenWithJti =
                                    jwtService.generateTokenWithJti(
                                            user.getUsername(),
                                            user.getRole(),
                                            user.getTenantId(),
                                            tenantName);
                            user.setCurrentJti(tokenWithJti.jti());
                            return userRepository
                                    .save(user)
                                    .then(/* existing refresh-token + TokenResponse assembly */);
```

Keep the surrounding tenant-name lookup and refresh-token issuance exactly as they are — only the two `save` calls collapse into one. Do not change `refresh()` (line 92), which legitimately saves once.

- [ ] **Step 4: Run the auth tests**

Run: `mvn -q -pl emcip-admin-api test -Dtest='*Auth*'`
Expected: PASS. `lastLogin` and `currentJti` must both still be persisted — if an existing test asserts two saves via Mockito `times(2)`, update it to `times(1)`.

- [ ] **Step 5: Format and commit**

```bash
mvn spotless:apply
git add emcip-admin-api/src/main/java/io/emcip/admin/api/service/AuthService.java
git commit -m "refactor(admin-api): persist lastLogin and currentJti in a single save

RT-F4: authenticate() wrote the user row twice per login. Both fields are now set
before a single save.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 6: Push and open the Batch A PR**

```bash
git push -u origin fix/p1-admin-api-auth-enforcement
gh pr create --base main --title "fix(admin-api): P1 auth enforcement — revoked JWT 401, missing @PreAuthorize, single-parse claims" --body "ROADMAP P1 batch 1.1. Closes RT2-003 (filter), RT2-004, RT-F3, RT-F4.

Verified corrections to the source reports:
- RT2-003's 'revocation not triggered on password/role/user change' is FALSE — already implemented in UserManagementService:143/177/242 and AuthController:72.
- RT2-004's 'warmUp() unauthenticated' is FALSE — it inherited class-level AI_CONFIG_READ.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

---

# BATCH B — audit integrity

**Branch:** `fix/p1-audit-integrity`

### Task 4: Activate the hash chain and remove `.block()` (RT2-002, B1)

**Files:**
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java:199`
- Test: `emcip-audit-service/src/test/java/io/emcip/audit/service/kafka/AuditEventConsumerTest.java` (exists — extend)

**Interfaces:**
- Consumes: `AuditService.saveWithChain(AuditEventEntity) -> Mono<AuditEventEntity>` (already implemented, `AuditService.java:166`).
- Produces: unchanged consumer signature; persisted rows now carry non-null `integrity_hash` and a `prev_hash` link.

**Context:** Line 199 is `auditService.save(entity).block();`. Two defects in one line: it bypasses the hash chain (`integrity_hash`/`prev_hash` stay NULL, so RT-027's tamper-evidence is inert), and it blocks a Kafka listener thread on a reactive R2DBC call. The listener is a normal (non-reactive) `@KafkaListener` using manual `Acknowledgment`, so the fix is to subscribe and acknowledge in the reactive completion callback rather than blocking.

**Ordering constraint:** the hash chain reads the previous row (`findTopByOrderByIdDesc`) and must therefore write strictly in order. Concurrent chain writes would interleave and corrupt the chain. Keep the listener single-threaded — do **not** add a concurrency setting to this listener as part of this task.

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git pull --ff-only origin main
git checkout -b fix/p1-audit-integrity
```

- [ ] **Step 2: Verify the finding still reproduces**

Run: `sed -n '199p' emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java`
Expected: `            auditService.save(entity).block();`

- [ ] **Step 3: Write the failing test**

Add to `AuditEventConsumerTest.java` (match the existing mock setup in that file — it already mocks `AuditService`):

```java
    @Test
    void persistsAuditEventThroughHashChain() {
        // Arrange: whatever record-building helper the existing tests use
        when(auditService.saveWithChain(any(AuditEventEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0, AuditEventEntity.class)));

        consumer.consumeAuditEvents(record, acknowledgment);

        verify(auditService).saveWithChain(any(AuditEventEntity.class));
        verify(auditService, never()).save(any(AuditEventEntity.class));
        verify(acknowledgment).acknowledge();
    }
```

Adjust `consumer.consumeAuditEvents(record, acknowledgment)` to the actual listener method name and the existing test's record fixture — read the top of the file first with `sed -n '1,80p'`.

- [ ] **Step 4: Run the test to verify it fails**

Run: `mvn -q -pl emcip-audit-service test -Dtest=AuditEventConsumerTest`
Expected: FAIL — `saveWithChain` was never called (the consumer calls `save`).

- [ ] **Step 5: Replace the blocking save**

Replace line 199 and the following acknowledge (lines 199–200):

```java
            auditService.save(entity).block();
            acknowledgment.acknowledge();
```

with:

```java
            auditService
                    .saveWithChain(entity)
                    .doOnSuccess(saved -> acknowledgment.acknowledge())
                    .doOnError(
                            e ->
                                    log.error(
                                            "Failed to persist audit event for {} offset {}: {}",
                                            record.topic(),
                                            record.offset(),
                                            e.getMessage(),
                                            e))
                    .subscribe();
```

**Important:** the `finally { TenantContext.clear(); }` block at line 217–219 now runs *before* the reactive save completes. Move the tenant clear into the reactive chain so it cannot clear the context out from under the save. Change the `finally` block to no longer call `TenantContext.clear()`, and instead append `.doFinally(signal -> TenantContext.clear())` to the chain above, immediately before `.subscribe()`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q -pl emcip-audit-service test -Dtest=AuditEventConsumerTest`
Expected: PASS.

- [ ] **Step 7: Run the full audit-service suite**

Run: `mvn -q -pl emcip-audit-service test`
Expected: BUILD SUCCESS — `AuditServiceTest`, `AuditChainVerificationJobTest`, `AuditRetentionJobTest` all green.

- [ ] **Step 8: Format and commit**

```bash
mvn spotless:apply
git add emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java \
        emcip-audit-service/src/test/java/io/emcip/audit/service/kafka/AuditEventConsumerTest.java
git commit -m "fix(audit-service): persist audit events through the hash chain, without blocking

RT2-002: the consumer called save() instead of saveWithChain(), so integrity_hash
and prev_hash were always NULL and the RT-027 tamper-evidence chain was inert
despite being implemented and unit-tested.

B1: the same line called .block() on an R2DBC Mono inside a Kafka listener. The
save is now subscribed reactively and the record is acknowledged on success.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Prevent unsanctioned audit deletion (RT2-016)

**Files:**
- Create: `emcip-audit-service/src/main/resources/db/changelog/004-audit-delete-protection.xml`
- Modify: `emcip-audit-service/src/main/resources/db/changelog/db.changelog-master.xml` (include the new file)
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java` (`deleteRecordsOlderThan`)
- Test: `emcip-audit-service/src/test/java/io/emcip/audit/service/service/AuditRetentionJobTest.java` (exists — extend)

**Interfaces:**
- Consumes: `DatabaseClient` (Spring Data R2DBC), `TransactionalOperator`.
- Produces: `AuditService.deleteRecordsOlderThan(Instant) -> Mono<Long>` — signature unchanged, implementation now runs the purge inside a transaction that sets the sanctioned-purge flag.

> **DESIGN NOTE — this is why RT2-016 is not a 15-minute fix.**
> The report says "add a DELETE trigger". A blanket `BEFORE DELETE` trigger would break
> `AuditRetentionJob`, which legitimately purges rows older than `AUDIT_RETENTION`
> (default `P10Y`) via `deleteByCreatedAtBefore`. Because the retention period is
> env-configurable, an age-based guard in the trigger can drift out of sync with the app.
> The design used here is a **sanctioned-purge session flag**: deletion raises an exception
> unless the transaction has set `emcip.audit_purge = 'on'`. Only the retention path sets it.
> Ad-hoc `DELETE FROM audit_events` from a psql session or a compromised app path is blocked.

- [ ] **Step 1: Verify the retention path exists and would be broken by a blanket trigger**

Run: `grep -n 'deleteByCreatedAtBefore\|deleteRecordsOlderThan' -r emcip-audit-service/src/main/java/`
Expected: `AuditService.deleteRecordsOlderThan` calls `repository.deleteByCreatedAtBefore(cutoff)`, and `AuditRetentionJob` calls `deleteRecordsOlderThan`. This confirms the guarded design is required.

- [ ] **Step 2: Confirm the master changelog include style**

Run: `cat emcip-audit-service/src/main/resources/db/changelog/db.changelog-master.xml`
Expected: a list of `<include file="db/changelog/00N-....xml"/>` entries. Match that exact path style in Step 4.

- [ ] **Step 3: Create the migration**

Create `emcip-audit-service/src/main/resources/db/changelog/004-audit-delete-protection.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="004-audit-prevent-delete-trigger" author="p1-remediation">
        <comment>RT2-016: block DELETE on audit_events outside the sanctioned retention purge</comment>
        <sql splitStatements="false">
            CREATE OR REPLACE FUNCTION prevent_audit_delete() RETURNS trigger AS $$
            BEGIN
                IF current_setting('emcip.audit_purge', true) IS DISTINCT FROM 'on' THEN
                    RAISE EXCEPTION 'audit_events rows cannot be deleted outside the sanctioned retention purge';
                END IF;
                RETURN OLD;
            END;
            $$ LANGUAGE plpgsql;

            CREATE TRIGGER audit_no_delete
                BEFORE DELETE ON audit_events
                FOR EACH ROW
                EXECUTE FUNCTION prevent_audit_delete();
        </sql>
        <rollback>
            DROP TRIGGER IF EXISTS audit_no_delete ON audit_events;
            DROP FUNCTION IF EXISTS prevent_audit_delete();
        </rollback>
    </changeSet>
</databaseChangeLog>
```

`current_setting(..., true)` returns NULL instead of erroring when the setting is absent, so `IS DISTINCT FROM 'on'` correctly blocks the default case.

- [ ] **Step 4: Register the migration**

Add to `db.changelog-master.xml`, after the `003-audit-tamper-resistance.xml` include:

```xml
    <include file="db/changelog/004-audit-delete-protection.xml"/>
```

- [ ] **Step 5: Write the failing test**

Add to `AuditRetentionJobTest.java` — or, if that test mocks the repository rather than hitting a database, add this to the Testcontainers-backed `AuditServiceTest` instead:

```java
    @Test
    void retentionPurgeSucceedsAndAdHocDeleteIsBlocked() {
        Instant old = Instant.now().minus(Duration.ofDays(400));
        // insert one old row via the normal save path, then:

        // ad-hoc delete must be rejected by the trigger
        StepVerifier.create(
                        databaseClient
                                .sql("DELETE FROM audit_events")
                                .fetch()
                                .rowsUpdated())
                .expectErrorSatisfies(
                        e ->
                                assertThat(e.getMessage())
                                        .contains("cannot be deleted outside the sanctioned"))
                .verify();

        // the sanctioned retention path must still work
        StepVerifier.create(auditService.deleteRecordsOlderThan(Instant.now()))
                .assertNext(deleted -> assertThat(deleted).isGreaterThanOrEqualTo(1L))
                .verifyComplete();
    }
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `mvn -q -pl emcip-audit-service test -Dtest=AuditServiceTest`
Expected: FAIL — the ad-hoc delete currently succeeds (no trigger yet, migration not applied to the test container until this run) or, once the trigger applies, `deleteRecordsOlderThan` fails because it does not set the flag.

- [ ] **Step 7: Make the retention purge set the sanctioned flag**

In `AuditService.java`, inject the R2DBC `DatabaseClient` and a `TransactionalOperator` via the existing `@RequiredArgsConstructor` field list:

```java
    private final org.springframework.r2dbc.core.DatabaseClient databaseClient;
    private final org.springframework.transaction.reactive.TransactionalOperator transactionalOperator;
```

Replace the delete inside `deleteRecordsOlderThan` so the flag and the delete share one transaction (and therefore one connection):

```java
            return transactionalOperator.transactional(
                    databaseClient
                            .sql("SET LOCAL emcip.audit_purge = 'on'")
                            .then()
                            .then(
                                    databaseClient
                                            .sql("DELETE FROM audit_events WHERE created_at < :cutoff")
                                            .bind("cutoff", cutoff)
                                            .fetch()
                                            .rowsUpdated()));
```

Keep the existing anchor-hash logging (`findOldestBeforeCutoff` → log `integrity_hash`) ahead of this delete — it is the forensic record of what was purged.

`SET LOCAL` scopes the flag to the transaction, so it cannot leak to other pooled connections.

- [ ] **Step 8: Run the test to verify it passes**

Run: `mvn -q -pl emcip-audit-service test -Dtest=AuditServiceTest`
Expected: PASS — ad-hoc delete rejected, retention purge succeeds.

- [ ] **Step 9: Run the full audit-service suite**

Run: `mvn -q -pl emcip-audit-service test`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Format and commit**

```bash
mvn spotless:apply
git add emcip-audit-service/src/main/resources/db/changelog/ \
        emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java \
        emcip-audit-service/src/test/java/io/emcip/audit/service/service/
git commit -m "feat(audit-service): block audit deletion outside the sanctioned retention purge

RT2-016: audit_events had an UPDATE trigger but no DELETE protection, so anyone
with database access could delete incriminating records undetected.

A blanket DELETE trigger as the report suggested would have broken AuditRetentionJob,
which legitimately purges rows older than AUDIT_RETENTION (default P10Y, and
env-configurable, so an age-based guard would drift). Instead the trigger rejects
any DELETE unless the transaction sets emcip.audit_purge = 'on', and only the
retention path sets it via SET LOCAL inside its transaction.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 11: Push and open the Batch B PR**

```bash
git push -u origin fix/p1-audit-integrity
gh pr create --base main --title "fix(audit-service): P1 audit integrity — activate hash chain, block unsanctioned deletes" --body "ROADMAP P1 batch 1.2. Closes RT2-002, RT2-016, B1.

Note: RT2-016 was re-scoped from XS to M. A blanket DELETE trigger would have broken the retention job (AUDIT_RETENTION default P10Y, env-configurable). Uses a sanctioned-purge session flag instead.

Note: the 'schedule verifyChain() periodically' follow-up is already implemented (AuditChainVerificationJob).

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

---

# BATCH C — Kafka tenant isolation

**Branch:** `fix/p1-kafka-tenant-isolation`

### Task 6: Close both tenant-validation gaps (RT2-008, RT2-009)

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ManualEnrichmentConsumer.java:34`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/PolicyDecisionConsumer.java:49`
- Test: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/PolicyDecisionConsumerTest.java` (create or extend)

**Interfaces:**
- Consumes: `TenantAwareKafkaSupport.validateTenantHeader(ConsumerRecord<?,?>) -> UUID` (throws `IllegalStateException` when the header is missing or malformed), `TenantContext.setTenantId(String)`, `TenantContext.clear()`.
- Produces: no API change. Both consumers now fail closed on a missing/invalid tenant header, and the LLM orchestrator runs downstream work inside a bound tenant context.

**Context:** `ManualEnrichmentConsumer` never calls `validateTenantHeader`; it infers tenancy from the looked-up entity, which cannot reject a forged header at the consumer boundary. `PolicyDecisionConsumer` calls `validateTenantHeader` but throws the returned UUID away and never binds `TenantContext`, so knowledge enrichment during LLM calls runs without a tenant filter.

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git pull --ff-only origin main
git checkout -b fix/p1-kafka-tenant-isolation
```

- [ ] **Step 2: Verify both findings still reproduce**

Run:
```bash
grep -n 'validateTenantHeader\|TenantContext' emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ManualEnrichmentConsumer.java
sed -n '48,54p' emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/PolicyDecisionConsumer.java
```
Expected: no matches for the first (confirms RT2-008); line 49 shows a bare `TenantAwareKafkaSupport.validateTenantHeader(record);` with no assignment (confirms RT2-009).

- [ ] **Step 3: Write the failing test for the orchestrator**

Create/extend `PolicyDecisionConsumerTest.java`:

```java
    @Test
    void bindsTenantContextFromKafkaHeader() {
        UUID tenantId = UUID.randomUUID();
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("policies.decisions", 0, 0L, "key", validDecisionJson());
        record.headers()
                .add(TenantContext.KAFKA_HEADER, tenantId.toString().getBytes(StandardCharsets.UTF_8));

        AtomicReference<String> boundTenant = new AtomicReference<>();
        // capture the tenant visible to downstream work
        doAnswer(inv -> {
                    boundTenant.set(TenantContext.getTenantId());
                    return null;
                })
                .when(llmCallService)
                .handle(any());

        consumer.consume(record);

        assertThat(boundTenant.get()).isEqualTo(tenantId.toString());
    }

    @Test
    void rejectsRecordWithoutTenantHeader() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("policies.decisions", 0, 0L, "key", validDecisionJson());

        consumer.consume(record);

        verifyNoInteractions(llmCallService);
    }
```

Adapt `llmCallService`/`handle` to the actual collaborator invoked by `handleRespondDecision` — read `sed -n '55,110p'` of the consumer first.

- [ ] **Step 4: Run the test to verify it fails**

Run: `mvn -q -pl emcip-llm-orchestrator test -Dtest=PolicyDecisionConsumerTest`
Expected: FAIL — `boundTenant` is null because `TenantContext` is never set.

- [ ] **Step 5: Fix `PolicyDecisionConsumer`**

Replace lines 48–53:

```java
        try {
            TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting record: {}", e.getMessage());
            return;
        }
```

with:

```java
        UUID tenantId;
        try {
            tenantId = TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting record: {}", e.getMessage());
            return;
        }
        TenantContext.setTenantId(tenantId.toString());
```

Wrap the remaining body so the context is always cleared. The existing `try { ... }` starting at line 55 gets a `finally`:

```java
        } finally {
            TenantContext.clear();
        }
```

Add imports `java.util.UUID` and `io.emcip.common.tenant.TenantContext` if absent.

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q -pl emcip-llm-orchestrator test -Dtest=PolicyDecisionConsumerTest`
Expected: PASS.

- [ ] **Step 7: Fix `ManualEnrichmentConsumer`**

At the very start of `consume` (immediately after the opening brace on line 34, before the existing `try`), add the boundary check:

```java
        UUID tenantId;
        try {
            tenantId = TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting enrichment trigger: {}", e.getMessage());
            return;
        }
```

Then, after the existing `source`/`run` lookup succeeds (after line 49), reject a mismatch between the header and the entity — this is the check that makes the header meaningful:

```java
            if (!tenantId.equals(source.get().getTenantId())) {
                log.error(
                        "Tenant mismatch on enrichment trigger: header={} source={}",
                        tenantId,
                        source.get().getTenantId());
                return;
            }
```

Add imports `java.util.UUID` (likely present) and `io.emcip.common.tenant.TenantAwareKafkaSupport`.

- [ ] **Step 8: Build both modules**

Run: `mvn -q -pl emcip-knowledge-engine,emcip-llm-orchestrator test`
Expected: BUILD SUCCESS. If a knowledge-engine test publishes an enrichment trigger without a tenant header, it will now fail — add the header to that fixture, which is the correct fix.

- [ ] **Step 9: Format and commit**

```bash
mvn spotless:apply
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ManualEnrichmentConsumer.java \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/PolicyDecisionConsumer.java \
        emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/PolicyDecisionConsumerTest.java
git commit -m "fix(knowledge-engine,llm-orchestrator): close two Kafka tenant-isolation gaps

RT2-008: ManualEnrichmentConsumer never validated the tenant_id header, breaking
the fail-closed pattern used by the other consumers. It now validates at the
consumer boundary and rejects a header that disagrees with the source entity.

RT2-009: PolicyDecisionConsumer called validateTenantHeader() but discarded the
returned UUID and never bound TenantContext, so downstream LLM work — including
knowledge enrichment — ran without a tenant filter. The context is now bound for
the duration of the record and cleared in a finally block.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 10: Push and open the Batch C PR**

```bash
git push -u origin fix/p1-kafka-tenant-isolation
gh pr create --base main --title "fix: P1 Kafka tenant isolation — RT2-008, RT2-009" --body "ROADMAP P1 batch 1.3. Closes RT2-008, RT2-009.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

---

# BATCH D — config & CI hardening

**Branch:** `chore/p1-config-ci-hardening`

### Task 7: Actuator, SAST, pinned images, blocking quality gates

**Files:**
- Modify: `emcip-admin-ui/src/main/resources/application.yml:15`
- Modify: `pom.xml:374` and `pom.xml:385`
- Modify: all 11 service `Dockerfile`s (21 `FROM` lines)
- Create: `.github/workflows/codeql.yml`

**Interfaces:** none — configuration only, no runtime code.

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git pull --ff-only origin main
git checkout -b chore/p1-config-ci-hardening
```

- [ ] **Step 2: Fix the admin-ui actuator exposure (RT2-013 / S-NEW-1)**

Verify first: `sed -n '13,15p' emcip-admin-ui/src/main/resources/application.yml`
Expected: `show-details: always`.

Change line 15 from `      show-details: always` to `      show-details: never`.

Confirm every service now agrees:

Run: `grep -rn 'show-details' --include=application.yml .`
Expected: every match reads `never`.

- [ ] **Step 3: Add Java CodeQL SAST (S-OPEN-2)**

Create `.github/workflows/codeql.yml`:

```yaml
name: CodeQL

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  schedule:
    - cron: '17 3 * * 1'

jobs:
  analyze:
    name: Analyze Java
    runs-on: ubuntu-latest
    permissions:
      actions: read
      contents: read
      security-events: write

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Initialize CodeQL
        uses: github/codeql-action/init@v3
        with:
          languages: java
          queries: security-extended

      - name: Build
        run: mvn -B -DskipTests package

      - name: Perform CodeQL Analysis
        uses: github/codeql-action/analyze@v3
        with:
          category: "/language:java"
```

The schedule uses `17 3 * * 1` rather than a round hour, per the project's cron-offset rule.

- [ ] **Step 4: Pin Docker base images (I2 / RT-034)**

Pick the current patch release of Temurin 21 (check https://hub.docker.com/_/eclipse-temurin tags — at time of writing `21.0.5_11`). Apply consistently:

```bash
grep -rl '^FROM eclipse-temurin:21' emcip-*/Dockerfile \
  | xargs sed -i \
      -e 's|^FROM eclipse-temurin:21-jdk-alpine|FROM eclipse-temurin:21.0.5_11-jdk-alpine|' \
      -e 's|^FROM eclipse-temurin:21-jre-alpine|FROM eclipse-temurin:21.0.5_11-jre-alpine|' \
      -e 's|^FROM eclipse-temurin:21-jdk|FROM eclipse-temurin:21.0.5_11-jdk|' \
      -e 's|^FROM eclipse-temurin:21-jre|FROM eclipse-temurin:21.0.5_11-jre|'
```

Order matters — the `-alpine` patterns must be substituted before the bare ones.

Verify: `grep -h '^FROM eclipse-temurin' emcip-*/Dockerfile | sort -u`
Expected: only pinned tags, no bare `21-jdk` / `21-jre`. There should be 4 distinct values.

- [ ] **Step 5: Verify one image still builds**

Run: `docker build -t emcip-audit-service:pin-test -f emcip-audit-service/Dockerfile .`
Expected: build succeeds. If the tag does not exist, correct the version and redo Step 4.

- [ ] **Step 6: Make quality gates blocking (I4)**

In `pom.xml`, change line 374 and line 385 from `<failOnViolation>false</failOnViolation>` to `<failOnViolation>true</failOnViolation>`.

- [ ] **Step 7: Find out how much debt this surfaces**

Run: `mvn -q checkstyle:check pmd:check 2>&1 | tail -40`
Expected: either BUILD SUCCESS, or a finite list of violations.

**If violations appear**, do not fix them all in this task — that is unbounded scope. Instead:
- Fix them if there are fewer than ~20 and they are mechanical.
- Otherwise revert only the `failOnViolation` change, open a follow-up backlog row under `BACKLOG.md §0` ("I4 — clear Checkstyle/PMD debt before enabling blocking gates", size M, phase P3), and note it in the PR description. Ship the rest of Batch D.

- [ ] **Step 8: Full build**

Run: `mvn -q -DskipTests package`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Format and commit**

```bash
mvn spotless:apply
git add emcip-admin-ui/src/main/resources/application.yml pom.xml .github/workflows/codeql.yml emcip-*/Dockerfile
git commit -m "chore: P1 config and CI hardening

RT2-013/S-NEW-1: admin-ui was the only service exposing full actuator health
details (show-details: always) to unauthenticated requests. Now 'never', matching
the other nine services.

S-OPEN-2: adds Java CodeQL SAST. codeql-action was previously used only to upload
Trivy container SARIF, so application source was never statically analysed.

I2/RT-034: pins all Temurin base images to a patch version for reproducible builds.

I4: makes Checkstyle and PMD blocking so quality regressions fail CI.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 10: Push and open the Batch D PR**

```bash
git push -u origin chore/p1-config-ci-hardening
gh pr create --base main --title "chore: P1 config & CI hardening — actuator, CodeQL, pinned images, blocking gates" --body "ROADMAP P1 batch 1.4. Closes RT2-013/S-NEW-1, S-OPEN-2, I2/RT-034, I4.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

---

## Task 8: Close out P1

**Files:**
- Modify: `docs/superpowers/BACKLOG.md` (§0 status column)

- [ ] **Step 1: Mark shipped items**

Set status `✅` for RT2-003, RT2-004, RT2-002, RT2-016, B1, RT2-008, RT2-009, RT2-013/S-NEW-1, S-OPEN-2, I2/RT-034, I4 — each with its PR number.

- [ ] **Step 2: Record the verified corrections**

Add a note under §0 capturing the findings that proved false, so no one re-implements them:

```markdown
> **Verified corrections (2026-07-22, during P1):**
> - RT2-003's "revocation not triggered on password/role/user change" — FALSE. Already implemented
>   (`UserManagementService:143/177/242`, `AuthController:72`). Only the filter 401 bug was real.
> - RT2-004's "`warmUp()` unauthenticated" — FALSE. It inherited class-level `AI_CONFIG_READ`.
>   Raised to `AI_CONFIG_WRITE` because it triggers LLM work.
> - RT2-002's "schedule `verifyChain()`" follow-up — ALREADY DONE (`AuditChainVerificationJob`).
> - RT2-016 re-estimated XS → M: a blanket DELETE trigger would break `AuditRetentionJob`.
> - TelegramAccountController has **13** endpoints, not 11.
```

- [ ] **Step 3: Commit**

```bash
git checkout -b docs/p1-backlog-status
git add docs/superpowers/BACKLOG.md
git commit -m "docs(backlog): mark P1 items complete and record verified finding corrections

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git push -u origin docs/p1-backlog-status
gh pr create --base main --title "docs(backlog): P1 status + finding corrections" --body "Closes out ROADMAP P1.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

---

## P1 Exit Criteria

- A revoked or demoted principal receives 401; the JWT signature is verified once per request.
- No READ-only principal can create, update or delete Telegram accounts, tenants, or AI configuration.
- Every audit row written from Kafka carries a non-null `integrity_hash` linked to its predecessor.
- `DELETE FROM audit_events` fails from an ordinary session; `AuditRetentionJob` still purges successfully.
- Both previously-gapped Kafka consumers reject records with a missing or mismatched tenant header.
- No service exposes actuator health details; CI runs Java CodeQL; base images are pinned.
