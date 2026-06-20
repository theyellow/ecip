# Epic 42 — Knowledge Enrichment: Admin-API REST Layer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add all admin-api endpoints for Epic 42: vendor API key CRUD (global + per-tenant), enrichment source configuration, manual trigger, run status polling, and Kafka trigger publisher. Also add the two new permission constants to the backend.

**Architecture:** `emcip-admin-api` is reactive (WebFlux + R2DBC). All new entities use `@Table` / Spring Data `@Id` / `Mono` / `Flux`. The new `knowledge.enrichment.trigger` Kafka topic is produced from here; `knowledge-engine` consumes it (Plan A.1 Task 9).

**Prerequisite:** Plan A.1 complete — migrations `ke-11`–`ke-14` must already exist in the DB.

**Tech Stack:** Java 21, Spring Boot 4, WebFlux, R2DBC, Spring Security (`@PreAuthorize`), Kafka producer, `StepVerifier` for reactive tests, `@WebFluxTest` for controller tests.

**Spec:** `docs/superpowers/specs/2026-06-19-42-knowledge-enrichment-connectors-design.md`

---

## File Map

**New — R2DBC entities** (`io.emcip.admin.api.integration`)
- `VendorApiKeyRow.java`
- `EnrichmentSourceRow.java`
- `EnrichmentRunRow.java`

**New — R2DBC repositories**
- `VendorApiKeyRowRepository.java`
- `EnrichmentSourceRowRepository.java`
- `EnrichmentRunRowRepository.java`

**New — DTOs**
- `dto/VendorApiKeyRequest.java`
- `dto/VendorApiKeyResponse.java`
- `dto/EnrichmentSourceResponse.java`
- `dto/TriggerResponse.java`
- `dto/RunStatusResponse.java`

**New — services**
- `VendorApiKeyService.java`
- `EnrichmentSourceService.java`
- `EnrichmentTriggerPublisher.java`

**New — controllers**
- `VendorApiKeyController.java` — `/api/v1/admin/integrations/keys`
- `TenantApiKeyController.java` — `/api/v1/tenant/integrations/keys`
- `EnrichmentSourceController.java` — `/api/v1/admin/integrations/sources`

**Modify — permissions**
- `emcip-admin-api/src/main/java/io/emcip/admin/api/security/Permission.java`
- `emcip-admin-api/src/main/java/io/emcip/admin/api/security/RolePermissions.java`

**New — tests**
- `src/test/java/io/emcip/admin/api/integration/VendorApiKeyServiceTest.java`
- `src/test/java/io/emcip/admin/api/integration/VendorApiKeyControllerTest.java`
- `src/test/java/io/emcip/admin/api/integration/EnrichmentSourceControllerTest.java`
- `src/test/java/io/emcip/admin/api/security/RolePermissionsTest.java` — extend existing

---

## Task 1: R2DBC entities

All entities map to the tables created by Plan A.1 migrations. `tenant_id IS NULL` means global.

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/VendorApiKeyRow.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/EnrichmentSourceRow.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/EnrichmentRunRow.java`

- [ ] **Step 1: Create VendorApiKeyRow.java**

```java
package io.emcip.admin.api.integration;

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

