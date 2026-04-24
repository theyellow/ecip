# Admin UI Phase C — New Feature Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Telegram config page, AI/LLM config page, and tenant dropdowns to Groups and PolicyRules — all wired through admin-api as an API Gateway.

**Architecture:** admin-api proxies Telegram status/reconnect to tdlib-adapter (port 9080) and AI config CRUD to llm-orchestrator (port 9084) via `WebClient`. Telegram credentials are stored in a new `telegram_config` table (single-row). Group profiles get a `tenant_id` FK column; PolicyRule already has the column in DB, just needs the entity field added.

**Tech Stack:** Java 21, Spring Boot 4, Spring WebFlux + WebClient, R2DBC, Liquibase, React 18, Vitest + Testing Library, CSS Modules

---

## File Structure

**New backend files:**
- `emcip-admin-api/src/main/resources/db/changelog/changes/006-telegram-config-and-group-tenant.xml` — Liquibase: telegram_config table + tenant_id on group_profiles
- `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramConfig.java` — R2DBC entity for telegram credentials
- `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/TelegramConfigRepository.java` — reactive repo
- `emcip-admin-api/src/main/java/io/emcip/admin/api/config/WebClientConfig.java` — WebClient beans for tdlib + orchestrator
- `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramController.java` — GET/PUT config, GET status proxy, POST reconnect proxy
- `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java` — CRUD proxy for /api/ai/models and /api/ai/templates

**Modified backend files:**
- `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml` — include new changeset
- `emcip-admin-api/src/main/resources/application.yml` — add service.tdlib.url + service.orchestrator.url properties
- `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/GroupProfile.java` — add tenantId field
- `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/PolicyRule.java` — add tenantId field (column already exists in DB)
- `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java` — add PUT/DELETE for models and templates

**New frontend files:**
- `emcip-admin-ui/src/main/frontend/src/api/telegram.js` — API module
- `emcip-admin-ui/src/main/frontend/src/api/aiConfig.js` — API module
- `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.jsx`
- `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.module.css`
- `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.test.jsx`
- `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.jsx`
- `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.module.css`
- `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.test.jsx`

**Modified frontend files:**
- `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx` — add tenant dropdown in create/edit modal
- `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.jsx` — add tenant dropdown in create/edit modal
- `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx` — add Telegram + AI Config nav items
- `emcip-admin-ui/src/main/frontend/src/App.jsx` — add routes for /telegram and /ai-config

---

### Task 1: Schema — telegram_config table + group_profiles.tenant_id

**Files:**
- Create: `emcip-admin-api/src/main/resources/db/changelog/changes/006-telegram-config-and-group-tenant.xml`
- Modify: `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Write the Liquibase changeset**

Create `emcip-admin-api/src/main/resources/db/changelog/changes/006-telegram-config-and-group-tenant.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="006-telegram-config" author="emcip-team">
        <comment>Telegram credentials storage (single-row, id=1)</comment>

        <createTable tableName="telegram_config">
            <column name="id" type="BIGINT">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="phone_number" type="VARCHAR(50)"/>
            <column name="api_id" type="INTEGER"/>
            <column name="api_hash" type="VARCHAR(255)"/>
            <column name="session_string" type="TEXT"/>
            <column name="updated_at" type="TIMESTAMP"/>
        </createTable>

        <insert tableName="telegram_config">
            <column name="id" valueNumeric="1"/>
        </insert>
    </changeSet>

    <changeSet id="006-group-tenant-id" author="emcip-team">
        <comment>Associate group profiles with a tenant (optional FK)</comment>

        <addColumn tableName="group_profiles">
            <column name="tenant_id" type="UUID"/>
        </addColumn>

        <createIndex indexName="idx_group_profiles_tenant" tableName="group_profiles">
            <column name="tenant_id"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Add include to master changelog**

Open `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml` and add the include after the last existing include:

```xml
    <include file="db/changelog/changes/006-telegram-config-and-group-tenant.xml"/>
```

The final includes block should look like:
```xml
    <include file="db/changelog/changes/002-seed-admin-user.xml"/>
    <include file="db/changelog/changes/003-create-tenants-table.xml"/>
    <include file="db/changelog/changes/004-reset-admin-password.xml"/>
    <include file="db/changelog/changes/005-group-rules-default.xml"/>
    <include file="db/changelog/changes/006-telegram-config-and-group-tenant.xml"/>
```

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/resources/db/changelog/
git commit -m "feat(admin-api): add telegram_config table and group_profiles.tenant_id migration"
```

---

### Task 2: Java — TelegramConfig entity, repository, WebClientConfig, TelegramController

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramConfig.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/TelegramConfigRepository.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/config/WebClientConfig.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramController.java`
- Modify: `emcip-admin-api/src/main/resources/application.yml`

- [ ] **Step 1: Add service URLs to application.yml**

Add at the end of `emcip-admin-api/src/main/resources/application.yml`:

```yaml
service:
  tdlib:
    url: ${SERVICE_TDLIB_URL:http://localhost:9080}
  orchestrator:
    url: ${SERVICE_ORCHESTRATOR_URL:http://localhost:9084}
```

- [ ] **Step 2: Create TelegramConfig entity**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/TelegramConfig.java`:

```java
package io.emcip.admin.api.entity;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("telegram_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramConfig {

    @Id private Long id;

    @Column("phone_number")
    private String phoneNumber;

    @Column("api_id")
    private Integer apiId;

    @Column("api_hash")
    private String apiHash;

    @Column("session_string")
    private String sessionString;

    @Column("updated_at")
    private Instant updatedAt;
}
```

- [ ] **Step 3: Create TelegramConfigRepository**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/TelegramConfigRepository.java`:

```java
package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.TelegramConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface TelegramConfigRepository extends ReactiveCrudRepository<TelegramConfig, Long> {}
```

- [ ] **Step 4: Create WebClientConfig**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/config/WebClientConfig.java`:

```java
package io.emcip.admin.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean("tdlibWebClient")
    public WebClient tdlibWebClient(@Value("${service.tdlib.url}") String tdlibUrl) {
        return WebClient.builder().baseUrl(tdlibUrl).build();
    }

    @Bean("orchestratorWebClient")
    public WebClient orchestratorWebClient(
            @Value("${service.orchestrator.url}") String orchestratorUrl) {
        return WebClient.builder().baseUrl(orchestratorUrl).build();
    }
}
```

- [ ] **Step 5: Create TelegramController**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramController.java`:

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.TelegramConfig;
import io.emcip.admin.api.repository.TelegramConfigRepository;
import java.time.Instant;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/telegram")
public class TelegramController {

    private static final long CONFIG_ID = 1L;

    private final TelegramConfigRepository configRepository;
    private final WebClient tdlibClient;

    public TelegramController(
            TelegramConfigRepository configRepository,
            @Qualifier("tdlibWebClient") WebClient tdlibClient) {
        this.configRepository = configRepository;
        this.tdlibClient = tdlibClient;
    }

    /** GET /api/telegram/config — return stored credentials (session_string masked). */
    @GetMapping("/config")
    public Mono<Map<String, Object>> getConfig() {
        return configRepository
                .findById(CONFIG_ID)
                .map(
                        cfg ->
                                Map.of(
                                        "phoneNumber",
                                        cfg.getPhoneNumber() != null ? cfg.getPhoneNumber() : "",
                                        "apiId",
                                        cfg.getApiId() != null ? cfg.getApiId() : 0,
                                        "apiHash",
                                        cfg.getApiHash() != null ? cfg.getApiHash() : "",
                                        "sessionStringSet",
                                        cfg.getSessionString() != null
                                                && !cfg.getSessionString().isEmpty()))
                .defaultIfEmpty(
                        Map.of(
                                "phoneNumber", "",
                                "apiId", 0,
                                "apiHash", "",
                                "sessionStringSet", false));
    }

