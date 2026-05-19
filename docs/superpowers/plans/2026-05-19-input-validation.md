# SC2: Input Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Jakarta Bean Validation (`@Valid` + constraint annotations) to all user-facing request bodies in `emcip-admin-api`, returning structured 400 errors with per-field messages instead of 500s or silently accepting bad data.

**Architecture:** Add `spring-boot-starter-validation` once; extend `GlobalExceptionHandler` with a `WebExchangeBindException` handler that returns `ProblemDetail` with an `errors` map; annotate each request type (records, DTOs, entities used as request bodies) with constraint annotations; add `@Valid` to the matching `@RequestBody` parameters. Proxy controllers (ModerationRule, PolicyRule, AIProxy, Audit) are out of scope — they forward raw JSON to downstream services which own their own validation.

**Tech Stack:** Java 21, Spring Boot 4, Spring WebFlux, Jakarta Validation (Hibernate Validator), JUnit 5 + `WebTestClient`

---

## Context

### What is broken today

- `POST /api/auth/token` with a blank username reaches the database.
- `POST /api/telegram/accounts` with `"phoneNumber": "not-a-phone"` is stored.
- `PATCH /api/flags/{id}/status` with no `status` field throws `IllegalArgumentException` from the service — the current `GlobalExceptionHandler` maps that to 400 correctly, but this is manual null-checking scattered in the service layer.
- `POST /api/groups` with a null `name` reaches R2DBC and throws a DB constraint error (500 response leaking database internals).

### WebFlux validation mechanics

- `@Valid` on a `@RequestBody` parameter causes Spring WebFlux to run the validator after deserialization.
- On failure it throws `WebExchangeBindException` (a subtype of `MethodArgumentNotValidException`).
- `WebExchangeBindException.getBindingResult().getFieldErrors()` gives per-field messages.
- `WebTestClient.bindToController(controller)` respects `@Valid` on `@RequestBody` — the AOP proxy for `@Validated` on the class is NOT needed for `@RequestBody` validation, only for path/query param constraints.

### Out of scope

- `ModerationRuleController` (admin-api) — passes raw JSON to `moderation-service`; that service owns its own validation.
- `PolicyRuleController` — same, proxy to `policy-engine`.
- `AIProxyController` — raw `String` body forwarded to `llm-orchestrator`.
- `AuditController` — read-only query params, no body.
- `@RequestParam size` upper-bound enforcement — tracked separately as SC6 (pagination).

### Run tests

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
```

### Apply Spotless

```bash
mvn spotless:apply -pl emcip-admin-api
```

---

## File Structure

**Modify:**

| File | Change |
|------|--------|
| `emcip-admin-api/pom.xml` | Add `spring-boot-starter-validation` dependency |
| `emcip-admin-api/.../config/GlobalExceptionHandler.java` | Add `WebExchangeBindException` handler |
| `emcip-admin-api/.../config/GlobalExceptionHandlerTest.java` | Add test for the new handler |
| `emcip-admin-api/.../controller/AuthController.java` | Annotate `AuthRequest` record, add `@Valid` |
| `emcip-admin-api/.../controller/AuthControllerTest.java` | Add validation tests |
| `emcip-admin-api/.../entity/GroupProfile.java` | Add constraint annotations |
| `emcip-admin-api/.../entity/Tenant.java` | Add constraint annotations |
| `emcip-admin-api/.../controller/GroupProfileController.java` | Add `@Valid` to `@RequestBody` |
| `emcip-admin-api/.../controller/GroupProfileControllerTest.java` | Add validation tests |
| `emcip-admin-api/.../controller/TenantController.java` | Add `@Valid` to `@RequestBody` |
| `emcip-admin-api/.../controller/TenantControllerTest.java` | Add validation tests |
| `emcip-admin-api/.../controller/TelegramAccountController.java` | Annotate request records, add `@Valid` |
| `emcip-admin-api/.../controller/TelegramAccountControllerTest.java` | Add validation tests |
| `emcip-admin-api/.../dto/SimulateMessageRequest.java` | Add constraint annotations |
| `emcip-admin-api/.../controller/SimulateController.java` | Add `@Valid` to `@RequestBody` |
| `emcip-admin-api/.../controller/SimulateControllerTest.java` | Add validation test |
| `emcip-admin-api/.../service/FlagService.java` | Change signature: take `String status` directly |
| `emcip-admin-api/.../controller/FlagController.java` | Use `StatusUpdateRequest`, add `@Valid` |
| `emcip-admin-api/.../controller/FlagControllerTest.java` | Fix test expecting 5xx → 400 |

**Create:**

| File | Purpose |
|------|---------|
| `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/StatusUpdateRequest.java` | Typed DTO replacing `Map<String,String>` for flag status update |

---

## Task 1: Validation foundation — dependency + GlobalExceptionHandler

**Files:**
- Modify: `emcip-admin-api/pom.xml`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/config/GlobalExceptionHandler.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/config/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Add `spring-boot-starter-validation` to `emcip-admin-api/pom.xml`**

Read `pom.xml` first. Then insert after the `spring-boot-starter-data-r2dbc` block:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
```

