# Telegram Multi-Account Auth Implementation Plan

> **STATUS: FULLY IMPLEMENTED** — All 10 tasks merged in PR #10. Post-merge fix (`9472913`) moved `apiId`/`apiHash` from per-account form fields to server-side env vars (`TELEGRAM_API_ID`, `TELEGRAM_API_HASH`). Task checkboxes below reflect original plan; see spec for final implementation state.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-row `telegram_config` with a proper multi-account pool, with interactive Admin UI auth wizard and per-account TDLib sessions.

**Architecture:** `TelegramAccount` rows stored in admin-api (R2DBC). `TdLibClientManager` in tdlib-adapter holds a `Map<UUID, TdLibClient>`. Admin-api proxies auth commands to tdlib-adapter over HTTP. On startup admin-api initialises active sessions via an `ApplicationRunner`.

**Tech Stack:** Java 21, Spring Boot 4, WebFlux/R2DBC (admin-api), TDLib JNI (tdlib-adapter), Liquibase, React/JSX (admin-ui)

---

## File Map

### emcip-admin-api
| Action | File |
|--------|------|
| DELETE | `entity/TelegramConfig.java` |
| DELETE | `repository/TelegramConfigRepository.java` |
| DELETE | `controller/TelegramController.java` |
| CREATE | `entity/TelegramAccount.java` |
| CREATE | `repository/TelegramAccountRepository.java` |
| CREATE | `controller/TelegramAccountController.java` |
| CREATE | `db/changelog/changes/007-telegram-accounts.xml` |
| MODIFY | `db/changelog/db.changelog-master.xml` |

### emcip-tdlib-adapter
| Action | File |
|--------|------|
| MODIFY | `config/TdLibClient.java` — remove `@Component`/`@PostConstruct`, accept per-account params |
| MODIFY | `config/TdLibProperties.java` — remove per-account fields (phone, apiId, apiHash) |
| CREATE | `config/TdLibClientManager.java` |
| MODIFY | `controller/AuthController.java` — account-scoped endpoints |
| MODIFY | `model/AuthStatusResponse.java` — add `lastError` field |

### emcip-admin-ui
| Action | File |
|--------|------|
| MODIFY | `src/api/telegram.js` |
| MODIFY | `src/pages/Telegram/Telegram.jsx` |

---

## Task 1: Liquibase migration — replace telegram_config with telegram_accounts

**Files:**
- Create: `emcip-admin-api/src/main/resources/db/changelog/changes/007-telegram-accounts.xml`
- Modify: `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Create 007-telegram-accounts.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="007-drop-telegram-config" author="emcip-team">
        <comment>Remove single-row Telegram config in favour of multi-account table</comment>
        <dropTable tableName="telegram_config"/>
    </changeSet>

    <changeSet id="007-create-telegram-accounts" author="emcip-team">
        <comment>Multi-account Telegram credential pool</comment>
        <createTable tableName="telegram_accounts">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="phone_number" type="VARCHAR(50)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="api_id" type="INTEGER">
                <constraints nullable="false"/>
            </column>
            <column name="api_hash" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="display_name" type="VARCHAR(100)"/>
            <column name="session_string" type="TEXT"/>
            <column name="status" type="VARCHAR(30)" defaultValue="UNCONFIGURED">
                <constraints nullable="false"/>
            </column>
            <column name="last_error" type="VARCHAR(500)"/>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Add include to db.changelog-master.xml**

In `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml`, after the `006` include, add:

```xml
    <include file="db/changelog/changes/007-telegram-accounts.xml"/>
```

- [ ] **Step 3: Verify Liquibase parses the changelog**

```bash
cd /home/ben/Development/ecip/emcip-admin-api
mvn liquibase:validate -pl . 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-api/src/main/resources/db/changelog/
git commit -m "feat(admin-api): add telegram_accounts migration, drop telegram_config"
```

---

## Task 2: TelegramAccount entity and repository (R2DBC)

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramAccount.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/TelegramAccountRepository.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramAccountStatus.java`

- [ ] **Step 1: Create TelegramAccountStatus enum**

```java
package io.emcip.admin.api.entity;

public enum TelegramAccountStatus {
    UNCONFIGURED,
    AWAITING_CODE,
    AWAITING_PASSWORD,
    ACTIVE,
    DISCONNECTED
}
```

- [ ] **Step 2: Create TelegramAccount entity**

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

    private TelegramAccountStatus status;

    @Column("last_error")
    private String lastError;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
```

- [ ] **Step 3: Create TelegramAccountRepository**

```java
package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface TelegramAccountRepository extends ReactiveCrudRepository<TelegramAccount, UUID> {
    Flux<TelegramAccount> findByStatus(TelegramAccountStatus status);
}
```

- [ ] **Step 4: Compile to verify**

```bash
cd /home/ben/Development/ecip
mvn compile -pl emcip-admin-api -am -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramAccount.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramAccountStatus.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/repository/TelegramAccountRepository.java
git commit -m "feat(admin-api): TelegramAccount entity and repository (R2DBC)"
```

---

## Task 3: TelegramAccountController (CRUD + auth proxy)

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java`