    /** PUT /api/telegram/config — save credentials. */
    @PutMapping("/config")
    public Mono<Map<String, Object>> saveConfig(@RequestBody TelegramConfigRequest req) {
        return configRepository
                .findById(CONFIG_ID)
                .switchIfEmpty(Mono.just(TelegramConfig.builder().id(CONFIG_ID).build()))
                .flatMap(
                        cfg -> {
                            if (req.getPhoneNumber() != null) cfg.setPhoneNumber(req.getPhoneNumber());
                            if (req.getApiId() != null) cfg.setApiId(req.getApiId());
                            if (req.getApiHash() != null) cfg.setApiHash(req.getApiHash());
                            if (req.getSessionString() != null && !req.getSessionString().isEmpty())
                                cfg.setSessionString(req.getSessionString());
                            cfg.setUpdatedAt(Instant.now());
                            return configRepository.save(cfg);
                        })
                .map(cfg -> Map.of("saved", true));
    }

    /**
     * GET /api/telegram/status — proxy to tdlib-adapter GET /api/auth/status. Also reads stored
     * phone number for display. Returns {status: "CONNECTED"|"PENDING"|"DISCONNECTED", message:
     * "...", phoneNumber: "..."}.
     */
    @GetMapping("/status")
    public Mono<Map<String, Object>> getStatus() {
        return configRepository
                .findById(CONFIG_ID)
                .map(cfg -> cfg.getPhoneNumber() != null ? cfg.getPhoneNumber() : "")
                .defaultIfEmpty("")
                .flatMap(
                        phone ->
                                tdlibClient
                                        .get()
                                        .uri("/api/auth/status")
                                        .retrieve()
                                        .bodyToMono(TdlibStatusResponse.class)
                                        .map(
                                                r -> {
                                                    String status =
                                                            r.isAuthorized()
                                                                    ? "CONNECTED"
                                                                    : r.isInitialized()
                                                                            ? "PENDING"
                                                                            : "DISCONNECTED";
                                                    return Map.of(
                                                            "status", status,
                                                            "message", r.getMessage(),
                                                            "phoneNumber", phone);
                                                })
                                        .onErrorResume(
                                                e -> {
                                                    log.warn(
                                                            "tdlib-adapter unreachable: {}",
                                                            e.getMessage());
                                                    return Mono.just(
                                                            Map.of(
                                                                    "status", "DISCONNECTED",
                                                                    "message", "Adapter offline",
                                                                    "phoneNumber", phone));
                                                }));
    }

    /**
     * POST /api/telegram/reconnect — trigger re-authentication by sending stored phone number to
     * tdlib-adapter.
     */
    @PostMapping("/reconnect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> reconnect() {
        return configRepository
                .findById(CONFIG_ID)
                .flatMap(
                        cfg -> {
                            if (cfg.getPhoneNumber() == null || cfg.getPhoneNumber().isEmpty()) {
                                return Mono.just(
                                        Map.of(
                                                "accepted",
                                                false,
                                                "reason",
                                                "No phone number configured"));
                            }
                            return tdlibClient
                                    .post()
                                    .uri("/api/auth/phone")
                                    .bodyValue(Map.of("phoneNumber", cfg.getPhoneNumber()))
                                    .retrieve()
                                    .bodyToMono(Void.class)
                                    .thenReturn(Map.of("accepted", true, "phone", cfg.getPhoneNumber()))
                                    .onErrorResume(
                                            e -> {
                                                log.warn("reconnect failed: {}", e.getMessage());
                                                return Mono.just(
                                                        Map.of(
                                                                "accepted",
                                                                false,
                                                                "reason",
                                                                e.getMessage()));
                                            });
                        })
                .defaultIfEmpty(Map.of("accepted", false, "reason", "No config found"));
    }

    @Data
    public static class TelegramConfigRequest {
        private String phoneNumber;
        private Integer apiId;
        private String apiHash;
        private String sessionString;
    }

    @Data
    public static class TdlibStatusResponse {
        private boolean initialized;
        private boolean authorized;
        private String message;
    }
}
```

- [ ] **Step 6: Verify compilation**

```bash
cd /home/ben/Development/ecip
mvn compile -pl emcip-admin-api -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 7: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/ emcip-admin-api/src/main/resources/application.yml
git commit -m "feat(admin-api): TelegramConfig entity, repo, WebClientConfig, TelegramController"
```

---

### Task 3: Java — Add tenantId to GroupProfile and PolicyRule entities

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/GroupProfile.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/PolicyRule.java`

**Context:** `group_profiles.tenant_id` was just added by Task 1 migration. `policy_rules.tenant_id` already exists (added by policy-engine migration 004). Both entities just need the Java field mapped.

- [ ] **Step 1: Add tenantId field to GroupProfile**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/GroupProfile.java`.

Add this import after the existing imports:
```java
import java.util.UUID;
```

Add this field after `welcomeMessage`:
```java
    @Column("tenant_id")
    private UUID tenantId;
```

The fields block should now end with:
```java
    @Column("welcome_message")
    private String welcomeMessage;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
```

- [ ] **Step 2: Add tenantId field to PolicyRule**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/PolicyRule.java`.

Add this import after the existing imports:
```java
import java.util.UUID;
```

Add this field after `createdAt`:
```java
    @Column("tenant_id")
    private UUID tenantId;
```

The fields block should end with:
```java
    @Column("effective_to")
    private Instant effectiveTo;

    @Column("created_at")
    private Instant createdAt;

    @Column("tenant_id")
    private UUID tenantId;
```

- [ ] **Step 3: Verify compilation**

```bash
cd /home/ben/Development/ecip
mvn compile -pl emcip-admin-api -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/entity/
git commit -m "feat(admin-api): map tenantId field on GroupProfile and PolicyRule entities"
```

---

### Task 4: Java — Orchestrator PUT/DELETE endpoints for models and templates

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`

**Context:** The existing controller has GET/POST for models and templates. We add PUT/DELETE so the admin-api proxy can offer full CRUD.

- [ ] **Step 1: Add required imports**

Open `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`.

Add these imports (add only the ones not already present):
```java
import io.emcip.llm.orchestrator.entity.PromptTemplate;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.server.ResponseStatusException;
```

Add `PromptTemplateRepository` to the injected fields — the class currently injects `LlmOrchestratorService`, `CostTrackingService`, and `ModelConfigRepository`. Add:
```java
    private final PromptTemplateRepository promptTemplateRepository;
```

Since `@RequiredArgsConstructor` generates the constructor, just declare the field and Lombok will wire it.

- [ ] **Step 2: Add PUT/DELETE for models**

After the existing `@PostMapping("/models")` method, add:

```java
    @PutMapping("/models/{id}")
    public ResponseEntity<ModelConfig> updateModel(
            @PathVariable UUID id, @RequestBody ModelConfig update) {
        ModelConfig existing =
                modelConfigRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Model not found: " + id));
        existing.setModelKey(update.getModelKey());
        existing.setProvider(update.getProvider());
        existing.setModelName(update.getModelName());
        existing.setDescription(update.getDescription());
        existing.setTaskType(update.getTaskType());
        existing.setInputCostPer1kTokens(update.getInputCostPer1kTokens());
        existing.setOutputCostPer1kTokens(update.getOutputCostPer1kTokens());
        existing.setContextWindow(update.getContextWindow());
        existing.setMaxOutputTokens(update.getMaxOutputTokens());
        existing.setAvgLatencyMs(update.getAvgLatencyMs());
        existing.setSupportsStreaming(update.getSupportsStreaming());
        existing.setActive(update.getActive());
        existing.setPriority(update.getPriority());
        return ResponseEntity.ok(modelConfigRepository.save(existing));
    }

    @DeleteMapping("/models/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteModel(@PathVariable UUID id) {
        if (!modelConfigRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found: " + id);
        }
        modelConfigRepository.deleteById(id);
    }