- [ ] **Step 2: Write failing test for validation error handler**

Read `GlobalExceptionHandlerTest.java` first. Then add this test at the end of the class (before the closing `}`):

```java
    @Test
    void handleValidation_returns400WithFieldErrors() {
        @org.springframework.validation.annotation.Validated
        @org.springframework.web.bind.annotation.RestController
        class TestController {
            @org.springframework.web.bind.annotation.PostMapping("/test-validation")
            public reactor.core.publisher.Mono<String> handle(
                    @jakarta.validation.Valid
                            @org.springframework.web.bind.annotation.RequestBody
                            TestRequest body) {
                return reactor.core.publisher.Mono.just("ok");
            }

            public record TestRequest(@jakarta.validation.constraints.NotBlank String value) {}
        }

        org.springframework.test.web.reactive.server.WebTestClient client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new TestController())
                        .controllerAdvice(new GlobalExceptionHandler())
                        .build();

        client.post()
                .uri("/test-validation")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("value", ""))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.errors.value").isNotEmpty();
    }
```

- [ ] **Step 3: Run test to confirm it fails**

```bash
mvn test -pl emcip-admin-api -Dtest=GlobalExceptionHandlerTest#handleValidation_returns400WithFieldErrors -q 2>&1 | tail -5
```

Expected: FAIL — `WebExchangeBindException` is not yet handled, so the test expects 400 but gets 500.

- [ ] **Step 4: Add `WebExchangeBindException` handler to `GlobalExceptionHandler`**

Read `GlobalExceptionHandler.java` first. Then add these imports and the new handler method. The full file after change:

```java
package io.emcip.admin.api.config;

import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleValidation(WebExchangeBindException ex) {
        Map<String, String> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .collect(
                                Collectors.toMap(
                                        FieldError::getField,
                                        fe ->
                                                fe.getDefaultMessage() != null
                                                        ? fe.getDefaultMessage()
                                                        : "invalid",
                                        (a, b) -> a));
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", errors);
        return Mono.just(ResponseEntity.badRequest().body(problem));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        return Mono.just(ResponseEntity.status(ex.getStatusCode()).body(problem));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Bad request: {}", ex.getMessage());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        return Mono.just(ResponseEntity.badRequest().body(problem));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ProblemDetail>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        return Mono.just(ResponseEntity.internalServerError().body(problem));
    }
}
```

- [ ] **Step 5: Run all GlobalExceptionHandler tests**

```bash
mvn test -pl emcip-admin-api -Dtest=GlobalExceptionHandlerTest -q 2>&1 | tail -5
```

Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 6: Run full test suite to confirm nothing broken**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 7: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "feat(admin-api): add spring-boot-starter-validation + WebExchangeBindException handler"
```

---

## Task 2: Validate `AuthRequest`

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuthController.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/AuthControllerTest.java`

- [ ] **Step 1: Write failing validation tests for AuthController**

Read `AuthControllerTest.java` first. Then add these two tests at the end (before the closing `}`):

```java
    @Test
    void token_blankUsername_returns400() {
        webTestClient
                .post()
                .uri("/api/auth/token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("username", "", "password", "validpassword"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void token_blankPassword_returns400() {
        webTestClient
                .post()
                .uri("/api/auth/token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("username", "admin", "password", ""))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
```