- [ ] **Step 1: Write the failing test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/TelegramAccountControllerTest.java`:

```java
package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramAccountControllerTest {

    @Mock TelegramAccountRepository repository;
    @Mock WebClient tdlibClient;
    @InjectMocks TelegramAccountController controller;

    @Test
    void listAccounts_returnsMaskedSessionString() {
        UUID id = UUID.randomUUID();
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(id)
                        .phoneNumber("+49123456789")
                        .apiId(12345)
                        .apiHash("abc123")
                        .displayName("Monitor 1")
                        .sessionString("secret-session-data")
                        .status(TelegramAccountStatus.ACTIVE)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        when(repository.findAll()).thenReturn(Flux.just(account));

        StepVerifier.create(controller.listAccounts())
                .assertNext(
                        list -> {
                            assertThat(list).hasSize(1);
                            assertThat(list.get(0).get("sessionStringSet")).isEqualTo(true);
                            assertThat(list.get(0)).doesNotContainKey("sessionString");
                        })
                .verifyComplete();
    }

    @Test
    void createAccount_savesWithUnconfiguredStatus() {
        UUID id = UUID.randomUUID();
        when(repository.save(any()))
                .thenAnswer(
                        inv -> {
                            TelegramAccount a = inv.getArgument(0);
                            a.setId(id);
                            return Mono.just(a);
                        });

        TelegramAccountController.CreateAccountRequest req =
                new TelegramAccountController.CreateAccountRequest(
                        "+49123456789", 12345, "abc123", "Monitor 1");

        StepVerifier.create(controller.createAccount(req))
                .assertNext(
                        a -> {
                            assertThat(a.getStatus()).isEqualTo(TelegramAccountStatus.UNCONFIGURED);
                            assertThat(a.getPhoneNumber()).isEqualTo("+49123456789");
                        })
                .verifyComplete();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -Dtest=TelegramAccountControllerTest -q 2>&1 | tail -20
```

Expected: FAIL with `TelegramAccountController` not found

- [ ] **Step 3: Create TelegramAccountController**

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/telegram/accounts")
public class TelegramAccountController {

    private final TelegramAccountRepository repository;
    private final WebClient tdlibClient;

    public TelegramAccountController(
            TelegramAccountRepository repository,
            @Qualifier("tdlibWebClient") WebClient tdlibClient) {
        this.repository = repository;
        this.tdlibClient = tdlibClient;
    }

    @GetMapping
    public Mono<List<Map<String, Object>>> listAccounts() {
        return repository.findAll().map(TelegramAccountController::toSafeMap).collectList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TelegramAccount> createAccount(@RequestBody CreateAccountRequest req) {
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(UUID.randomUUID())
                        .phoneNumber(req.phoneNumber())
                        .apiId(req.apiId())
                        .apiHash(req.apiHash())
                        .displayName(req.displayName())
                        .status(TelegramAccountStatus.UNCONFIGURED)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        return repository.save(account);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteAccount(@PathVariable UUID id) {
        return repository.deleteById(id);
    }

    @GetMapping("/{id}/status")
    public Mono<Map<String, Object>> getStatus(@PathVariable UUID id) {
        return repository
                .findById(id)
                .flatMap(
                        account ->
                                tdlibClient
                                        .get()
                                        .uri("/api/auth/" + id + "/status")
                                        .retrieve()
                                        .bodyToMono(TdlibStatusResponse.class)
                                        .map(
                                                r -> {
                                                    Map<String, Object> m = new LinkedHashMap<>();
                                                    m.put("id", id.toString());
                                                    m.put("status", r.getStatus());
                                                    m.put("lastError", r.getLastError());
                                                    return m;
                                                })
                                        .onErrorResume(
                                                e -> {
                                                    Map<String, Object> m = new LinkedHashMap<>();
                                                    m.put("id", id.toString());
                                                    m.put("status", account.getStatus().name());
                                                    m.put("lastError", account.getLastError());
                                                    return Mono.just(m);
                                                }))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Account not found: " + id)));
    }

    @PostMapping("/{id}/reconnect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> reconnect(@PathVariable UUID id) {
        return repository
                .findById(id)
                .flatMap(
                        account -> {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("phoneNumber", account.getPhoneNumber());
                            payload.put("apiId", account.getApiId());
                            payload.put("apiHash", account.getApiHash());
                            payload.put("sessionString", account.getSessionString());
                            return tdlibClient
                                    .post()
                                    .uri("/api/auth/" + id + "/initialize")
                                    .bodyValue(payload)
                                    .retrieve()
                                    .bodyToMono(Void.class)
                                    .then(
                                            repository.save(
                                                    update(account, TelegramAccountStatus.AWAITING_CODE, null)))
                                    .thenReturn(Map.<String, Object>of("accepted", true))
                                    .onErrorResume(
                                            e -> {
                                                log.warn("reconnect failed for {}: {}", id, e.getMessage());
                                                return Mono.just(
                                                        Map.of("accepted", false, "reason", e.getMessage()));
                                            });
                        })
                .switchIfEmpty(Mono.just(Map.of("accepted", false, "reason", "Account not found")));
    }

    @PostMapping("/{id}/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> submitCode(@PathVariable UUID id, @RequestBody CodeRequest req) {
        return tdlibClient
                .post()
                .uri("/api/auth/" + id + "/code")
                .bodyValue(Map.of("code", req.code()))
                .retrieve()
                .bodyToMono(Void.class);
    }

    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> submitPassword(@PathVariable UUID id, @RequestBody PasswordRequest req) {
        return tdlibClient
                .post()
                .uri("/api/auth/" + id + "/password")
                .bodyValue(Map.of("password", req.password()))
                .retrieve()
                .bodyToMono(Void.class);
    }

    @PostMapping("/{id}/logout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> logout(@PathVariable UUID id) {
        return tdlibClient
                .post()
                .uri("/api/auth/" + id + "/logout")
                .retrieve()
                .bodyToMono(Void.class)
                .then(
                        repository
                                .findById(id)
                                .flatMap(a -> repository.save(update(a, TelegramAccountStatus.DISCONNECTED, null)))
                                .then());
    }

    private static TelegramAccount update(TelegramAccount a, TelegramAccountStatus status, String lastError) {
        a.setStatus(status);
        a.setLastError(lastError);
        a.setUpdatedAt(Instant.now());
        return a;
    }

    private static Map<String, Object> toSafeMap(TelegramAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId().toString());
        m.put("displayName", a.getDisplayName() != null ? a.getDisplayName() : "");
        m.put("phoneNumber", a.getPhoneNumber());
        m.put("apiId", a.getApiId());
        m.put("status", a.getStatus().name());
        m.put("lastError", a.getLastError());
        m.put("sessionStringSet", a.getSessionString() != null && !a.getSessionString().isEmpty());
        m.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        return m;
    }

    public record CreateAccountRequest(String phoneNumber, Integer apiId, String apiHash, String displayName) {}
    public record CodeRequest(String code) {}
    public record PasswordRequest(String password) {}

    @Data
    public static class TdlibStatusResponse {
        private String status;
        private String lastError;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -Dtest=TelegramAccountControllerTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, `Tests run: 2, Failures: 0`

- [ ] **Step 5: Spotless + commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/controller/TelegramAccountControllerTest.java
git commit -m "feat(admin-api): TelegramAccountController with CRUD and auth proxy"
```

---

## Task 4: Delete old Telegram classes and update SecurityConfig whitelist

**Files:**
- Delete: `emcip-admin-api/.../entity/TelegramConfig.java`
- Delete: `emcip-admin-api/.../repository/TelegramConfigRepository.java`
- Delete: `emcip-admin-api/.../controller/TelegramController.java`
- Verify: `emcip-admin-api/.../security/SecurityConfig.java` — check if `/api/telegram/**` needs explicit allowlist

- [ ] **Step 1: Delete the three files**

```bash
rm /home/ben/Development/ecip/emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramConfig.java
rm /home/ben/Development/ecip/emcip-admin-api/src/main/java/io/emcip/admin/api/repository/TelegramConfigRepository.java
rm /home/ben/Development/ecip/emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramController.java
```

- [ ] **Step 2: Compile to surface broken references**

```bash
cd /home/ben/Development/ecip
mvn compile -pl emcip-admin-api -am -q 2>&1 | grep "ERROR"
```

Fix any remaining import errors (e.g. if `SecurityConfig` or `WebClientConfig` referenced old classes).

- [ ] **Step 3: Run admin-api tests**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add -u emcip-admin-api/src/main/java/io/emcip/admin/api/
git commit -m "chore(admin-api): remove TelegramConfig, TelegramController, TelegramConfigRepository"
```

---

## Task 5: Refactor TdLibClient — remove singleton, accept per-account params

**Files:**
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/TdLibClient.java`
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/TdLibProperties.java`
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/model/AuthStatusResponse.java`

- [ ] **Step 1: Update TdLibProperties — remove per-account fields**

Replace `TdLibProperties.java` content:

```java
package io.emcip.tdlib.adapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tdlib")
public record TdLibProperties(
        String baseDirectory,
        boolean useFileDatabase,
        boolean useChatInfoDatabase,
        boolean useMessageDatabase,
        boolean useSecretChats,
        int logVerbosityLevel) {
    public TdLibProperties {
        baseDirectory = baseDirectory != null ? baseDirectory : "tdlib-db";
        logVerbosityLevel = logVerbosityLevel > 0 ? logVerbosityLevel : 1;
    }
}
```

- [ ] **Step 2: Update application.yml to match**

In `emcip-tdlib-adapter/src/main/resources/application.yml`, replace the `tdlib:` block:

```yaml
tdlib:
  base-directory: ${TDLIB_BASE_DIR:tdlib-db}
  use-file-database: true
  use-chat-info-database: true
  use-message-database: true
  use-secret-chats: false
  log-verbosity-level: 1
```

- [ ] **Step 3: Update AuthStatusResponse to include lastError**

```java
package io.emcip.tdlib.adapter.model;

public record AuthStatusResponse(String status, String lastError) {}
```

- [ ] **Step 4: Refactor TdLibClient — remove @Component, accept per-account params**

Replace `TdLibClient.java` with:

```java
package io.emcip.tdlib.adapter.config;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TdLibClient {

    private static final Logger log = LoggerFactory.getLogger(TdLibClient.class);

    private final UUID accountId;
    private final int apiId;
    private final String apiHash;
    private final String phoneNumber;
    private final String databaseDirectory;
    private final TdLibProperties properties;
    private final BiConsumer<UUID, TdApi.AuthorizationState> authStateCallback;

    private Client client;
    private volatile boolean initialized = false;
    private volatile boolean authorized = false;
    private volatile String lastError = null;

    private final ConcurrentMap<String, Consumer<TdApi.Update>> updateHandlers =
            new ConcurrentHashMap<>();

    public TdLibClient(
            UUID accountId,
            int apiId,
            String apiHash,
            String phoneNumber,
            String databaseDirectory,
            TdLibProperties properties,
            BiConsumer<UUID, TdApi.AuthorizationState> authStateCallback) {
        this.accountId = accountId;
        this.apiId = apiId;
        this.apiHash = apiHash;
        this.phoneNumber = phoneNumber;
        this.databaseDirectory = databaseDirectory;
        this.properties = properties;
        this.authStateCallback = authStateCallback;
    }

    public void initialize() {
        try {
            System.loadLibrary("tdjni");
        } catch (UnsatisfiedLinkError e) {
            log.warn("[{}] TDLib native library not found: {}", accountId, e.getMessage());
        }

        try {
            Client.execute(new TdApi.SetLogVerbosityLevel(properties.logVerbosityLevel()));
        } catch (Client.ExecutionException e) {
            log.warn("[{}] Failed to set TDLib log verbosity: {}", accountId, e.getMessage());
        }

        client = Client.create(this::handleResponse, new DefaultExceptionHandler(accountId), null);
        initialized = true;
        log.info("[{}] TDLib client initialized", accountId);
        sendInitialParameters();
    }

    private void sendInitialParameters() {
        TdApi.SetTdlibParameters params = new TdApi.SetTdlibParameters();
        params.useTestDc = false;
        params.databaseDirectory = databaseDirectory;
        params.filesDirectory = databaseDirectory + "/files";
        params.useFileDatabase = properties.useFileDatabase();
        params.useChatInfoDatabase = properties.useChatInfoDatabase();
        params.useMessageDatabase = properties.useMessageDatabase();
        params.useSecretChats = properties.useSecretChats();
        params.apiId = apiId;
        params.apiHash = apiHash;
        params.systemLanguageCode = "en";
        params.deviceModel = "Desktop";
        params.systemVersion = "Unknown";
        params.applicationVersion = "0.1.0";
        client.send(params, result -> log.debug("[{}] TDLib params sent", accountId));
    }

    public void handleAuthorizationStateUpdate(TdApi.AuthorizationState state) {
        log.info("[{}] Auth state: {}", accountId, state.getClass().getSimpleName());
        switch (state.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> sendInitialParameters();
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                if (phoneNumber != null && !phoneNumber.isBlank()) {
                    setPhoneNumber(phoneNumber);
                }
            }
            case TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                authorized = true;
                lastError = null;
            }
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> authorized = false;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                initialized = false;
                authorized = false;
            }
            default -> log.debug("[{}] Unhandled auth state: {}", accountId, state.getClass().getSimpleName());
        }
        authStateCallback.accept(accountId, state);
    }

    public void setPhoneNumber(String phone) {
        client.send(
                new TdApi.SetAuthenticationPhoneNumber(phone, null),
                result -> {
                    if (result instanceof TdApi.Error error) {
                        lastError = error.message;
                        log.error("[{}] Phone error {}: {}", accountId, error.code, error.message);
                    }
                });
    }

    public void setAuthenticationCode(String code) {
        client.send(
                new TdApi.CheckAuthenticationCode(code),
                result -> {
                    if (result instanceof TdApi.Error error) {
                        lastError = error.message;
                        log.error("[{}] Code error {}: {}", accountId, error.code, error.message);
                    } else {
                        lastError = null;
                    }
                });
    }

    public void setPassword(String password) {
        client.send(
                new TdApi.CheckAuthenticationPassword(password),
                result -> {
                    if (result instanceof TdApi.Error error) {
                        lastError = error.message;
                        log.error("[{}] Password error {}: {}", accountId, error.code, error.message);
                    } else {
                        lastError = null;
                    }
                });
    }

    public void logout() {
        client.send(new TdApi.LogOut(), result -> log.info("[{}] Logout sent", accountId));
    }

    public void registerUpdateHandler(String updateType, Consumer<TdApi.Update> handler) {
        updateHandlers.put(updateType, handler);
    }

    public void unregisterUpdateHandler(String updateType) {
        updateHandlers.remove(updateType);
    }

    public void sendRequest(TdApi.Function<?> query, Client.ResultHandler handler) {
        if (!initialized || client == null) {
            throw new IllegalStateException("TDLib client not initialized for account " + accountId);
        }
        client.send(query, handler);
    }

    public boolean isInitialized() { return initialized; }
    public boolean isAuthorized() { return authorized; }
    public String getLastError() { return lastError; }
    public UUID getAccountId() { return accountId; }

    private void handleResponse(TdApi.Object object) {
        if (object instanceof TdApi.Update update) {
            if (update instanceof TdApi.UpdateAuthorizationState s) {
                handleAuthorizationStateUpdate(s.authorizationState);
            }
            Consumer<TdApi.Update> handler = updateHandlers.get(update.getClass().getSimpleName());
            if (handler != null) {
                try { handler.accept(update); }
                catch (Exception e) { log.error("[{}] Handler error: {}", accountId, e.getMessage(), e); }
            }
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("[{}] Destroying TDLib client", accountId);
        if (authorized) logout();
        if (client != null) client.send(new TdApi.Close(), null);
    }

    private record DefaultExceptionHandler(UUID accountId) implements Client.ExceptionHandler {
        @Override
        public void onException(Throwable e) {
            LoggerFactory.getLogger(TdLibClient.class)
                    .error("[{}] TDLib exception: {}", accountId, e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: Compile tdlib-adapter**

```bash
cd /home/ben/Development/ecip
mvn compile -pl emcip-tdlib-adapter -am -q 2>&1 | tail -20
```

Expected: compile errors in `AuthController` (references old singleton TdLibClient) — that's fine, fixed in next task.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-tdlib-adapter -q
git add emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/TdLibClient.java \
        emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/TdLibProperties.java \
        emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/model/AuthStatusResponse.java \
        emcip-tdlib-adapter/src/main/resources/application.yml
git commit -m "refactor(tdlib-adapter): TdLibClient is now per-account, remove singleton @Component"
```

---

## Task 6: Create TdLibClientManager

**Files:**
- Create: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/TdLibClientManager.java`

- [ ] **Step 1: Write the failing test**

Create `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/config/TdLibClientManagerTest.java`:

```java
package io.emcip.tdlib.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TdLibClientManagerTest {

    private TdLibProperties properties;
    private TdLibClientManager manager;

    @BeforeEach
    void setUp() {
        properties = new TdLibProperties("tdlib-test", true, true, true, false, 1);
        manager = new TdLibClientManager(properties);
    }

    @Test
    void getClient_unknownId_throwsException() {
        assertThatThrownBy(() -> manager.getClient(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No TdLibClient");
    }

    @Test
    void removeClient_nonExistent_doesNotThrow() {
        manager.removeClient(UUID.randomUUID()); // should be a no-op
    }

    @Test
    void hasClient_afterRegister_returnsTrue() {
        UUID id = UUID.randomUUID();
        // Register a stub without actually initialising TDLib
        manager.registerClient(id, stubClient(id));
        assertThat(manager.hasClient(id)).isTrue();
    }

    @Test
    void hasClient_afterRemove_returnsFalse() {
        UUID id = UUID.randomUUID();
        manager.registerClient(id, stubClient(id));
        manager.removeClient(id);
        assertThat(manager.hasClient(id)).isFalse();
    }

    private TdLibClient stubClient(UUID id) {
        // Construct without initialising (no TDLib native library needed)
        return new TdLibClient(id, 0, "hash", "+49000", "tdlib-test/" + id, properties, (a, s) -> {});
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-tdlib-adapter -Dtest=TdLibClientManagerTest -q 2>&1 | tail -20
```

Expected: FAIL — `TdLibClientManager` not found

- [ ] **Step 3: Create TdLibClientManager**

```java
package io.emcip.tdlib.adapter.config;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.drinkless.tdlib.TdApi;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TdLibClientManager {

    private final TdLibProperties properties;
    private final ConcurrentMap<UUID, TdLibClient> clients = new ConcurrentHashMap<>();

    public TdLibClientManager(TdLibProperties properties) {
        this.properties = properties;
    }

    /**
     * Create and initialise a new TdLibClient for the given account. If a client already exists for
     * this account it is destroyed first.
     */
    public TdLibClient createAndInitialize(
            UUID accountId, int apiId, String apiHash, String phoneNumber) {
        removeClient(accountId);
        String dbDir = properties.baseDirectory() + "/" + accountId;
        TdLibClient client =
                new TdLibClient(
                        accountId,
                        apiId,
                        apiHash,
                        phoneNumber,
                        dbDir,
                        properties,
                        this::onAuthStateChange);
        clients.put(accountId, client);
        client.initialize();
        return client;
    }

    /** Register a pre-constructed client (used in tests). */
    public void registerClient(UUID accountId, TdLibClient client) {
        clients.put(accountId, client);
    }

    public TdLibClient getClient(UUID accountId) {
        TdLibClient client = clients.get(accountId);
        if (client == null) {
            throw new IllegalArgumentException("No TdLibClient registered for account " + accountId);
        }
        return client;
    }

    public boolean hasClient(UUID accountId) {
        return clients.containsKey(accountId);
    }

    public void removeClient(UUID accountId) {
        TdLibClient existing = clients.remove(accountId);
        if (existing != null) {
            try {
                existing.destroy();
            } catch (Exception e) {
                log.warn("[{}] Error destroying client: {}", accountId, e.getMessage());
            }
        }
    }

    private void onAuthStateChange(UUID accountId, TdApi.AuthorizationState state) {
        log.info("[{}] Auth state changed to: {}", accountId, state.getClass().getSimpleName());
        // Status persistence is handled by admin-api polling /status
        // Future: push status updates via Kafka or SSE
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-tdlib-adapter -Dtest=TdLibClientManagerTest -q 2>&1 | tail -20
```

Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: Spotless + commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-tdlib-adapter -q
git add emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/TdLibClientManager.java \
        emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/config/TdLibClientManagerTest.java
git commit -m "feat(tdlib-adapter): TdLibClientManager — per-account client lifecycle"
```

---

## Task 7: Refactor AuthController — account-scoped endpoints

**Files:**
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/AuthController.java`
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/model/AuthRequest.java`

- [ ] **Step 1: Update AuthRequest to add Initialize**

```java
package io.emcip.tdlib.adapter.model;

public sealed interface AuthRequest {
    record Initialize(String phoneNumber, Integer apiId, String apiHash, String sessionString)
            implements AuthRequest {}

    record PhoneNumber(String phoneNumber) implements AuthRequest {}

    record Code(String code) implements AuthRequest {}

    record Password(String password) implements AuthRequest {}
}
```

- [ ] **Step 2: Replace AuthController**

```java
package io.emcip.tdlib.adapter.controller;

import io.emcip.tdlib.adapter.config.TdLibClient;
import io.emcip.tdlib.adapter.config.TdLibClientManager;
import io.emcip.tdlib.adapter.model.AuthRequest;
import io.emcip.tdlib.adapter.model.AuthStatusResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TdLibClientManager manager;

    /**
     * Called by admin-api on reconnect or startup session resume. Creates a TdLibClient for the
     * account. If sessionString is provided, TDLib will attempt silent resume.
     */
    @PostMapping("/{accountId}/initialize")
    public Mono<ResponseEntity<Void>> initialize(
            @PathVariable UUID accountId, @RequestBody AuthRequest.Initialize req) {
        return Mono.fromRunnable(
                        () -> {
                            log.info("[{}] Initializing TdLibClient", accountId);
                            manager.createAndInitialize(
                                    accountId, req.apiId(), req.apiHash(), req.phoneNumber());
                        })
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }

    @GetMapping("/{accountId}/status")
    public Mono<ResponseEntity<AuthStatusResponse>> getStatus(@PathVariable UUID accountId) {
        if (!manager.hasClient(accountId)) {
            return Mono.just(
                    ResponseEntity.ok(new AuthStatusResponse("UNCONFIGURED", null)));
        }
        TdLibClient client = manager.getClient(accountId);
        String status =
                client.isAuthorized()
                        ? "ACTIVE"
                        : client.isInitialized() ? "AWAITING_CODE" : "DISCONNECTED";
        return Mono.just(ResponseEntity.ok(new AuthStatusResponse(status, client.getLastError())));
    }

    @PostMapping("/{accountId}/phone")
    public Mono<ResponseEntity<Void>> setPhoneNumber(
            @PathVariable UUID accountId, @RequestBody AuthRequest.PhoneNumber req) {
        return Mono.fromRunnable(
                        () -> manager.getClient(accountId).setPhoneNumber(req.phoneNumber()))
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }

    @PostMapping("/{accountId}/code")
    public Mono<ResponseEntity<Void>> setCode(
            @PathVariable UUID accountId, @RequestBody AuthRequest.Code req) {
        return Mono.fromRunnable(
                        () -> manager.getClient(accountId).setAuthenticationCode(req.code()))
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }

    @PostMapping("/{accountId}/password")
    public Mono<ResponseEntity<Void>> setPassword(
            @PathVariable UUID accountId, @RequestBody AuthRequest.Password req) {
        return Mono.fromRunnable(
                        () -> manager.getClient(accountId).setPassword(req.password()))
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }

    @PostMapping("/{accountId}/logout")
    public Mono<ResponseEntity<Void>> logout(@PathVariable UUID accountId) {
        return Mono.fromRunnable(
                        () -> {
                            manager.getClient(accountId).logout();
                            manager.removeClient(accountId);
                        })
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }
}
```

- [ ] **Step 3: Check for other classes referencing old TdLibClient bean**

```bash
grep -r "TdLibClient\|TdLibHealthIndicator" \
  /home/ben/Development/ecip/emcip-tdlib-adapter/src/main/java \
  --include="*.java" -l
```

If `TdLibHealthIndicator` references the old singleton `TdLibClient`, update it to use `TdLibClientManager`.

- [ ] **Step 4: If TdLibHealthIndicator needs updating**

Read the file and update the dependency from `TdLibClient` to `TdLibClientManager`. Replace the health check logic:

```java
// In TdLibHealthIndicator, replace TdLibClient dependency with TdLibClientManager:
// Old: private final TdLibClient tdLibClient;
// New: private final TdLibClientManager manager;
//
// Health check: report UP if any client is authorized, DOWN if none exist
```

- [ ] **Step 5: Compile and run all tdlib-adapter tests**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-tdlib-adapter -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Check TelegramEventPublisher for old TdLibClient references**

```bash
grep -r "TdLibClient" \
  /home/ben/Development/ecip/emcip-tdlib-adapter/src/main/java \
  --include="*.java" -l
```

If `TelegramEventPublisher` or `TelegramUpdateHandler` holds a `TdLibClient` bean, update to use `TdLibClientManager.getClient(accountId)`.

- [ ] **Step 7: Spotless + commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-tdlib-adapter -q
git add emcip-tdlib-adapter/src/main/java/
git commit -m "refactor(tdlib-adapter): account-scoped AuthController, remove old singleton usage"
```

---

## Task 8: Admin-api startup session resume

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/config/TelegramSessionResumeRunner.java`

- [ ] **Step 1: Create TelegramSessionResumeRunner**

```java
package io.emcip.admin.api.config;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramSessionResumeRunner {

    private final TelegramAccountRepository repository;
    @Qualifier("tdlibWebClient") private final WebClient tdlibClient;

    @EventListener(ApplicationReadyEvent.class)
    public void resumeActiveSessions() {
        repository
                .findByStatus(TelegramAccountStatus.ACTIVE)
                .flatMap(this::initializeAccount)
                .subscribe(
                        id -> log.info("Session resume triggered for account {}", id),
                        err -> log.warn("Session resume error: {}", err.getMessage()));
    }

    private Mono<String> initializeAccount(TelegramAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phoneNumber", account.getPhoneNumber());
        payload.put("apiId", account.getApiId());
        payload.put("apiHash", account.getApiHash());
        payload.put("sessionString", account.getSessionString());

        return tdlibClient
                .post()
                .uri("/api/auth/" + account.getId() + "/initialize")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .thenReturn(account.getId().toString())
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Failed to resume session for account {}: {}",
                                    account.getId(),
                                    e.getMessage());
                            account.setStatus(TelegramAccountStatus.DISCONNECTED);
                            account.setLastError("Session resume failed: " + e.getMessage());
                            account.setUpdatedAt(Instant.now());
                            return repository.save(account).thenReturn(account.getId().toString());
                        });
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/ben/Development/ecip
mvn compile -pl emcip-admin-api -am -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Spotless + commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/config/TelegramSessionResumeRunner.java
git commit -m "feat(admin-api): resume active Telegram sessions on startup"
```

---

## Task 9: Admin UI — replace Telegram page with Accounts page

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/api/telegram.js`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.jsx`

- [ ] **Step 1: Update telegram.js API client**

Replace `emcip-admin-ui/src/main/frontend/src/api/telegram.js`:

```javascript
export function telegramApi(request) {
  return {
    listAccounts: () => request('/api/telegram/accounts'),
    createAccount: body =>
      request('/api/telegram/accounts', { method: 'POST', body: JSON.stringify(body) }),
    deleteAccount: id =>
      request(`/api/telegram/accounts/${id}`, { method: 'DELETE' }),
    getStatus: id => request(`/api/telegram/accounts/${id}/status`),
    reconnect: id =>
      request(`/api/telegram/accounts/${id}/reconnect`, { method: 'POST' }),
    submitCode: (id, code) =>
      request(`/api/telegram/accounts/${id}/code`, {
        method: 'POST',
        body: JSON.stringify({ code }),
      }),
    submitPassword: (id, password) =>
      request(`/api/telegram/accounts/${id}/password`, {
        method: 'POST',
        body: JSON.stringify({ password }),
      }),
    logout: id =>
      request(`/api/telegram/accounts/${id}/logout`, { method: 'POST' }),
  }
}
```

- [ ] **Step 2: Replace Telegram.jsx**

Replace `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { telegramApi } from '../../api/telegram'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './Telegram.module.css'

const STATUS_VARIANT = {
  ACTIVE: 'green',
  AWAITING_CODE: 'yellow',
  AWAITING_PASSWORD: 'yellow',
  UNCONFIGURED: 'gray',
  DISCONNECTED: 'red',
}

export function Telegram() {
  const { token } = useAuth()
  const api = telegramApi(makeRequest(token))

  const [accounts, setAccounts] = useState([])
  const [error, setError] = useState('')
  const [showAdd, setShowAdd] = useState(false)
  const [addForm, setAddForm] = useState({ phoneNumber: '', apiId: '', apiHash: '', displayName: '' })
  const [wizard, setWizard] = useState(null) // { accountId, step: 'code'|'password', error }
  const [codeInput, setCodeInput] = useState('')
  const [passwordInput, setPasswordInput] = useState('')

  const loadAccounts = () =>
    api.listAccounts().then(setAccounts).catch(e => setError(e.message))

  useEffect(() => {
    loadAccounts()
  }, [])

  // Poll status when wizard is open
  useEffect(() => {
    if (!wizard) return
    const interval = setInterval(async () => {
      try {
        const s = await api.getStatus(wizard.accountId)
        if (s.status === 'ACTIVE') {
          setWizard(null)
          loadAccounts()
        } else if (s.status === 'AWAITING_PASSWORD' && wizard.step !== 'password') {
          setWizard(w => ({ ...w, step: 'password' }))
        }
      } catch (_) {}
    }, 2500)
    return () => clearInterval(interval)
  }, [wizard])

  const handleAdd = async () => {
    try {
      await api.createAccount({
        phoneNumber: addForm.phoneNumber,
        apiId: parseInt(addForm.apiId, 10),
        apiHash: addForm.apiHash,
        displayName: addForm.displayName,
      })
      setShowAdd(false)
      setAddForm({ phoneNumber: '', apiId: '', apiHash: '', displayName: '' })
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleReconnect = async id => {
    try {
      const res = await api.reconnect(id)
      if (res.accepted) {
        setWizard({ accountId: id, step: 'code', error: null })
      } else {
        setError(res.reason)
      }
    } catch (e) {
      setError(e.message)
    }
  }

  const handleSubmitCode = async () => {
    try {
      await api.submitCode(wizard.accountId, codeInput)
      setCodeInput('')
    } catch (e) {
      setWizard(w => ({ ...w, error: e.message }))
    }
  }

  const handleSubmitPassword = async () => {
    try {
      await api.submitPassword(wizard.accountId, passwordInput)
      setPasswordInput('')
    } catch (e) {
      setWizard(w => ({ ...w, error: e.message }))
    }
  }

  const handleLogout = async id => {
    try {
      await api.logout(id)
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleDelete = async id => {
    try {
      await api.deleteAccount(id)
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h2>Telegram Accounts</h2>
        <Button onClick={() => setShowAdd(true)}>Add Account</Button>
      </div>

      {error && <p className={styles.error} role="alert">{error}</p>}

      <table className={styles.table}>
        <thead>
          <tr>
            <th>Name</th>
            <th>Phone</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {accounts.map(a => (
            <tr key={a.id}>
              <td>{a.displayName || '—'}</td>
              <td>{a.phoneNumber}</td>
              <td>
                <Badge variant={STATUS_VARIANT[a.status] ?? 'gray'} title={a.lastError ?? ''}>
                  {a.status}
                </Badge>
              </td>
              <td className={styles.actions}>
                <Button variant="secondary" onClick={() => handleReconnect(a.id)}>Auth</Button>
                <Button variant="secondary" onClick={() => handleLogout(a.id)}>Logout</Button>
                <Button variant="danger" onClick={() => handleDelete(a.id)}>Delete</Button>
              </td>
            </tr>
          ))}
          {accounts.length === 0 && (
            <tr><td colSpan={4} className={styles.empty}>No accounts configured</td></tr>
          )}
        </tbody>
      </table>

      {/* Add Account Modal */}
      {showAdd && (
        <Modal title="Add Telegram Account" onClose={() => setShowAdd(false)}>
          <div className={styles.form}>
            {[
              { label: 'Display Name', key: 'displayName', type: 'text', placeholder: 'Monitor account 1' },
              { label: 'Phone Number', key: 'phoneNumber', type: 'text', placeholder: '+49123456789' },
              { label: 'API ID', key: 'apiId', type: 'number', placeholder: '12345' },
              { label: 'API Hash', key: 'apiHash', type: 'text', placeholder: 'abc123...' },
            ].map(({ label, key, type, placeholder }) => (
              <div key={key}>
                <label className={styles.label}>{label}</label>
                <input
                  type={type}
                  className={styles.input}
                  placeholder={placeholder}
                  value={addForm[key]}
                  onChange={e => setAddForm(f => ({ ...f, [key]: e.target.value }))}
                />
              </div>
            ))}
            <div className={styles.modalActions}>
              <Button onClick={handleAdd}>Save</Button>
              <Button variant="secondary" onClick={() => setShowAdd(false)}>Cancel</Button>
            </div>
          </div>
        </Modal>
      )}

      {/* Auth Wizard Modal */}
      {wizard && (
        <Modal title="Authenticate Account" onClose={() => setWizard(null)}>
          <div className={styles.form}>
            {wizard.error && <p className={styles.error}>{wizard.error}</p>}
            {wizard.step === 'code' && (
              <>
                <p>Enter the verification code sent to your Telegram app.</p>
                <input
                  type="text"
                  className={styles.input}
                  placeholder="12345"
                  value={codeInput}
                  onChange={e => setCodeInput(e.target.value)}
                  autoFocus
                />
                <div className={styles.modalActions}>
                  <Button onClick={handleSubmitCode}>Submit Code</Button>
                </div>
              </>
            )}
            {wizard.step === 'password' && (
              <>
                <p>Enter your 2FA password.</p>
                <input
                  type="password"
                  className={styles.input}
                  value={passwordInput}
                  onChange={e => setPasswordInput(e.target.value)}
                  autoFocus
                />
                <div className={styles.modalActions}>
                  <Button onClick={handleSubmitPassword}>Submit Password</Button>
                </div>
              </>
            )}
          </div>
        </Modal>
      )}
    </div>
  )
}
```

- [ ] **Step 3: Check if Modal component exists**

```bash
ls /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend/src/components/Modal/
```

If it doesn't exist, create `Modal.jsx`:

```jsx
import styles from './Modal.module.css'

export function Modal({ title, children, onClose }) {
  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={e => e.stopPropagation()}>
        <div className={styles.header}>
          <h3>{title}</h3>
          <button className={styles.close} onClick={onClose}>✕</button>
        </div>
        <div className={styles.body}>{children}</div>
      </div>
    </div>
  )
}
```

And `Modal.module.css`:

```css
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.modal {
  background: var(--color-surface, #1e1e2e);
  border-radius: 8px;
  min-width: 420px;
  max-width: 600px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--color-border, #313244);
}

.header h3 {
  margin: 0;
  font-size: 1rem;
}

.close {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--color-text-muted, #a6adc8);
  font-size: 1rem;
  padding: 0.25rem;
}

.body {
  padding: 1.25rem;
}
```

- [ ] **Step 4: Check Telegram.module.css has required classes**

Read the existing `Telegram.module.css` and add any missing classes: `header`, `table`, `actions`, `empty`, `form`, `label`, `input`, `modalActions`, `error`.

- [ ] **Step 5: Build the frontend**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui
mvn frontend:npm@build -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS` with no JSX errors.

- [ ] **Step 6: Spotless + commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-ui -q 2>&1 | tail -5
git add emcip-admin-ui/src/main/frontend/src/
git commit -m "feat(admin-ui): multi-account Telegram page with auth wizard"
```

---

## Task 10: Final integration verification

- [ ] **Step 1: Full build**

```bash
cd /home/ben/Development/ecip
mvn clean install -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`

- [ ] **Step 2: Run all tests**

```bash
cd /home/ben/Development/ecip
mvn test -q 2>&1 | tail -20
```

Expected: all tests pass, no failures.

- [ ] **Step 3: Spotless final check**

```bash
cd /home/ben/Development/ecip
mvn spotless:check -q 2>&1 | tail -10
```

Expected: `0 were changed to be clean`

- [ ] **Step 4: Final commit if any formatting applied**

If spotless changed anything:

```bash
git add -A
git commit -m "chore: spotless formatting pass after multi-account Telegram implementation"
```