```

- [ ] **Step 3: Add PUT/DELETE for templates**

After the existing `@PostMapping("/templates")` method, add:

```java
    @PutMapping("/templates/{id}")
    public ResponseEntity<PromptTemplate> updateTemplate(
            @PathVariable UUID id, @RequestBody PromptTemplate update) {
        PromptTemplate existing =
                promptTemplateRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Template not found: " + id));
        existing.setName(update.getName());
        existing.setVersion(update.getVersion());
        existing.setDescription(update.getDescription());
        existing.setModelProvider(update.getModelProvider());
        existing.setModelName(update.getModelName());
        existing.setSystemPrompt(update.getSystemPrompt());
        existing.setUserPromptTemplate(update.getUserPromptTemplate());
        existing.setTemperature(update.getTemperature());
        existing.setMaxTokens(update.getMaxTokens());
        existing.setActive(update.getActive());
        existing.setPriority(update.getPriority());
        return ResponseEntity.ok(promptTemplateRepository.save(existing));
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@PathVariable UUID id) {
        if (!promptTemplateRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: " + id);
        }
        promptTemplateRepository.deleteById(id);
    }
```

- [ ] **Step 4: Verify compilation**

```bash
cd /home/ben/Development/ecip
mvn compile -pl emcip-llm-orchestrator -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator -q
git add emcip-llm-orchestrator/src/main/java/
git commit -m "feat(orchestrator): add PUT/DELETE endpoints for model configs and prompt templates"
```

---

### Task 5: Java — AIProxyController in admin-api

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java`

**Context:** Proxies all CRUD operations for /api/ai/models and /api/ai/templates to the llm-orchestrator at the configured URL. Uses the `orchestratorWebClient` bean created in Task 2.

- [ ] **Step 1: Write the failing test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/AIProxyControllerTest.java`:

```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@WebFluxTest(AIProxyController.class)
class AIProxyControllerTest {

    @Autowired WebTestClient client;

    @MockitoBean(name = "orchestratorWebClient")
    WebClient orchestratorWebClient;