Note: check how `AuthControllerTest` sets up `webTestClient` — it needs `.controllerAdvice(new GlobalExceptionHandler())` to produce 400 instead of 500. If it doesn't have it yet, add it to the `@BeforeEach` setup.

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl emcip-admin-api -Dtest=AuthControllerTest -q 2>&1 | tail -5
```

Expected: the two new tests FAIL — blank inputs currently pass through.

- [ ] **Step 3: Annotate `AuthRequest` and add `@Valid` in `AuthController`**

Read `AuthController.java`. Replace the full file with:

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    public Mono<ResponseEntity<TokenResponse>> token(@Valid @RequestBody AuthRequest request) {
        return authService
                .authenticate(request.username(), request.password())
                .map(ResponseEntity::ok);
    }

    public record AuthRequest(
            @NotBlank(message = "username is required") String username,
            @NotBlank(message = "password is required") String password) {}
}
```

- [ ] **Step 4: Run AuthController tests**

```bash
mvn test -pl emcip-admin-api -Dtest=AuthControllerTest -q 2>&1 | tail -5
```

Expected: all tests pass, including the two new ones.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "feat(admin-api): validate AuthRequest — username and password required"
```

---

## Task 3: Validate `GroupProfile` and `Tenant` entities

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/GroupProfile.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/Tenant.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/GroupProfileController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TenantController.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/GroupProfileControllerTest.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/TenantControllerTest.java`

- [ ] **Step 1: Write failing validation tests for GroupProfileController**

Read `GroupProfileControllerTest.java` first. Add these tests (before the closing `}`):

```java
    @Test
    void createGroup_blankName_returns400() {
        GroupProfile profile = new GroupProfile();
        profile.setTelegramChatId(-1001234567890L);
        profile.setName("");

        webTestClient
                .post()
                .uri("/api/groups")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(profile)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void createGroup_invalidModerationLevel_returns400() {
        GroupProfile profile = new GroupProfile();
        profile.setTelegramChatId(-1001234567890L);
        profile.setName("Test Group");
        profile.setModerationLevel("EXTREME");

        webTestClient
                .post()
                .uri("/api/groups")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(profile)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
```

Also check that the `GroupProfileControllerTest` `webTestClient` is set up with `.controllerAdvice(new GlobalExceptionHandler())`. If not, add it.

- [ ] **Step 2: Write failing validation test for TenantController**

Read `TenantControllerTest.java` first. Add this test:

```java
    @Test
    void createTenant_blankName_returns400() {
        Tenant tenant = new Tenant();
        tenant.setName("");

        webTestClient
                .post()
                .uri("/api/tenants")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(tenant)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
```

- [ ] **Step 3: Run tests to confirm they fail**

```bash
mvn test -pl emcip-admin-api -Dtest="GroupProfileControllerTest+TenantControllerTest" -q 2>&1 | tail -5
```

Expected: new tests FAIL — blank inputs accepted today.

- [ ] **Step 4: Add constraint annotations to `GroupProfile`**

Read `GroupProfile.java`. Replace the full file with:

```java
package io.emcip.admin.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

@Schema(description = "Configuration profile for a watched Telegram group")
@Table("group_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupProfile {

    @Schema(description = "Internal profile ID")
    @Id
    private Long id;

    @Schema(description = "Telegram chat ID this profile applies to", example = "-1001234567890")
    @Column("telegram_chat_id")
    private Long telegramChatId;

    @NotBlank(message = "name is required")
    @Size(max = 255, message = "name must be 255 characters or fewer")
    @Schema(description = "Display name for the group", example = "My Community")
    private String name;

    @Size(max = 500, message = "description must be 500 characters or fewer")
    @Schema(description = "Optional description")
    private String description;

    @Schema(
            description = "JSON array of enabled rule IDs",
            example = "[\"spam-block\",\"greeting-respond\"]")
    @Column("rules_enabled")
    private String rulesEnabled;

    @Schema(description = "Whether the bot should auto-respond in this group")
    @Column("auto_respond")
    private boolean autoRespond;

    @Pattern(
            regexp = "^(LOW|MEDIUM|HIGH)$",
            message = "moderationLevel must be LOW, MEDIUM, or HIGH")
    @Schema(
            description = "Moderation aggressiveness level",
            example = "MEDIUM",
            allowableValues = {"LOW", "MEDIUM", "HIGH"})
    @Column("moderation_level")
    private String moderationLevel;

    @Size(max = 500, message = "welcomeMessage must be 500 characters or fewer")
    @Schema(
            description = "Message sent when a new member joins",
            example = "Welcome to our community!")
    @Column("welcome_message")
    private String welcomeMessage;

    @Schema(description = "Tenant this group belongs to")
    @Column("tenant_id")
    private UUID tenantId;

    @Schema(description = "Creation timestamp (UTC)")
    @Column("created_at")
    private Instant createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    @Column("updated_at")
    private Instant updatedAt;
}
```