@Table("ke_vendor_api_keys")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorApiKeyRow {

    @Id
    private UUID id;

    @Column("vendor_id")
    private String vendorId;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("api_key")
    private String apiKey;

    @Column("enabled")
    private boolean enabled;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
```

- [ ] **Step 2: Create EnrichmentSourceRow.java**

R2DBC does not support JSONB natively — the `config` column is mapped as `String` and serialised/deserialised in the service layer.

```java
package io.emcip.admin.api.integration;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ke_enrichment_sources")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentSourceRow {

    @Id
    private UUID id;

    @Column("vendor_id")
    private String vendorId;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("enabled")
    private boolean enabled;

    @Column("schedule_cron")
    private String scheduleCron;

    @Column("last_run_at")
    private Instant lastRunAt;

    @Column("last_run_status")
    private String lastRunStatus;

    @Column("config")
    private String config; // raw JSON string

    @Version
    @Column("version")
    private long version;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
```

- [ ] **Step 3: Create EnrichmentRunRow.java**

```java
package io.emcip.admin.api.integration;

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

@Table("ke_enrichment_runs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentRunRow {

    @Id
    private UUID id;

    @Column("source_id")
    private UUID sourceId;

    @Column("trigger_type")
    private String triggerType;

    @Column("started_at")
    private Instant startedAt;

    @Column("completed_at")
    private Instant completedAt;

    @Column("status")
    private String status;

    @Column("items_fetched")
    private int itemsFetched;

    @Column("items_ingested")
    private int itemsIngested;

    @Column("error_message")
    private String errorMessage;
}
```

- [ ] **Step 4: Compile check**

```bash
cd emcip-admin-api
mvn compile -q | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/integration/
git commit -m "feat(42): add R2DBC row entities for vendor keys, enrichment sources, runs"
```

---

## Task 2: R2DBC repositories

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/VendorApiKeyRowRepository.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/EnrichmentSourceRowRepository.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/EnrichmentRunRowRepository.java`

- [ ] **Step 1: Create VendorApiKeyRowRepository.java**

```java
package io.emcip.admin.api.integration;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface VendorApiKeyRowRepository extends ReactiveCrudRepository<VendorApiKeyRow, UUID> {

    Flux<VendorApiKeyRow> findAllByTenantIdIsNull();

    Flux<VendorApiKeyRow> findAllByTenantId(UUID tenantId);

    Mono<VendorApiKeyRow> findByVendorIdAndTenantIdIsNull(String vendorId);

    Mono<VendorApiKeyRow> findByVendorIdAndTenantId(String vendorId, UUID tenantId);
}
```

- [ ] **Step 2: Create EnrichmentSourceRowRepository.java**

```java
package io.emcip.admin.api.integration;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface EnrichmentSourceRowRepository extends ReactiveCrudRepository<EnrichmentSourceRow, UUID> {

    Flux<EnrichmentSourceRow> findAllByTenantIdIsNull();

    Flux<EnrichmentSourceRow> findAllByTenantId(UUID tenantId);
}
```

- [ ] **Step 3: Create EnrichmentRunRowRepository.java**

```java
package io.emcip.admin.api.integration;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface EnrichmentRunRowRepository extends ReactiveCrudRepository<EnrichmentRunRow, UUID> {

    Flux<EnrichmentRunRow> findBySourceIdOrderByStartedAtDesc(UUID sourceId, Pageable pageable);
}
```

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/integration/
git commit -m "feat(42): add R2DBC reactive repositories for integrations"
```

---

## Task 3: DTOs

**Files (package `io.emcip.admin.api.integration.dto`):**
- `VendorApiKeyRequest.java`
- `VendorApiKeyResponse.java`
- `EnrichmentSourceResponse.java`
- `TriggerResponse.java`
- `RunStatusResponse.java`

- [ ] **Step 1: Create VendorApiKeyRequest.java**

```java
package io.emcip.admin.api.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VendorApiKeyRequest(
        @NotBlank String vendorId,
        @NotBlank @Size(max = 512) String apiKey,
        boolean enabled) {}
```

- [ ] **Step 2: Create VendorApiKeyResponse.java**

The `apiKey` field is never returned raw. Always masked to the last 4 characters.

```java
package io.emcip.admin.api.integration.dto;

import java.time.Instant;
import java.util.UUID;

public record VendorApiKeyResponse(
        UUID id,
        String vendorId,
        UUID tenantId,
        String maskedKey,   // "••••••••7f3a" — last 4 chars; null if no key set
        boolean enabled,
        Instant updatedAt) {

    /** Build from a row entity. Never exposes the raw key. */
    public static VendorApiKeyResponse from(
            io.emcip.admin.api.integration.VendorApiKeyRow row) {
        return new VendorApiKeyResponse(
                row.getId(),
                row.getVendorId(),
                row.getTenantId(),
                maskKey(row.getApiKey()),
                row.isEnabled(),
                row.getUpdatedAt());
    }

    private static String maskKey(String raw) {
        if (raw == null || raw.length() < 4) return "••••";
        return "••••••••" + raw.substring(raw.length() - 4);
    }
}
```

- [ ] **Step 3: Create EnrichmentSourceResponse.java**

```java
package io.emcip.admin.api.integration.dto;

import java.time.Instant;
import java.util.UUID;

public record EnrichmentSourceResponse(
        UUID id,
        String vendorId,
        UUID tenantId,
        boolean enabled,
        String scheduleCron,
        Instant lastRunAt,
        String lastRunStatus,
        long version) {

    public static EnrichmentSourceResponse from(
            io.emcip.admin.api.integration.EnrichmentSourceRow row) {
        return new EnrichmentSourceResponse(
                row.getId(),
                row.getVendorId(),
                row.getTenantId(),
                row.isEnabled(),
                row.getScheduleCron(),
                row.getLastRunAt(),
                row.getLastRunStatus(),
                row.getVersion());
    }
}
```

- [ ] **Step 4: Create TriggerResponse.java**

```java
package io.emcip.admin.api.integration.dto;

import java.util.UUID;

public record TriggerResponse(UUID runId) {}
```

- [ ] **Step 5: Create RunStatusResponse.java**

```java
package io.emcip.admin.api.integration.dto;

import java.time.Instant;
import java.util.UUID;

public record RunStatusResponse(
        UUID id,
        UUID sourceId,
        String triggerType,
        Instant startedAt,
        Instant completedAt,
        String status,
        int itemsFetched,
        int itemsIngested,
        String errorMessage) {

    public static RunStatusResponse from(
            io.emcip.admin.api.integration.EnrichmentRunRow row) {
        return new RunStatusResponse(
                row.getId(),
                row.getSourceId(),
                row.getTriggerType(),
                row.getStartedAt(),
                row.getCompletedAt(),
                row.getStatus(),
                row.getItemsFetched(),
                row.getItemsIngested(),
                row.getErrorMessage());
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/integration/dto/
git commit -m "feat(42): add integration DTOs (VendorApiKey, EnrichmentSource, RunStatus)"
```

---

## Task 4: VendorApiKeyService + test

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/VendorApiKeyService.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/integration/VendorApiKeyServiceTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.emcip.admin.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.admin.api.integration.dto.VendorApiKeyResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class VendorApiKeyServiceTest {

    @Mock private VendorApiKeyRowRepository repo;

    private VendorApiKeyService service;

    @BeforeEach
    void setUp() {
        service = new VendorApiKeyService(repo);
    }

    @Test
    void listGlobal_returnsAllNullTenantRows() {
        VendorApiKeyRow row = VendorApiKeyRow.builder()
                .id(UUID.randomUUID())
                .vendorId("exa")
                .apiKey("secret-key-1234")
                .enabled(true)
                .build();
        when(repo.findAllByTenantIdIsNull()).thenReturn(Flux.just(row));

        StepVerifier.create(service.listGlobal())
                .assertNext(r -> {
                    assertThat(r.vendorId()).isEqualTo("exa");
                    assertThat(r.maskedKey()).isEqualTo("••••••••1234");
                    assertThat(r.maskedKey()).doesNotContain("secret");
                })
                .verifyComplete();
    }

    @Test
    void create_savesRowAndReturnsMasked() {
        VendorApiKeyRequest req = new VendorApiKeyRequest("brave", "my-api-key-5678", true);
        VendorApiKeyRow saved = VendorApiKeyRow.builder()
                .id(UUID.randomUUID())
                .vendorId("brave")
                .apiKey("my-api-key-5678")
                .enabled(true)
                .build();
        when(repo.save(any())).thenReturn(Mono.just(saved));

        StepVerifier.create(service.createGlobal(req))
                .assertNext(r -> {
                    assertThat(r.maskedKey()).endsWith("5678");
                    assertThat(r.maskedKey()).doesNotContain("my-api-key");
                })
                .verifyComplete();
    }
}
```

- [ ] **Step 2: Run — verify it fails**

```bash
cd emcip-admin-api
mvn test -pl . -Dtest=VendorApiKeyServiceTest | cat
```

Expected: FAIL — `VendorApiKeyService` does not exist.

- [ ] **Step 3: Create VendorApiKeyService.java**

```java
package io.emcip.admin.api.integration;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.admin.api.integration.dto.VendorApiKeyResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@RequiredArgsConstructor
public class VendorApiKeyService {

    private final VendorApiKeyRowRepository repo;

    public Flux<VendorApiKeyResponse> listGlobal() {
        return repo.findAllByTenantIdIsNull().map(VendorApiKeyResponse::from);
    }

    public Flux<VendorApiKeyResponse> listByTenant(UUID tenantId) {
        return repo.findAllByTenantId(tenantId).map(VendorApiKeyResponse::from);
    }

    public Mono<VendorApiKeyResponse> createGlobal(VendorApiKeyRequest req) {
        VendorApiKeyRow row = VendorApiKeyRow.builder()
                .vendorId(req.vendorId())
                .apiKey(req.apiKey())
                .enabled(req.enabled())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return repo.save(row).map(VendorApiKeyResponse::from);
    }

    public Mono<VendorApiKeyResponse> upsertForTenant(String vendorId, UUID tenantId, VendorApiKeyRequest req) {
        return repo.findByVendorIdAndTenantId(vendorId, tenantId)
                .flatMap(existing -> {
                    existing.setApiKey(req.apiKey());
                    existing.setEnabled(req.enabled());
                    existing.setUpdatedAt(Instant.now());
                    return repo.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    VendorApiKeyRow row = VendorApiKeyRow.builder()
                            .vendorId(vendorId)
                            .tenantId(tenantId)
                            .apiKey(req.apiKey())
                            .enabled(req.enabled())
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();
                    return repo.save(row);
                }))
                .map(VendorApiKeyResponse::from);
    }

    public Mono<VendorApiKeyResponse> update(UUID id, VendorApiKeyRequest req) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not found")))
                .flatMap(row -> {
                    row.setApiKey(req.apiKey());
                    row.setEnabled(req.enabled());
                    row.setUpdatedAt(Instant.now());
                    return repo.save(row);
                })
                .map(VendorApiKeyResponse::from);
    }

    public Mono<Void> delete(UUID id) {
        return repo.deleteById(id);
    }

    public Mono<Void> deleteByVendorAndTenant(String vendorId, UUID tenantId) {
        return repo.findByVendorIdAndTenantId(vendorId, tenantId)
                .flatMap(row -> repo.deleteById(row.getId()));
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
cd emcip-admin-api
mvn test -pl . -Dtest=VendorApiKeyServiceTest | cat
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/integration/VendorApiKeyService.java \
        emcip-admin-api/src/test/
git commit -m "feat(42): add VendorApiKeyService with masked key responses"
```

---

## Task 5: EnrichmentSourceService + EnrichmentTriggerPublisher

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/EnrichmentSourceService.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/EnrichmentTriggerPublisher.java`

- [ ] **Step 1: Create EnrichmentTriggerPublisher.java**

Publishes to `knowledge.enrichment.trigger` so `knowledge-engine` picks it up and executes the run.

```java
package io.emcip.admin.api.integration;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class EnrichmentTriggerPublisher {

    private static final String TOPIC = "knowledge.enrichment.trigger";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(UUID sourceId, UUID runId) {
        try {
            String payload = objectMapper.writeValueAsString(
                    Map.of("sourceId", sourceId.toString(), "runId", runId.toString()));
            kafkaTemplate.send(TOPIC, sourceId.toString(), payload);
            log.debug("Published enrichment trigger: sourceId={} runId={}", sourceId, runId);
        } catch (Exception e) {
            log.error("Failed to publish enrichment trigger: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to publish enrichment trigger", e);
        }
    }
}
```

- [ ] **Step 2: Create EnrichmentSourceService.java**

```java
package io.emcip.admin.api.integration;

import io.emcip.admin.api.integration.dto.EnrichmentSourceResponse;
import io.emcip.admin.api.integration.dto.RunStatusResponse;
import io.emcip.admin.api.integration.dto.TriggerResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@RequiredArgsConstructor
public class EnrichmentSourceService {

    private final EnrichmentSourceRowRepository sourceRepo;
    private final EnrichmentRunRowRepository runRepo;
    private final EnrichmentTriggerPublisher triggerPublisher;

    public Flux<EnrichmentSourceResponse> listAll() {
        return sourceRepo.findAllByTenantIdIsNull().map(EnrichmentSourceResponse::from);
    }

    /** Trigger a manual run: create a RUNNING run row, publish Kafka event, return runId. */
    public Mono<TriggerResponse> triggerManual(UUID sourceId) {
        return sourceRepo.findById(sourceId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found")))
                .flatMap(source -> {
                    EnrichmentRunRow run = new EnrichmentRunRow();
                    run.setSourceId(sourceId);
                    run.setTriggerType("MANUAL");
                    run.setStatus("RUNNING");
                    run.setStartedAt(Instant.now());
                    run.setItemsFetched(0);
                    run.setItemsIngested(0);
                    return runRepo.save(run);
                })
                .map(run -> {
                    triggerPublisher.publish(sourceId, run.getId());
                    return new TriggerResponse(run.getId());
                });
    }

    public Flux<RunStatusResponse> listRuns(UUID sourceId, int page, int size) {
        return runRepo.findBySourceIdOrderByStartedAtDesc(sourceId, PageRequest.of(page, size))
                .map(RunStatusResponse::from);
    }

    public Mono<RunStatusResponse> getRun(UUID runId) {
        return runRepo.findById(runId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found")))
                .map(RunStatusResponse::from);
    }
}
```

- [ ] **Step 3: Compile check**

```bash
cd emcip-admin-api
mvn compile -q | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/integration/
git commit -m "feat(42): add EnrichmentSourceService and EnrichmentTriggerPublisher"
```

---

## Task 6: Controllers + tests

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/VendorApiKeyController.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/TenantApiKeyController.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/EnrichmentSourceController.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/integration/VendorApiKeyControllerTest.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/integration/EnrichmentSourceControllerTest.java`

- [ ] **Step 1: Write failing controller tests**

```java
package io.emcip.admin.api.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.admin.api.integration.dto.VendorApiKeyResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(VendorApiKeyController.class)
class VendorApiKeyControllerTest {

    @Autowired WebTestClient client;
    @MockBean VendorApiKeyService service;

    @Test
    @WithMockUser(authorities = "INTEGRATIONS_GLOBAL_MANAGE")
    void listGlobal_returns200() {
        VendorApiKeyResponse resp = new VendorApiKeyResponse(
                UUID.randomUUID(), "exa", null, "••••••••1234", true, null);
        when(service.listGlobal()).thenReturn(Flux.just(resp));

        client.get().uri("/api/v1/admin/integrations/keys")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VendorApiKeyResponse.class).hasSize(1);
    }

    @Test
    @WithMockUser(authorities = "INTEGRATIONS_TENANT_MANAGE")
    void listGlobal_returns403_forTenantAdminRole() {
        client.get().uri("/api/v1/admin/integrations/keys")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @WithMockUser(authorities = "INTEGRATIONS_GLOBAL_MANAGE")
    void create_returns201() {
        VendorApiKeyRequest req = new VendorApiKeyRequest("exa", "my-key-abcd", true);
        VendorApiKeyResponse resp = new VendorApiKeyResponse(
                UUID.randomUUID(), "exa", null, "••••••••abcd", true, null);
        when(service.createGlobal(any())).thenReturn(Mono.just(resp));

        client.post().uri("/api/v1/admin/integrations/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void listGlobal_returns401_whenUnauthenticated() {
        client.get().uri("/api/v1/admin/integrations/keys")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
```

```java
package io.emcip.admin.api.integration;

import static org.mockito.Mockito.when;

import io.emcip.admin.api.integration.dto.EnrichmentSourceResponse;
import io.emcip.admin.api.integration.dto.TriggerResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(EnrichmentSourceController.class)
class EnrichmentSourceControllerTest {

    @Autowired WebTestClient client;
    @MockBean EnrichmentSourceService service;

    @Test
    @WithMockUser(authorities = "INTEGRATIONS_GLOBAL_MANAGE")
    void listSources_returns200() {
        EnrichmentSourceResponse src = new EnrichmentSourceResponse(
                UUID.randomUUID(), "wikipedia", null, true, "0 17 3 * * *", null, null, 0L);
        when(service.listAll()).thenReturn(Flux.just(src));

        client.get().uri("/api/v1/admin/integrations/sources")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(EnrichmentSourceResponse.class).hasSize(1);
    }

    @Test
    @WithMockUser(authorities = "INTEGRATIONS_GLOBAL_MANAGE")
    void trigger_returns202WithRunId() {
        UUID sourceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(service.triggerManual(sourceId)).thenReturn(Mono.just(new TriggerResponse(runId)));

        client.post()
                .uri("/api/v1/admin/integrations/sources/{id}/trigger", sourceId)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(TriggerResponse.class)
                .value(r -> r.runId().equals(runId));
    }
}
```

- [ ] **Step 2: Run — verify tests fail**

```bash
cd emcip-admin-api
mvn test -pl . -Dtest="VendorApiKeyControllerTest,EnrichmentSourceControllerTest" | cat
```

Expected: FAIL — controllers do not exist.

- [ ] **Step 3: Create VendorApiKeyController.java**

```java
package io.emcip.admin.api.integration;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.admin.api.integration.dto.VendorApiKeyResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/admin/integrations/keys")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('INTEGRATIONS_GLOBAL_MANAGE')")
@Tag(name = "Integrations — Global Keys", description = "Manage global vendor API keys")
public class VendorApiKeyController {

    private final VendorApiKeyService service;

    @GetMapping
    public Flux<VendorApiKeyResponse> list(@RequestParam(required = false) UUID tenantId) {
        return tenantId != null ? service.listByTenant(tenantId) : service.listGlobal();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VendorApiKeyResponse> create(@Valid @RequestBody VendorApiKeyRequest req) {
        return service.createGlobal(req);
    }

    @PutMapping("/{id}")
    public Mono<VendorApiKeyResponse> update(
            @PathVariable UUID id, @Valid @RequestBody VendorApiKeyRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
```

- [ ] **Step 4: Create TenantApiKeyController.java**

```java
package io.emcip.admin.api.integration;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.admin.api.integration.dto.VendorApiKeyResponse;
import io.emcip.common.tenant.ReactorTenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/tenant/integrations/keys")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('INTEGRATIONS_TENANT_MANAGE')")
@Tag(name = "Integrations — Tenant Keys", description = "Manage own tenant vendor API keys")
public class TenantApiKeyController {

    private final VendorApiKeyService service;

    @GetMapping
    public Flux<VendorApiKeyResponse> listOwn() {
        return ReactorTenantContext.getTenantId()
                .flatMapMany(service::listByTenant);
    }

    @PutMapping("/{vendorId}")
    public Mono<VendorApiKeyResponse> upsert(
            @PathVariable String vendorId, @Valid @RequestBody VendorApiKeyRequest req) {
        return ReactorTenantContext.getTenantId()
                .flatMap(tenantId -> service.upsertForTenant(vendorId, tenantId, req));
    }

    @DeleteMapping("/{vendorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String vendorId) {
        return ReactorTenantContext.getTenantId()
                .flatMap(tenantId -> service.deleteByVendorAndTenant(vendorId, tenantId));
    }
}
```

- [ ] **Step 5: Create EnrichmentSourceController.java**

```java
package io.emcip.admin.api.integration;

import io.emcip.admin.api.integration.dto.EnrichmentSourceResponse;
import io.emcip.admin.api.integration.dto.RunStatusResponse;
import io.emcip.admin.api.integration.dto.TriggerResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/admin/integrations/sources")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('INTEGRATIONS_GLOBAL_MANAGE')")
@Tag(name = "Integrations — Sources", description = "Manage enrichment sources and run history")
public class EnrichmentSourceController {

    private final EnrichmentSourceService service;

    @GetMapping
    public Flux<EnrichmentSourceResponse> list() {
        return service.listAll();
    }

    @PostMapping("/{id}/trigger")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<TriggerResponse> trigger(@PathVariable UUID id) {
        return service.triggerManual(id);
    }

    @GetMapping("/{id}/runs")
    public Flux<RunStatusResponse> listRuns(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listRuns(id, page, size);
    }

    @GetMapping("/{id}/runs/{runId}")
    public Mono<RunStatusResponse> getRun(
            @PathVariable UUID id, @PathVariable UUID runId) {
        return service.getRun(runId);
    }
}
```

- [ ] **Step 6: Run controller tests — verify they pass**

```bash
cd emcip-admin-api
mvn test -pl . -Dtest="VendorApiKeyControllerTest,EnrichmentSourceControllerTest" | cat
```

Expected: `Tests run: 6, Failures: 0, Errors: 0` (combined)

- [ ] **Step 7: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/integration/ \
        emcip-admin-api/src/test/
git commit -m "feat(42): add VendorApiKeyController, TenantApiKeyController, EnrichmentSourceController"
```

---

## Task 7: Permission constants

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/Permission.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/security/RolePermissions.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/security/RolePermissionsTest.java`

- [ ] **Step 1: Write failing test additions**

Open `RolePermissionsTest.java` and add two new `@Test` methods:

```java
@Test
void tenantAdmin_hasIntegrationsTenantManage() {
    Set<Permission> perms = RolePermissions.permissionsFor(Role.TENANT_ADMIN);
    assertThat(perms).contains(Permission.INTEGRATIONS_TENANT_MANAGE);
}

@Test
void tenantAdmin_lacksIntegrationsGlobalManage() {
    Set<Permission> perms = RolePermissions.permissionsFor(Role.TENANT_ADMIN);
    assertThat(perms).doesNotContain(Permission.INTEGRATIONS_GLOBAL_MANAGE);
}
```

- [ ] **Step 2: Run — verify new tests fail**

```bash
cd emcip-admin-api
mvn test -pl . -Dtest=RolePermissionsTest | cat
```

Expected: FAIL — `Permission.INTEGRATIONS_TENANT_MANAGE` does not exist.

- [ ] **Step 3: Add constants to Permission.java**

Read `Permission.java`, then add two entries at the end of the enum before the closing `}`:

```java
    COSTS_READ,
    RESOLUTION_REVIEW_READ,
    RESOLUTION_REVIEW_WRITE,
    KNOWLEDGE_READ,
    KNOWLEDGE_WRITE,
    INTEGRATIONS_GLOBAL_MANAGE,
    INTEGRATIONS_TENANT_MANAGE
```

Note: `COSTS_READ`, `RESOLUTION_REVIEW_*`, and `KNOWLEDGE_*` may already exist in `permissions.js` but are missing from the Java enum — add them all here to fix the pre-existing gap while touching this file.

- [ ] **Step 4: Update RolePermissions.java**

Read `RolePermissions.java`. Update `TENANT_ADMIN_PERMISSIONS` to add the new constant:

```java
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
```

`ADMIN` uses `EnumSet.allOf(Permission.class)` so it gets both constants automatically.

- [ ] **Step 5: Run all permission tests — verify they pass**

```bash
cd emcip-admin-api
mvn test -pl . -Dtest=RolePermissionsTest | cat
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: Run full admin-api test suite**

```bash
cd emcip-admin-api
mvn test | cat
```

Expected: all tests pass.

- [ ] **Step 7: Apply Spotless**

```bash
cd emcip-admin-api
mvn spotless:apply | cat
```

If any files changed: `git add -A && git commit -m "style: apply spotless"`

- [ ] **Step 8: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/security/ \
        emcip-admin-api/src/test/java/io/emcip/admin/api/security/
git commit -m "feat(42): add INTEGRATIONS_GLOBAL_MANAGE and INTEGRATIONS_TENANT_MANAGE permissions"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Covered in task |
|---|---|
| `GET /api/v1/admin/integrations/keys` | Task 6 — VendorApiKeyController |
| `POST /api/v1/admin/integrations/keys` | Task 6 — VendorApiKeyController |
| `PUT /api/v1/admin/integrations/keys/{id}` | Task 6 — VendorApiKeyController |
| `DELETE /api/v1/admin/integrations/keys/{id}` | Task 6 — VendorApiKeyController |
| `GET /api/v1/tenant/integrations/keys` | Task 6 — TenantApiKeyController |
| `PUT /api/v1/tenant/integrations/keys/{vendorId}` | Task 6 — TenantApiKeyController |
| `DELETE /api/v1/tenant/integrations/keys/{vendorId}` | Task 6 — TenantApiKeyController |
| `GET /api/v1/admin/integrations/sources` | Task 6 — EnrichmentSourceController |
| `POST /api/v1/admin/integrations/sources/{id}/trigger` → 202 + runId | Task 6 — EnrichmentSourceController |
| `GET /api/v1/admin/integrations/sources/{id}/runs` | Task 6 — EnrichmentSourceController |
| `GET /api/v1/admin/integrations/sources/{id}/runs/{runId}` | Task 6 — EnrichmentSourceController |
| api_key masked to last 4 chars in all GET responses | Task 3 Step 2 — `VendorApiKeyResponse.maskKey()` |
| Kafka publish `knowledge.enrichment.trigger` on manual trigger | Task 5 Step 1 — `EnrichmentTriggerPublisher` |
| `INTEGRATIONS_GLOBAL_MANAGE` constant | Task 7 |
| `INTEGRATIONS_TENANT_MANAGE` constant | Task 7 |
| TENANT_ADMIN gets `INTEGRATIONS_TENANT_MANAGE`, not global | Task 7 Step 4 |

**No placeholders found.**

**Type consistency:** `TriggerResponse(UUID runId)` is used in both `EnrichmentSourceService` and `EnrichmentSourceController`. `RunStatusResponse.from(EnrichmentRunRow)` static factory matches the `EnrichmentRunRow` fields exactly.