    @Test
    void getModels_proxiesDownstream() {
        // Arrange: mock the WebClient chain
        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(orchestratorWebClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/models")).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("[]"));

        // Act + Assert
        client.get().uri("/api/ai/models")
                .header("Authorization", "Bearer test-token")
                .exchange()
                .expectStatus().isOk();
    }
}
```

Note: This test is intentionally light — the proxy is a thin pass-through so full integration testing belongs in E2E. The test just verifies the route exists and wires the WebClient.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -Dtest=AIProxyControllerTest -q 2>&1 | tail -10
```

Expected: FAIL — `AIProxyController` does not exist yet.

- [ ] **Step 3: Write AIProxyController**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java`:

```java
package io.emcip.admin.api.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Proxies AI configuration CRUD to the llm-orchestrator service. Admin-UI → admin-api →
 * llm-orchestrator (API Gateway pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AIProxyController {

    private final WebClient orchestratorClient;

    public AIProxyController(@Qualifier("orchestratorWebClient") WebClient orchestratorClient) {
        this.orchestratorClient = orchestratorClient;
    }

    // ---- Models ----

    @GetMapping("/models")
    public Mono<String> listModels() {
        return proxy().get().uri("/api/models").retrieve().bodyToMono(String.class);
    }

    @PostMapping(value = "/models", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<String> createModel(@RequestBody String body) {
        return proxy()
                .post()
                .uri("/api/models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    @PutMapping(value = "/models/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> updateModel(@PathVariable String id, @RequestBody String body) {
        return proxy()
                .put()
                .uri("/api/models/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    @DeleteMapping("/models/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteModel(@PathVariable String id) {
        return proxy().delete().uri("/api/models/{id}", id).retrieve().bodyToMono(Void.class);
    }

    // ---- Templates ----

    @GetMapping("/templates")
    public Mono<String> listTemplates() {
        return proxy().get().uri("/api/templates").retrieve().bodyToMono(String.class);
    }

    @PostMapping(value = "/templates", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<String> createTemplate(@RequestBody String body) {
        return proxy()
                .post()
                .uri("/api/templates")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    @PutMapping(value = "/templates/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> updateTemplate(@PathVariable String id, @RequestBody String body) {
        return proxy()
                .put()
                .uri("/api/templates/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTemplate(@PathVariable String id) {
        return proxy().delete().uri("/api/templates/{id}", id).retrieve().bodyToMono(Void.class);
    }

    private WebClient proxy() {
        return orchestratorClient;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -Dtest=AIProxyControllerTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 1 test passing.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/ emcip-admin-api/src/test/
git commit -m "feat(admin-api): AIProxyController — proxy AI model/template CRUD to orchestrator"
```

---

### Task 6: Frontend — telegram.js and aiConfig.js API modules

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/api/telegram.js`
- Create: `emcip-admin-ui/src/main/frontend/src/api/aiConfig.js`

- [ ] **Step 1: Write failing tests**

Create `emcip-admin-ui/src/main/frontend/src/api/telegram.test.js`:

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { telegramApi } from './telegram'

describe('telegramApi', () => {
  let request

  beforeEach(() => {
    request = vi.fn().mockResolvedValue({})
  })

  it('getConfig calls /api/telegram/config', async () => {
    await telegramApi(request).getConfig()
    expect(request).toHaveBeenCalledWith('/api/telegram/config')
  })

  it('saveConfig calls PUT /api/telegram/config', async () => {
    await telegramApi(request).saveConfig({ phoneNumber: '+1234' })
    expect(request).toHaveBeenCalledWith(
      '/api/telegram/config',
      expect.objectContaining({ method: 'PUT' })
    )
  })

  it('getStatus calls /api/telegram/status', async () => {
    await telegramApi(request).getStatus()
    expect(request).toHaveBeenCalledWith('/api/telegram/status')
  })

  it('reconnect calls POST /api/telegram/reconnect', async () => {
    await telegramApi(request).reconnect()
    expect(request).toHaveBeenCalledWith(
      '/api/telegram/reconnect',
      expect.objectContaining({ method: 'POST' })
    )
  })
})
```

Create `emcip-admin-ui/src/main/frontend/src/api/aiConfig.test.js`:

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { aiConfigApi } from './aiConfig'

describe('aiConfigApi', () => {
  let request

  beforeEach(() => {
    request = vi.fn().mockResolvedValue({})
  })

  it('listModels calls /api/ai/models', async () => {
    await aiConfigApi(request).listModels()
    expect(request).toHaveBeenCalledWith('/api/ai/models')
  })

  it('createModel calls POST /api/ai/models', async () => {
    await aiConfigApi(request).createModel({ modelKey: 'gpt-4o' })
    expect(request).toHaveBeenCalledWith(
      '/api/ai/models',
      expect.objectContaining({ method: 'POST' })
    )
  })

  it('updateModel calls PUT /api/ai/models/:id', async () => {
    await aiConfigApi(request).updateModel('abc-123', { modelKey: 'gpt-4o' })
    expect(request).toHaveBeenCalledWith(
      '/api/ai/models/abc-123',
      expect.objectContaining({ method: 'PUT' })
    )
  })

  it('deleteModel calls DELETE /api/ai/models/:id', async () => {
    await aiConfigApi(request).deleteModel('abc-123')
    expect(request).toHaveBeenCalledWith(
      '/api/ai/models/abc-123',
      expect.objectContaining({ method: 'DELETE' })
    )
  })

  it('listTemplates calls /api/ai/templates', async () => {
    await aiConfigApi(request).listTemplates()
    expect(request).toHaveBeenCalledWith('/api/ai/templates')
  })

  it('deleteTemplate calls DELETE /api/ai/templates/:id', async () => {
    await aiConfigApi(request).deleteTemplate('tmpl-1')
    expect(request).toHaveBeenCalledWith(
      '/api/ai/templates/tmpl-1',
      expect.objectContaining({ method: 'DELETE' })
    )
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/api/telegram.test.js src/api/aiConfig.test.js 2>&1 | tail -10
```

Expected: FAIL — modules not found.

- [ ] **Step 3: Implement telegram.js**

Create `emcip-admin-ui/src/main/frontend/src/api/telegram.js`:

```js
export function telegramApi(request) {
  return {
    getConfig: () => request('/api/telegram/config'),
    saveConfig: body =>
      request('/api/telegram/config', { method: 'PUT', body: JSON.stringify(body) }),
    getStatus: () => request('/api/telegram/status'),
    reconnect: () => request('/api/telegram/reconnect', { method: 'POST', body: '{}' }),
  }
}
```

- [ ] **Step 4: Implement aiConfig.js**

Create `emcip-admin-ui/src/main/frontend/src/api/aiConfig.js`:

```js
export function aiConfigApi(request) {
  return {
    listModels: () => request('/api/ai/models'),
    createModel: body =>
      request('/api/ai/models', { method: 'POST', body: JSON.stringify(body) }),
    updateModel: (id, body) =>
      request(`/api/ai/models/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    deleteModel: id =>
      request(`/api/ai/models/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    listTemplates: () => request('/api/ai/templates'),
    createTemplate: body =>
      request('/api/ai/templates', { method: 'POST', body: JSON.stringify(body) }),
    updateTemplate: (id, body) =>
      request(`/api/ai/templates/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    deleteTemplate: id =>
      request(`/api/ai/templates/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/api/telegram.test.js src/api/aiConfig.test.js 2>&1 | tail -5
```

Expected: 10 tests passing.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/api/
git commit -m "feat(admin-ui): telegram.js and aiConfig.js API modules with tests"
```

---

### Task 7: Frontend — Telegram page

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.module.css`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.test.jsx`

- [ ] **Step 1: Write the failing test**

Create `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.test.jsx`:

```jsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { Telegram } from './Telegram'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
}))

vi.mock('../../api/telegram', () => ({
  telegramApi: () => ({
    getStatus: vi.fn().mockResolvedValue({ status: 'CONNECTED', message: 'Ready', phoneNumber: '+49123456' }),
    getConfig: vi.fn().mockResolvedValue({
      phoneNumber: '+49123456',
      apiId: 12345,
      apiHash: 'abc',
      sessionStringSet: true,
    }),
    saveConfig: vi.fn().mockResolvedValue({ saved: true }),
    reconnect: vi.fn().mockResolvedValue({ accepted: true }),
  }),
}))

vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

describe('Telegram page', () => {
  it('shows connection status badge', async () => {
    render(<Telegram />)
    await waitFor(() => {
      expect(screen.getByText('CONNECTED')).toBeInTheDocument()
    })
  })

  it('shows stored phone number in form', async () => {
    render(<Telegram />)
    await waitFor(() => {
      expect(screen.getByDisplayValue('+49123456')).toBeInTheDocument()
    })
  })

  it('save button submits config', async () => {
    render(<Telegram />)
    await waitFor(() => screen.getByDisplayValue('+49123456'))
    await userEvent.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => {
      expect(screen.getByText(/saved/i)).toBeInTheDocument()
    })
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/pages/Telegram/Telegram.test.jsx 2>&1 | tail -10
```

Expected: FAIL — `Telegram` module not found.

- [ ] **Step 3: Implement Telegram.module.css**

Create `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.module.css`:

```css
.page { display: flex; flex-direction: column; gap: 1.5rem; }

.card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 0.5rem;
  padding: 1.5rem;
}

.cardTitle {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 1rem;
}

.statusRow {
  display: flex;
  align-items: center;
  gap: 1rem;
  flex-wrap: wrap;
}

.message {
  color: var(--text-secondary);
  font-size: 0.85rem;
}

.form { display: flex; flex-direction: column; gap: 0.75rem; }

.label {
  font-size: 0.85rem;
  color: var(--text-secondary);
  margin-bottom: 0.2rem;
  display: block;
}

.input {
  width: 100%;
  padding: 0.45rem 0.7rem;
  border: 1px solid var(--border);
  border-radius: 0.35rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.9rem;
  box-sizing: border-box;
}

.sessionToggle {
  background: none;
  border: none;
  color: var(--accent);
  cursor: pointer;
  font-size: 0.85rem;
  padding: 0;
  margin-bottom: 0.25rem;
}

.mono {
  font-family: 'Source Code Pro', monospace;
  font-size: 0.8rem;
}

.actions { display: flex; gap: 0.75rem; margin-top: 0.5rem; }

.feedback { font-size: 0.85rem; color: var(--accent); margin-top: 0.5rem; }

.error { color: #ef4444; font-size: 0.85rem; }

.disabledSection { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid var(--border); }

.disabledLabel { color: var(--text-secondary); font-size: 0.8rem; margin-bottom: 0.5rem; }
```

- [ ] **Step 4: Implement Telegram.jsx**

Create `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { telegramApi } from '../../api/telegram'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import styles from './Telegram.module.css'

const STATUS_VARIANT = { CONNECTED: 'green', PENDING: 'yellow', DISCONNECTED: 'red' }

export function Telegram() {
  const { token } = useAuth()
  const api = telegramApi(makeRequest(token))

  const [status, setStatus] = useState({ status: 'DISCONNECTED', message: '', phoneNumber: '' })
  const [config, setConfig] = useState({ phoneNumber: '', apiId: '', apiHash: '', sessionStringSet: false })
  const [sessionInput, setSessionInput] = useState('')
  const [showSession, setShowSession] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [error, setError] = useState('')

  const loadStatus = () =>
    api.getStatus().then(setStatus).catch(e => setError(e.message))

  const loadConfig = () =>
    api.getConfig().then(setConfig).catch(e => setError(e.message))

  useEffect(() => {
    loadStatus()
    loadConfig()
  }, [])

  const handleSave = async () => {
    setFeedback('')
    setError('')
    try {
      const payload = {
        phoneNumber: config.phoneNumber,
        apiId: config.apiId ? parseInt(config.apiId, 10) : undefined,
        apiHash: config.apiHash,
      }
      if (sessionInput.trim()) payload.sessionString = sessionInput.trim()
      await api.saveConfig(payload)
      setFeedback('Saved')
      setSessionInput('')
      loadConfig()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleReconnect = async () => {
    setFeedback('')
    setError('')
    try {
      const res = await api.reconnect()
      setFeedback(res.accepted ? 'Reconnect triggered' : `Failed: ${res.reason}`)
      setTimeout(loadStatus, 1500)
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className={styles.page}>
      <h2>Telegram</h2>

      {/* Connection Status */}
      <div className={styles.card}>
        <h3 className={styles.cardTitle}>Connection Status</h3>
        <div className={styles.statusRow}>
          <Badge variant={STATUS_VARIANT[status.status] ?? 'gray'}>{status.status}</Badge>
          {status.phoneNumber && <span className={styles.message}>{status.phoneNumber}</span>}
          <span className={styles.message}>{status.message}</span>
          <Button variant="secondary" onClick={handleReconnect}>Reconnect</Button>
        </div>
      </div>

      {/* Credentials */}
      <div className={styles.card}>
        <h3 className={styles.cardTitle}>Credentials</h3>
        {error && <p className={styles.error} role="alert">{error}</p>}
        <div className={styles.form}>
          <div>
            <label className={styles.label}>Phone Number</label>
            <input
              type="text"
              className={styles.input}
              value={config.phoneNumber}
              onChange={e => setConfig(c => ({ ...c, phoneNumber: e.target.value }))}
              placeholder="+49123456789"
            />
          </div>
          <div>
            <label className={styles.label}>API ID</label>
            <input
              type="number"
              className={styles.input}
              value={config.apiId}
              onChange={e => setConfig(c => ({ ...c, apiId: e.target.value }))}
            />
          </div>
          <div>
            <label className={styles.label}>API Hash</label>
            <input
              type="text"
              className={`${styles.input} ${styles.mono}`}
              value={config.apiHash}
              onChange={e => setConfig(c => ({ ...c, apiHash: e.target.value }))}
            />
          </div>
          <div>
            <button
              type="button"
              className={styles.sessionToggle}
              onClick={() => setShowSession(s => !s)}
            >
              {showSession ? '▲ Hide' : '▼ Session String'}{config.sessionStringSet ? ' (set)' : ' (not set)'}
            </button>
            {showSession && (
              <textarea
                className={`${styles.input} ${styles.mono}`}
                rows={4}
                value={sessionInput}
                onChange={e => setSessionInput(e.target.value)}
                placeholder="Paste new session string here to update..."
              />
            )}
          </div>

          <div className={styles.actions}>
            <Button onClick={handleSave}>Save</Button>
          </div>
          {feedback && <p className={styles.feedback}>{feedback}</p>}

          <div className={styles.disabledSection}>
            <p className={styles.disabledLabel}>Live auth flow (coming in next phase)</p>
            <div className={styles.actions}>
              <Button disabled title="Coming in next phase">Request Auth Code</Button>
              <Button disabled title="Coming in next phase">Submit Code</Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/pages/Telegram/Telegram.test.jsx 2>&1 | tail -5
```

Expected: 3 tests passing.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Telegram/
git commit -m "feat(admin-ui): Telegram configuration page — status + credentials + reconnect"
```

---

### Task 8: Frontend — AIConfig page (models + templates)

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.module.css`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.test.jsx`

- [ ] **Step 1: Write the failing test**

Create `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.test.jsx`:

```jsx
import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { AIConfig } from './AIConfig'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
}))

const mockModels = [
  { id: 'uuid-1', modelKey: 'gpt-4o', provider: 'openai', modelName: 'GPT-4o', active: true, priority: 10 },
]
const mockTemplates = [
  { id: 'tmpl-1', name: 'moderation-v1', version: '1.0', modelProvider: 'openai', modelName: 'GPT-4o', systemPrompt: 'You are a moderator', active: true },
]

vi.mock('../../api/aiConfig', () => ({
  aiConfigApi: () => ({
    listModels: vi.fn().mockResolvedValue(mockModels),
    listTemplates: vi.fn().mockResolvedValue(mockTemplates),
    createModel: vi.fn().mockResolvedValue({}),
    updateModel: vi.fn().mockResolvedValue({}),
    deleteModel: vi.fn().mockResolvedValue(null),
    createTemplate: vi.fn().mockResolvedValue({}),
    updateTemplate: vi.fn().mockResolvedValue({}),
    deleteTemplate: vi.fn().mockResolvedValue(null),
  }),
}))

vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

describe('AIConfig page', () => {
  it('renders Models section heading', async () => {
    render(<AIConfig />)
    await waitFor(() => {
      expect(screen.getByText('AI Models')).toBeInTheDocument()
    })
  })

  it('lists model keys from API', async () => {
    render(<AIConfig />)
    await waitFor(() => {
      expect(screen.getByText('gpt-4o')).toBeInTheDocument()
    })
  })

  it('renders Templates section heading', async () => {
    render(<AIConfig />)
    await waitFor(() => {
      expect(screen.getByText('Prompt Templates')).toBeInTheDocument()
    })
  })

  it('lists template names from API', async () => {
    render(<AIConfig />)
    await waitFor(() => {
      expect(screen.getByText('moderation-v1')).toBeInTheDocument()
    })
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/pages/AIConfig/AIConfig.test.jsx 2>&1 | tail -10
```

Expected: FAIL — `AIConfig` not found.

- [ ] **Step 3: Implement AIConfig.module.css**

Create `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.module.css`:

```css
.page { display: flex; flex-direction: column; gap: 2rem; }

.section { display: flex; flex-direction: column; gap: 0.75rem; }

.sectionHeader {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.sectionTitle {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
}

.table th {
  text-align: left;
  padding: 0.5rem 0.75rem;
  border-bottom: 2px solid var(--border);
  color: var(--text-secondary);
  font-weight: 500;
}

.table td {
  padding: 0.6rem 0.75rem;
  border-bottom: 1px solid var(--border);
  color: var(--text-primary);
  vertical-align: top;
}

.mono { font-family: 'Source Code Pro', monospace; font-size: 0.8rem; }

.actions { display: flex; gap: 0.4rem; }

.input {
  width: 100%;
  padding: 0.45rem 0.7rem;
  border: 1px solid var(--border);
  border-radius: 0.35rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.875rem;
  box-sizing: border-box;
}

.promptTextarea {
  font-family: 'Source Code Pro', monospace;
  font-size: 0.8rem;
  resize: vertical;
}

.preview {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-secondary);
  font-size: 0.8rem;
}

.error { color: #ef4444; font-size: 0.85rem; }
```

- [ ] **Step 4: Implement AIConfig.jsx**

Create `emcip-admin-ui/src/main/frontend/src/pages/AIConfig/AIConfig.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { aiConfigApi } from '../../api/aiConfig'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './AIConfig.module.css'

function ModelModal({ model, onClose, onSave }) {
  const [form, setForm] = useState({
    modelKey: model?.modelKey ?? '',
    provider: model?.provider ?? '',
    modelName: model?.modelName ?? '',
    description: model?.description ?? '',
    taskType: model?.taskType ?? 'GENERAL',
    inputCostPer1kTokens: model?.inputCostPer1kTokens ?? 0,
    outputCostPer1kTokens: model?.outputCostPer1kTokens ?? 0,
    contextWindow: model?.contextWindow ?? 8192,
    maxOutputTokens: model?.maxOutputTokens ?? 2048,
    avgLatencyMs: model?.avgLatencyMs ?? 500,
    supportsStreaming: model?.supportsStreaming ?? false,
    active: model?.active ?? true,
    priority: model?.priority ?? 100,
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={model ? 'Edit Model' : 'Add Model'} onClose={onClose} onSubmit={() => onSave(form)}>
      <label>Model Key *</label>
      <input type="text" className={styles.input} value={form.modelKey}
        onChange={e => set('modelKey', e.target.value)} required />
      <label>Provider *</label>
      <input type="text" className={styles.input} value={form.provider}
        onChange={e => set('provider', e.target.value)} placeholder="openai / anthropic / google" required />
      <label>Model Name *</label>
      <input type="text" className={styles.input} value={form.modelName}
        onChange={e => set('modelName', e.target.value)} required />
      <label>Description</label>
      <input type="text" className={styles.input} value={form.description}
        onChange={e => set('description', e.target.value)} />
      <label>Task Type</label>
      <select className={styles.input} value={form.taskType}
        onChange={e => set('taskType', e.target.value)}>
        {['GENERAL', 'CLASSIFICATION', 'MODERATION', 'SUMMARIZATION', 'CHAT'].map(t => (
          <option key={t}>{t}</option>
        ))}
      </select>
      <label>Input cost / 1k tokens ($)</label>
      <input type="number" step="0.0001" className={styles.input} value={form.inputCostPer1kTokens}
        onChange={e => set('inputCostPer1kTokens', parseFloat(e.target.value))} />
      <label>Output cost / 1k tokens ($)</label>
      <input type="number" step="0.0001" className={styles.input} value={form.outputCostPer1kTokens}
        onChange={e => set('outputCostPer1kTokens', parseFloat(e.target.value))} />
      <label>Context window (tokens)</label>
      <input type="number" className={styles.input} value={form.contextWindow}
        onChange={e => set('contextWindow', parseInt(e.target.value, 10))} />
      <label>Max output tokens</label>
      <input type="number" className={styles.input} value={form.maxOutputTokens}
        onChange={e => set('maxOutputTokens', parseInt(e.target.value, 10))} />
      <label>Priority (lower = preferred)</label>
      <input type="number" className={styles.input} value={form.priority}
        onChange={e => set('priority', parseInt(e.target.value, 10))} />
      <label>
        <input type="checkbox" checked={form.supportsStreaming}
          onChange={e => set('supportsStreaming', e.target.checked)} /> Supports streaming
      </label>
      <label>
        <input type="checkbox" checked={form.active}
          onChange={e => set('active', e.target.checked)} /> Active
      </label>
    </Modal>
  )
}

function TemplateModal({ template, onClose, onSave }) {
  const [form, setForm] = useState({
    name: template?.name ?? '',
    version: template?.version ?? '1.0',
    description: template?.description ?? '',
    modelProvider: template?.modelProvider ?? '',
    modelName: template?.modelName ?? '',
    systemPrompt: template?.systemPrompt ?? '',
    userPromptTemplate: template?.userPromptTemplate ?? '',
    temperature: template?.temperature ?? 0.7,
    maxTokens: template?.maxTokens ?? 2048,
    active: template?.active ?? true,
    priority: template?.priority ?? 100,
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={template ? 'Edit Template' : 'Add Template'} onClose={onClose} onSubmit={() => onSave(form)}>
      <label>Name *</label>
      <input type="text" className={styles.input} value={form.name}
        onChange={e => set('name', e.target.value)} required />
      <label>Version</label>
      <input type="text" className={styles.input} value={form.version}
        onChange={e => set('version', e.target.value)} />
      <label>Description</label>
      <input type="text" className={styles.input} value={form.description}
        onChange={e => set('description', e.target.value)} />
      <label>Model Provider</label>
      <input type="text" className={styles.input} value={form.modelProvider}
        onChange={e => set('modelProvider', e.target.value)} placeholder="openai" />
      <label>Model Name</label>
      <input type="text" className={styles.input} value={form.modelName}
        onChange={e => set('modelName', e.target.value)} placeholder="gpt-4o" />
      <label>System Prompt *</label>
      <textarea className={`${styles.input} ${styles.promptTextarea}`} rows={6}
        value={form.systemPrompt} onChange={e => set('systemPrompt', e.target.value)} required />
      <label>User Prompt Template</label>
      <textarea className={`${styles.input} ${styles.promptTextarea}`} rows={4}
        value={form.userPromptTemplate} onChange={e => set('userPromptTemplate', e.target.value)}
        placeholder="Use {{variable}} placeholders" />
      <label>Temperature</label>
      <input type="number" step="0.1" min="0" max="2" className={styles.input}
        value={form.temperature} onChange={e => set('temperature', parseFloat(e.target.value))} />
      <label>Max Tokens</label>
      <input type="number" className={styles.input} value={form.maxTokens}
        onChange={e => set('maxTokens', parseInt(e.target.value, 10))} />
      <label>
        <input type="checkbox" checked={form.active}
          onChange={e => set('active', e.target.checked)} /> Active
      </label>
    </Modal>
  )
}

export function AIConfig() {
  const { token } = useAuth()
  const api = aiConfigApi(makeRequest(token))

  const [models, setModels] = useState([])
  const [templates, setTemplates] = useState([])
  const [modelModal, setModelModal] = useState(null)
  const [templateModal, setTemplateModal] = useState(null)
  const [error, setError] = useState('')

  const loadModels = () => api.listModels().then(setModels).catch(e => setError(e.message))
  const loadTemplates = () => api.listTemplates().then(setTemplates).catch(e => setError(e.message))

  useEffect(() => {
    loadModels()
    loadTemplates()
  }, [])

  const saveModel = async form => {
    try {
      if (modelModal === 'add') await api.createModel(form)
      else await api.updateModel(modelModal.id, form)
      setModelModal(null)
      loadModels()
    } catch (e) { setError(e.message) }
  }

  const removeModel = async model => {
    if (!confirm(`Delete model "${model.modelKey}"?`)) return
    try { await api.deleteModel(model.id); loadModels() }
    catch (e) { setError(e.message) }
  }

  const saveTemplate = async form => {
    try {
      if (templateModal === 'add') await api.createTemplate(form)
      else await api.updateTemplate(templateModal.id, form)
      setTemplateModal(null)
      loadTemplates()
    } catch (e) { setError(e.message) }
  }

  const removeTemplate = async tmpl => {
    if (!confirm(`Delete template "${tmpl.name}"?`)) return
    try { await api.deleteTemplate(tmpl.id); loadTemplates() }
    catch (e) { setError(e.message) }
  }

  return (
    <div className={styles.page}>
      <h2>AI Configuration</h2>
      {error && <p className={styles.error} role="alert">{error}</p>}

      {/* Models */}
      <div className={styles.section}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>AI Models</h3>
          <Button onClick={() => setModelModal('add')}>+ Add Model</Button>
        </div>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Key</th>
              <th>Provider</th>
              <th>Model Name</th>
              <th>Task Type</th>
              <th>Priority</th>
              <th>Active</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {models.map(m => (
              <tr key={m.id}>
                <td className={styles.mono}>{m.modelKey}</td>
                <td>{m.provider}</td>
                <td>{m.modelName}</td>
                <td><Badge variant="gray">{m.taskType}</Badge></td>
                <td>{m.priority}</td>
                <td><Badge variant={m.active ? 'green' : 'red'}>{m.active ? 'Yes' : 'No'}</Badge></td>
                <td className={styles.actions}>
                  <Button variant="secondary" onClick={() => setModelModal(m)}>Edit</Button>
                  <Button variant="danger" onClick={() => removeModel(m)}>Delete</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Templates */}
      <div className={styles.section}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>Prompt Templates</h3>
          <Button onClick={() => setTemplateModal('add')}>+ Add Template</Button>
        </div>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Name</th>
              <th>Version</th>
              <th>Provider</th>
              <th>System Prompt</th>
              <th>Active</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {templates.map(t => (
              <tr key={t.id}>
                <td>{t.name}</td>
                <td className={styles.mono}>{t.version}</td>
                <td>{t.modelProvider}</td>
                <td className={styles.preview} title={t.systemPrompt}>{t.systemPrompt}</td>
                <td><Badge variant={t.active ? 'green' : 'red'}>{t.active ? 'Yes' : 'No'}</Badge></td>
                <td className={styles.actions}>
                  <Button variant="secondary" onClick={() => setTemplateModal(t)}>Edit</Button>
                  <Button variant="danger" onClick={() => removeTemplate(t)}>Delete</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {modelModal && (
        <ModelModal
          model={modelModal === 'add' ? null : modelModal}
          onClose={() => setModelModal(null)}
          onSave={saveModel}
        />
      )}
      {templateModal && (
        <TemplateModal
          template={templateModal === 'add' ? null : templateModal}
          onClose={() => setTemplateModal(null)}
          onSave={saveTemplate}
        />
      )}
    </div>
  )
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/pages/AIConfig/AIConfig.test.jsx 2>&1 | tail -5
```

Expected: 4 tests passing.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/AIConfig/
git commit -m "feat(admin-ui): AIConfig page — models table + prompt templates table with full CRUD"
```

---

### Task 9: Frontend — Tenant dropdown in Groups page

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx`

**Context:** Groups page already exists. `GroupProfile` entity now has `tenantId`. The UI needs a `<select>` in the create/edit modal populated from `GET /api/tenants`. Selector format: `"Tenant Name (short-uuid)"` as per spec A4.

- [ ] **Step 1: Write the failing test**

Create `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.tenant.test.jsx`:

```jsx
import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { Groups } from './Groups'

const mockGroups = [
  { telegramChatId: -1001234567890, name: 'Test Group', moderationLevel: 'LOW', autoRespond: false }
]
const mockTenants = [
  { id: '11111111-0000-0000-0000-000000000000', name: 'Acme Corp' },
]

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'test-token' }) }))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

vi.mock('../../api/groups', () => ({
  groupsApi: () => ({
    list: vi.fn().mockResolvedValue(mockGroups),
    create: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
    remove: vi.fn().mockResolvedValue(null),
  }),
}))

vi.mock('../../api/tenants', () => ({
  tenantsApi: () => ({
    list: vi.fn().mockResolvedValue(mockTenants),
  }),
}))

describe('Groups page — tenant dropdown', () => {
  it('shows tenant dropdown in Add Group modal', async () => {
    render(<Groups />)
    await waitFor(() => screen.getByText('+ Add Group'))
    const btn = screen.getByRole('button', { name: /add group/i })
    btn.click()
    await waitFor(() => {
      expect(screen.getByText(/acme corp/i)).toBeInTheDocument()
    })
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/pages/Groups/Groups.tenant.test.jsx 2>&1 | tail -10
```

Expected: FAIL — tenant dropdown not rendered.

- [ ] **Step 3: Update Groups.jsx**

Open `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx`.

Add import for `tenantsApi` at the top:
```js
import { tenantsApi } from '../../api/tenants'
```

Update `GroupModal` — add `tenants` prop and inject tenant select. The updated component signature and added field:

Replace the `function GroupModal({ group, onClose, onSave })` function entirely with:

```jsx
function GroupModal({ group, onClose, onSave, tenants }) {
  const [form, setForm] = useState({
    telegramChatId: group?.telegramChatId ?? '',
    name: group?.name ?? '',
    description: group?.description ?? '',
    moderationLevel: group?.moderationLevel ?? 'LOW',
    autoRespond: group?.autoRespond ?? false,
    welcomeMessage: group?.welcomeMessage ?? '',
    tenantId: group?.tenantId ?? '',
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={group ? 'Edit Group' : 'Add Group'} onClose={onClose} onSubmit={() => onSave(form)}>
      {!group && (
        <>
          <label>Telegram Chat ID *</label>
          <input type="number" value={form.telegramChatId}
            onChange={e => set('telegramChatId', parseInt(e.target.value, 10))}
            className={styles.input} required />
        </>
      )}
      <label>Name *</label>
      <input type="text" value={form.name} onChange={e => set('name', e.target.value)}
        className={styles.input} required />
      <label>Description</label>
      <input type="text" value={form.description}
        onChange={e => set('description', e.target.value)} className={styles.input} />
      <label>Moderation Level</label>
      <select value={form.moderationLevel}
        onChange={e => set('moderationLevel', e.target.value)} className={styles.input}>
        {['LOW', 'MEDIUM', 'HIGH', 'STRICT'].map(l => <option key={l}>{l}</option>)}
      </select>
      <label>
        <input type="checkbox" checked={form.autoRespond}
          onChange={e => set('autoRespond', e.target.checked)} /> Auto-respond
      </label>
      <label>Welcome Message</label>
      <textarea value={form.welcomeMessage}
        onChange={e => set('welcomeMessage', e.target.value)} className={styles.input} rows={3} />
      <label>Tenant</label>
      <select value={form.tenantId ?? ''}
        onChange={e => set('tenantId', e.target.value || null)} className={styles.input}>
        <option value="">— none —</option>
        {tenants.map(t => (
          <option key={t.id} value={t.id}>
            {t.name} ({t.id.slice(0, 8)})
          </option>
        ))}
      </select>
    </Modal>
  )
}
```

In the `Groups` component body, add tenant loading. After the existing `const [error, setError] = useState('')` line, add:

```js
  const [tenants, setTenants] = useState([])
```

After the existing `useEffect(() => { load() }, [])` line, add:

```js
  useEffect(() => {
    tenantsApi(makeRequest(token)).list().then(setTenants).catch(() => {})
  }, [])
```

In the JSX, find the `{modal && <GroupModal .../>}` line and update it to pass `tenants`:

```jsx
      {modal && <GroupModal group={modal === 'add' ? null : modal} onClose={() => setModal(null)} onSave={save} tenants={tenants} />}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/pages/Groups/Groups.tenant.test.jsx 2>&1 | tail -5
```

Expected: 1 test passing.

- [ ] **Step 5: Run all existing Groups tests to confirm no regressions**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/pages/Groups/ 2>&1 | tail -5
```

Expected: all passing.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Groups/
git commit -m "feat(admin-ui): Groups page — tenant dropdown in create/edit modal"
```

---

### Task 10: Frontend — Tenant dropdown in PolicyRules page

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.jsx`

- [ ] **Step 1: Write the failing test**

Create `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.tenant.test.jsx`:

```jsx
import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { PolicyRules } from './PolicyRules'

const mockRules = []
const mockTenants = [
  { id: '22222222-0000-0000-0000-000000000000', name: 'Beta Corp' },
]

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'test-token' }) }))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

vi.mock('../../api/policyRules', () => ({
  policyRulesApi: () => ({
    list: vi.fn().mockResolvedValue(mockRules),
    create: vi.fn().mockResolvedValue({}),
    update: vi.fn().mockResolvedValue({}),
    remove: vi.fn().mockResolvedValue(null),
    history: vi.fn().mockResolvedValue([]),
  }),
}))

vi.mock('../../api/tenants', () => ({
  tenantsApi: () => ({
    list: vi.fn().mockResolvedValue(mockTenants),
  }),
}))

describe('PolicyRules page — tenant dropdown', () => {
  it('shows tenant dropdown in Create Rule modal', async () => {
    render(<PolicyRules />)
    await waitFor(() => screen.getByText('+ Create Rule'))
    screen.getByRole('button', { name: /create rule/i }).click()
    await waitFor(() => {
      expect(screen.getByText(/beta corp/i)).toBeInTheDocument()
    })
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/pages/PolicyRules/PolicyRules.tenant.test.jsx 2>&1 | tail -10
```

Expected: FAIL — tenant dropdown not rendered.

- [ ] **Step 3: Update PolicyRules.jsx**

Open `emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/PolicyRules.jsx`.

Add import for `tenantsApi` at the top:
```js
import { tenantsApi } from '../../api/tenants'
```

Update `RuleModal` to accept `tenants` prop. Replace `function RuleModal({ rule, onClose, onSave })` entirely with:

```jsx
function RuleModal({ rule, onClose, onSave, tenants }) {
  const [form, setForm] = useState({
    ruleName: rule?.ruleName ?? '',
    ruleType: rule?.ruleType ?? 'KEYWORD',
    action: rule?.action ?? 'FLAG',
    parameters: rule?.parameters ?? '',
    effectiveFrom: rule?.effectiveFrom?.slice(0, 16) ?? '',
    effectiveTo: rule?.effectiveTo?.slice(0, 16) ?? '',
    tenantId: rule?.tenantId ?? '',
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={rule ? 'Edit Rule' : 'Create Rule'} onClose={onClose} onSubmit={() => onSave(form)}>
      <label>Rule Name *</label>
      <input type="text" value={form.ruleName} onChange={e => set('ruleName', e.target.value)}
        className={styles.input} required disabled={!!rule} />
      <label>Rule Type</label>
      <select value={form.ruleType} onChange={e => set('ruleType', e.target.value)} className={styles.input}>
        {['KEYWORD', 'REGEX', 'SENTIMENT', 'INTENT', 'COMPOSITE'].map(t => <option key={t}>{t}</option>)}
      </select>
      <label>Action</label>
      <select value={form.action} onChange={e => set('action', e.target.value)} className={styles.input}>
        {['FLAG', 'WARN', 'MUTE', 'BAN', 'DELETE', 'ESCALATE'].map(a => <option key={a}>{a}</option>)}
      </select>
      <label>Parameters (JSON)</label>
      <textarea value={form.parameters} onChange={e => set('parameters', e.target.value)}
        className={styles.input} rows={4} placeholder='{"keywords":["spam"]}' />
      <label>Effective From</label>
      <input type="datetime-local" value={form.effectiveFrom}
        onChange={e => set('effectiveFrom', e.target.value)} className={styles.input} />
      <label>Effective To</label>
      <input type="datetime-local" value={form.effectiveTo}
        onChange={e => set('effectiveTo', e.target.value)} className={styles.input} />
      <label>Tenant</label>
      <select value={form.tenantId ?? ''}
        onChange={e => set('tenantId', e.target.value || null)} className={styles.input}>
        <option value="">— none —</option>
        {tenants.map(t => (
          <option key={t.id} value={t.id}>
            {t.name} ({t.id.slice(0, 8)})
          </option>
        ))}
      </select>
    </Modal>
  )
}
```

In the `PolicyRules` component body, add tenant loading. After `const [error, setError] = useState('')`:

```js
  const [tenants, setTenants] = useState([])
```

After the `useEffect(() => { load() }, [])`:

```js
  useEffect(() => {
    tenantsApi(makeRequest(token)).list().then(setTenants).catch(() => {})
  }, [])
```

In the JSX, update the RuleModal render call to pass `tenants`:

```jsx
      {modal && <RuleModal rule={modal === 'add' ? null : modal} onClose={() => setModal(null)} onSave={save} tenants={tenants} />}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run src/pages/PolicyRules/PolicyRules.tenant.test.jsx 2>&1 | tail -5
```

Expected: 1 test passing.

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/
git commit -m "feat(admin-ui): PolicyRules page — tenant dropdown in create/edit modal"
```

---

### Task 11: Frontend — Sidebar nav + App.jsx routing for new pages

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/App.jsx`

- [ ] **Step 1: Update Sidebar.jsx nav items**

Open `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`.

Find the `NAV` array:
```js
const NAV = [
  { to: '/tenants',      label: 'Tenants',       icon: '⬡' },
  { to: '/policy-rules', label: 'Policy Rules',   icon: '⚖' },
  { to: '/groups',       label: 'Groups',         icon: '◈' },
  { to: '/audit-log',    label: 'Audit Log',      icon: '◎' },
  { to: '/simulate',     label: 'Simulate Event', icon: '▶' },
]
```

Replace with:
```js
const NAV = [
  { to: '/tenants',      label: 'Tenants',       icon: '⬡' },
  { to: '/policy-rules', label: 'Policy Rules',   icon: '⚖' },
  { to: '/groups',       label: 'Groups',         icon: '◈' },
  { to: '/audit-log',    label: 'Audit Log',      icon: '◎' },
  { to: '/simulate',     label: 'Simulate Event', icon: '▶' },
  { to: '/telegram',     label: 'Telegram',       icon: '⌘' },
  { to: '/ai-config',    label: 'AI Config',      icon: '✦' },
]
```

- [ ] **Step 2: Update App.jsx routes**

Open `emcip-admin-ui/src/main/frontend/src/App.jsx`.

Add imports after the existing page imports:
```js
import { Telegram } from './pages/Telegram/Telegram'
import { AIConfig } from './pages/AIConfig/AIConfig'
```

Add routes inside the `<Route element={<AppShell />}>` block, after the simulate route:
```jsx
        <Route path="telegram" element={<Telegram />} />
        <Route path="ai-config" element={<AIConfig />} />
```

The complete routes block should now be:
```jsx
      <Routes>
        <Route element={<AppShell />}>
          <Route index element={<Navigate to="/tenants" replace />} />
          <Route path="tenants" element={<Tenants />} />
          <Route path="policy-rules" element={<PolicyRules />} />
          <Route path="groups" element={<Groups />} />
          <Route path="audit-log" element={<AuditLog />} />
          <Route path="simulate" element={<Simulate />} />
          <Route path="telegram" element={<Telegram />} />
          <Route path="ai-config" element={<AIConfig />} />
        </Route>
      </Routes>
```

- [ ] **Step 3: Run all frontend tests**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm test -- --run 2>&1 | tail -10
```

Expected: all tests passing (at minimum the 12 from Plan 1 + 10 new from this plan = 22 total).

- [ ] **Step 4: Verify Vite build compiles without errors**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npm run build 2>&1 | tail -5
```

Expected: `✓ built in Xs` with no errors.

- [ ] **Step 5: Apply Spotless on all Java changes and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-api,emcip-llm-orchestrator -q
git add emcip-admin-ui/src/main/frontend/src/layout/Sidebar/
git add emcip-admin-ui/src/main/frontend/src/App.jsx
git commit -m "feat(admin-ui): wire Telegram + AIConfig routes and sidebar nav items"
```

---

## Verification Checklist

After all tasks are complete, verify end-to-end:

```bash
# 1. All frontend tests pass
cd emcip-admin-ui/src/main/frontend && npm test -- --run

# 2. Backend compiles clean
cd /home/ben/Development/ecip
mvn compile -pl emcip-admin-api,emcip-llm-orchestrator -q

# 3. Spotless clean
mvn spotless:check -pl emcip-admin-api,emcip-llm-orchestrator -q

# 4. Full Maven build (runs Vite too)
mvn package -pl emcip-admin-ui,emcip-admin-api -DskipTests -q
```