- [ ] **Step 5: Add constraint annotations to `Tenant`**

Read `Tenant.java`. Replace the full file with:

```java
package io.emcip.admin.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

@Schema(description = "Tenant configuration")
@Table("tenants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    @Schema(description = "Unique tenant ID (UUID)")
    @Id
    private UUID id;

    @NotBlank(message = "name is required")
    @Size(max = 255, message = "name must be 255 characters or fewer")
    @Schema(description = "Tenant display name", example = "Acme Corp")
    @Column("name")
    private String name;

    @Size(max = 1000, message = "description must be 1000 characters or fewer")
    @Schema(description = "Optional description of this tenant")
    @Column("description")
    private String description;

    @Size(max = 100, message = "llmModelOverride must be 100 characters or fewer")
    @Schema(
            description = "Override the default LLM model key for this tenant",
            example = "gpt4-turbo")
    @Column("llm_model_override")
    private String llmModelOverride;

    @Schema(description = "Creation timestamp (UTC)")
    @Column("created_at")
    private Instant createdAt;
}
```

- [ ] **Step 6: Add `@Valid` to `GroupProfileController` and `TenantController`**

Read `GroupProfileController.java`. Add `@Valid` to the `create` and `update` method `@RequestBody` params. Also add `import jakarta.validation.Valid;`.

For `create`:
```java
public Mono<GroupProfile> createGroup(@Valid @RequestBody GroupProfile profile) {
```

For `update`:
```java
public Mono<GroupProfile> updateGroup(
        @PathVariable long chatId, @Valid @RequestBody GroupProfile patch) {
```

Read `TenantController.java`. Add `@Valid` to the `create` method:
```java
public Mono<Tenant> createTenant(@Valid @RequestBody Tenant tenant) {
```

Also add `import jakarta.validation.Valid;` to both controllers.

- [ ] **Step 7: Run tests**

```bash
mvn test -pl emcip-admin-api -Dtest="GroupProfileControllerTest+TenantControllerTest" -q 2>&1 | tail -5
```

Expected: all pass, including the new validation tests.

- [ ] **Step 8: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "feat(admin-api): add validation to GroupProfile and Tenant entities"
```

---

## Task 4: Validate `TelegramAccountController` request records

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/TelegramAccountControllerTest.java`

- [ ] **Step 1: Write failing validation tests**

Read `TelegramAccountControllerTest.java` first. Add these tests (before the closing `}`):

```java
    @Test
    void createAccount_invalidPhoneNumber_returns400() {
        webTestClient
                .post()
                .uri("/api/telegram/accounts")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("phoneNumber", "not-a-phone", "displayName", "Test"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void createAccount_blankPhoneNumber_returns400() {
        webTestClient
                .post()
                .uri("/api/telegram/accounts")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("phoneNumber", "", "displayName", "Test"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void submitCode_invalidCode_returns400() {
        webTestClient
                .post()
                .uri("/api/telegram/accounts/" + java.util.UUID.randomUUID() + "/code")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("code", ""))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
```

Also check that `TelegramAccountControllerTest` `webTestClient` is set up with `.controllerAdvice(new GlobalExceptionHandler())`. If not, add it.

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl emcip-admin-api -Dtest=TelegramAccountControllerTest -q 2>&1 | tail -5
```

Expected: new tests FAIL.

- [ ] **Step 3: Annotate request records in `TelegramAccountController`**

Read `TelegramAccountController.java`. Add these imports:
```java
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
```

Add `@Valid` to the three `@RequestBody` parameters:
```java
public Mono<Map<String, Object>> createAccount(@Valid @RequestBody CreateAccountRequest req)
public Mono<Void> submitCode(@PathVariable("id") UUID id, @Valid @RequestBody CodeRequest req)
public Mono<Void> submitPassword(@PathVariable("id") UUID id, @Valid @RequestBody PasswordRequest req)
public Mono<Map<String, Object>> watchGroup(@PathVariable("id") UUID accountId, @Valid @RequestBody WatchRequest req)
```

Replace the four inner record definitions at the bottom of the class with:

```java
    @Schema(description = "Request to register a new Telegram account")
    public record CreateAccountRequest(
            @NotBlank(message = "phoneNumber is required")
                    @Pattern(
                            regexp = "^\\+\\d{10,15}$",
                            message = "phoneNumber must be in international format, e.g. +491234567890")
                    @Schema(
                            description = "Phone number in international format",
                            example = "+491234567890")
                    String phoneNumber,
            @Size(max = 100, message = "displayName must be 100 characters or fewer")
                    @Schema(
                            description = "Human-readable label for this account",
                            example = "Main bot")
                    String displayName) {}

    @Schema(description = "Telegram authentication code sent to the phone")
    public record CodeRequest(
            @NotBlank(message = "code is required")
                    @Pattern(
                            regexp = "^\\d{4,7}$",
                            message = "code must be 4–7 digits")
                    @Schema(
                            description = "Verification code received via Telegram",
                            example = "12345")
                    String code) {}

    @Schema(description = "Two-factor authentication password")
    public record PasswordRequest(
            @NotBlank(message = "password is required")
                    @Schema(description = "2FA password for the Telegram account")
                    String password) {}

    @Schema(description = "Request to start watching a Telegram group")
    public record WatchRequest(
            @Schema(description = "Telegram chat ID to watch", example = "-1001234567890")
                    long chatId,
            @Size(max = 255, message = "title must be 255 characters or fewer")
                    @Schema(description = "Display title for the group", example = "My Community")
                    String title) {}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -pl emcip-admin-api -Dtest=TelegramAccountControllerTest -q 2>&1 | tail -5
```

Expected: all pass.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "feat(admin-api): validate TelegramAccount request records — phone format, code digits"
```

---

## Task 5: Validate `SimulateMessageRequest` and fix `FlagController` status update

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/SimulateMessageRequest.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/SimulateController.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/SimulateControllerTest.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/StatusUpdateRequest.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/FlagControllerTest.java`

- [ ] **Step 1: Write failing test for SimulateController**

Read `SimulateControllerTest.java` first. Add this test (before the closing `}`):

```java
    @Test
    void simulate_nullChatId_returns400() {
        webTestClient
                .post()
                .uri("/api/simulate/message")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("text", "hello"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
```

Also ensure the `SimulateControllerTest` `webTestClient` includes `.controllerAdvice(new GlobalExceptionHandler())`.

- [ ] **Step 2: Run test to confirm it fails**

```bash
mvn test -pl emcip-admin-api -Dtest=SimulateControllerTest#simulate_nullChatId_returns400 -q 2>&1 | tail -5
```

Expected: FAIL — null chatId currently accepted.

- [ ] **Step 3: Annotate `SimulateMessageRequest`**

Read `SimulateMessageRequest.java`. Replace the full file with:

```java
package io.emcip.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Request to inject a simulated Telegram message into the pipeline")
@Data
public class SimulateMessageRequest {

    @NotNull(message = "chatId is required")
    @Schema(description = "Telegram chat ID", example = "-1001234567890")
    private Long chatId;

    @Size(max = 100, message = "senderId must be 100 characters or fewer")
    @Schema(description = "Sender identifier", example = "user-42")
    private String senderId;

    @Pattern(regexp = "^(USER|BOT)$", message = "senderType must be USER or BOT")
    @Schema(
            description = "Sender type",
            example = "USER",
            allowableValues = {"USER", "BOT"})
    private String senderType;

    @Size(max = 4096, message = "text must be 4096 characters or fewer")
    @Schema(description = "Message text to classify and process", example = "Hello everyone!")
    private String text;

    @Schema(description = "Optional Telegram message ID override")
    private Long telegramMessageId;
}
```

- [ ] **Step 4: Add `@Valid` to `SimulateController`**

Read `SimulateController.java`. Add `import jakarta.validation.Valid;` and add `@Valid` to the `@RequestBody` parameter:

```java
public Mono<SimulateResult> simulate(@Valid @RequestBody SimulateMessageRequest request) {
```

- [ ] **Step 5: Run SimulateController tests**

```bash
mvn test -pl emcip-admin-api -Dtest=SimulateControllerTest -q 2>&1 | tail -5
```

Expected: all pass including the new null-chatId test.

- [ ] **Step 6: Create `StatusUpdateRequest` DTO**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/StatusUpdateRequest.java`:

```java
package io.emcip.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to update the review status of a policy flag")
public record StatusUpdateRequest(
        @NotBlank(message = "status is required")
                @Schema(description = "New status value", example = "REVIEWED")
                String status) {}
```

- [ ] **Step 7: Update `FlagService.updateStatus` to take `String status` directly**

Read `FlagService.java`. Replace the full file with:

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.client.PolicyEngineClient;
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

    public Mono<Void> updateStatus(String id, String status) {
        return policyEngineClient.updateDecisionStatus(id, status);
    }
}
```

The manual `null`/blank check is removed — `@Valid` on the request body handles this before `FlagService` is ever called.

- [ ] **Step 8: Update `FlagController` to use `StatusUpdateRequest` with `@Valid`**

Read `FlagController.java`. Replace the full file with:

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.StatusUpdateRequest;
import io.emcip.admin.api.service.FlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@Tag(name = "Flags", description = "View and action moderation flags from the policy engine")
public class FlagController {

    private final FlagService flagService;

    @Operation(summary = "List recent policy flags")
    @GetMapping
    public Flux<JsonNode> getFlags(
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "decision", required = false) String decision) {
        return flagService.listFlags(size, decision);
    }

    @Operation(summary = "Update the status of a policy flag")
    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> updateStatus(
            @PathVariable String id, @Valid @RequestBody StatusUpdateRequest req) {
        return flagService.updateStatus(id, req.status());
    }
}
```

- [ ] **Step 9: Fix `FlagControllerTest` — update stale test and add validation test**

Read `FlagControllerTest.java`. The test suite needs two changes:

1. `updateStatus_returns204` — update to use `StatusUpdateRequest` serialization format (it already uses `Map.of("status", "REVIEWED")` which Jackson will deserialize into `StatusUpdateRequest("REVIEWED")` correctly, but the `when(flagService.updateStatus(...))` mock signature must change to `String status`):

```java
    @Test
    void updateStatus_returns204() {
        when(flagService.updateStatus("flag-1", "REVIEWED")).thenReturn(Mono.empty());
        webTestClient
                .patch()
                .uri("/api/flags/flag-1/status")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "REVIEWED"))
                .exchange()
                .expectStatus()
                .isNoContent();
    }
```

2. Replace `updateStatus_missingStatus_returnsError` (currently expects 5xx) with a proper 400 test using validation. The service is no longer called when validation rejects:

```java
    @Test
    void updateStatus_blankStatus_returns400() {
        webTestClient
                .patch()
                .uri("/api/flags/flag-1/status")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", ""))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
```

Also ensure `FlagControllerTest` `webTestClient` is set up with `.controllerAdvice(new GlobalExceptionHandler())`.

The full `setUp` should look like:
```java
    @BeforeEach
    void setUp() {
        webTestClient =
                WebTestClient.bindToController(new FlagController(flagService))
                        .controllerAdvice(new GlobalExceptionHandler())
                        .build();
    }
```

- [ ] **Step 10: Run all admin-api tests**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 11: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "feat(admin-api): validate SimulateMessageRequest; replace Map status body with StatusUpdateRequest DTO"
```

---

## Self-Review

### Spec coverage

| Requirement (from REVIEW-2026-05-18.md §3.1 A2, S12) | Task |
|------|------|
| `spring-boot-starter-validation` added | Task 1 ✅ |
| Validation errors return 400 with field details | Task 1 ✅ |
| `AuthRequest` — both fields required | Task 2 ✅ |
| `GroupProfile.name` required, `moderationLevel` enum-validated | Task 3 ✅ |
| `Tenant.name` required | Task 3 ✅ |
| `CreateAccountRequest.phoneNumber` — required, international format | Task 4 ✅ |
| `CodeRequest.code` — required, digits only | Task 4 ✅ |
| `PasswordRequest.password` — required | Task 4 ✅ |
| `SimulateMessageRequest.chatId` — required | Task 5 ✅ |
| `FlagController` status — proper DTO, manual validation removed | Task 5 ✅ |

### Out of scope (acknowledged)

- Proxy controllers (ModerationRule, PolicyRule, AIProxy) — pass-through, upstream owns validation
- `@RequestParam size` upper-bound — tracked as SC6
- `GroupProfile.telegramChatId` required on POST — would need validation groups (separate DTO), over-engineered for this task

### Placeholder scan

None found — all steps contain complete code.

### Type consistency

- `FlagService.updateStatus(String id, String status)` — used consistently in Tasks 5 Steps 7, 8, 9. ✅
- `StatusUpdateRequest.status()` accessor (record accessor method) — used in `FlagController`. ✅
- All `@Valid` annotations use `jakarta.validation.Valid`. ✅
