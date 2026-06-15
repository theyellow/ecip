# LLM Cost Analytics Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated LLM Costs page to the Admin UI showing cost totals, per-model breakdown, and a daily bar chart.

**Architecture:** Three new aggregation queries on `ModelCostLogRepository`, three new endpoints on `OrchestratorController`, a thin proxy controller on admin-api, and a new React page with summary cards + CSS bar chart + model table. Tenant filtering via existing Hibernate `tenantFilter` aspect.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, React, CSS Modules

**Known limitation:** The orchestrator's `TenantContext` is only set on Kafka consumer paths, not REST endpoints. The cost endpoints will return all tenants' data regardless of caller. This matches the existing `GET /api/costs/summary` behavior. Tenant-scoped cost filtering is a follow-up concern.

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `emcip-llm-orchestrator/.../repository/ModelCostLogRepository.java` | Modify | Add 3 aggregation queries |
| `emcip-llm-orchestrator/.../service/CostTrackingService.java` | Modify | Add 3 methods mapping query results |
| `emcip-llm-orchestrator/.../controller/OrchestratorController.java` | Modify | Add 3 GET endpoints under `/api/costs` |
| `emcip-llm-orchestrator/.../service/CostTrackingServiceTest.java` | Modify | Add tests for new methods |
| `emcip-llm-orchestrator/.../controller/OrchestratorControllerCostsTest.java` | Create | Tests for cost endpoints |
| `emcip-admin-api/.../controller/CostsProxyController.java` | Create | Proxy 3 GET endpoints to orchestrator |
| `emcip-admin-api/.../controller/CostsProxyControllerTest.java` | Create | Test proxy endpoints |
| `emcip-admin-ui/.../auth/permissions.js` | Modify | Add `COSTS_READ` |
| `emcip-admin-ui/.../layout/Sidebar/Sidebar.jsx` | Modify | Add nav entry |
| `emcip-admin-ui/.../App.jsx` | Modify | Add route |
| `emcip-admin-ui/.../api/costs.js` | Create | API module |
| `emcip-admin-ui/.../pages/Costs/Costs.jsx` | Create | Page component |
| `emcip-admin-ui/.../pages/Costs/Costs.module.css` | Create | Page styles |

---

### Task 1: Repository aggregation queries + service methods

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/repository/ModelCostLogRepository.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/CostTrackingService.java`
- Modify: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/CostTrackingServiceTest.java`

- [ ] **Step 1: Write tests for the three new service methods**

Add to the existing `CostTrackingServiceTest.java` after line 182:

```java
    // --- getTotals tests ---

    @Test
    void getTotals_returnsAggregatedData() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-31T23:59:59Z");
        Object[] row = new Object[]{42.5, 120000L, 210L, 795.0, 205L, 5L};
        when(costLogRepository.calculateTotals(start, end)).thenReturn(row);

        Map<String, Object> result = service.getTotals(start, end);

        assertThat(result.get("totalCostUsd")).isEqualTo(42.5);
        assertThat(result.get("totalTokens")).isEqualTo(120000L);
        assertThat(result.get("callCount")).isEqualTo(210L);
        assertThat(result.get("avgLatencyMs")).isEqualTo(795.0);
        assertThat(result.get("successCount")).isEqualTo(205L);
        assertThat(result.get("failureCount")).isEqualTo(5L);
    }

    @Test
    void getTotals_nullRow_returnsZeros() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-31T23:59:59Z");
        Object[] row = new Object[]{null, null, 0L, null, 0L, 0L};
        when(costLogRepository.calculateTotals(start, end)).thenReturn(row);

        Map<String, Object> result = service.getTotals(start, end);

        assertThat(result.get("totalCostUsd")).isEqualTo(0.0);
        assertThat(result.get("totalTokens")).isEqualTo(0L);
    }

    // --- getByModel tests ---

    @Test
    void getByModel_returnsMappedResults() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-31T23:59:59Z");
        List<Object[]> rows = List.of(
                new Object[]{"qwen3-30b-a3b", 142L, 60000L, 25000L, 85000L, 0.0, 812.0});
        when(costLogRepository.aggregateByModel(start, end)).thenReturn(rows);

        List<Map<String, Object>> result = service.getByModel(start, end);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("modelName")).isEqualTo("qwen3-30b-a3b");
        assertThat(result.getFirst().get("callCount")).isEqualTo(142L);
        assertThat(result.getFirst().get("totalTokens")).isEqualTo(85000L);
    }

    // --- getByDay tests ---

    @Test
    void getByDay_returnsMappedResults() {
        Instant start = Instant.parse("2026-06-14T00:00:00Z");
        Instant end = Instant.parse("2026-06-15T23:59:59Z");
        List<Object[]> rows = List.of(
                new Object[]{java.time.LocalDate.of(2026, 6, 14), 0.0, 47L, 28000L},
                new Object[]{java.time.LocalDate.of(2026, 6, 15), 0.0, 63L, 35000L});
        when(costLogRepository.aggregateByDay(start, end)).thenReturn(rows);

        List<Map<String, Object>> result = service.getByDay(start, end);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("date")).isEqualTo("2026-06-14");
        assertThat(result.get(0).get("callCount")).isEqualTo(47L);
        assertThat(result.get(1).get("date")).isEqualTo("2026-06-15");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=CostTrackingServiceTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -5 | cat`
Expected: Compilation error — `getTotals`, `getByModel`, `getByDay` methods don't exist.

- [ ] **Step 3: Add aggregation queries to ModelCostLogRepository**

In `ModelCostLogRepository.java`, add after the existing `findTop10ByPromptTemplateNameOrderByCreatedAtDesc` method (after line 48):

```java
    /** Aggregate totals for a time period (includes both SUCCESS and FAILED). */
    @Query(
            "SELECT COALESCE(SUM(m.totalCostUsd), 0.0),"
                    + " COALESCE(SUM(m.totalTokens), 0),"
                    + " COUNT(m),"
                    + " COALESCE(AVG(m.latencyMs), 0.0),"
                    + " SUM(CASE WHEN m.status = 'SUCCESS' THEN 1 ELSE 0 END),"
                    + " SUM(CASE WHEN m.status = 'FAILED' THEN 1 ELSE 0 END)"
                    + " FROM ModelCostLog m"
                    + " WHERE m.createdAt BETWEEN :start AND :end")
    Object[] calculateTotals(@Param("start") Instant start, @Param("end") Instant end);

    /** Aggregate by model for a time period (SUCCESS only). */
    @Query(
            "SELECT m.modelName,"
                    + " COUNT(m),"
                    + " COALESCE(SUM(m.inputTokens), 0),"
                    + " COALESCE(SUM(m.outputTokens), 0),"
                    + " COALESCE(SUM(m.totalTokens), 0),"
                    + " COALESCE(SUM(m.totalCostUsd), 0.0),"
                    + " COALESCE(AVG(m.latencyMs), 0.0)"
                    + " FROM ModelCostLog m"
                    + " WHERE m.createdAt BETWEEN :start AND :end AND m.status = 'SUCCESS'"
                    + " GROUP BY m.modelName"
                    + " ORDER BY COUNT(m) DESC")
    List<Object[]> aggregateByModel(@Param("start") Instant start, @Param("end") Instant end);

    /** Aggregate by day for a time period (SUCCESS only). */
    @Query(
            value =
                    "SELECT DATE(created_at) AS d,"
                            + " COALESCE(SUM(total_cost_usd), 0.0),"
                            + " COUNT(*),"
                            + " COALESCE(SUM(total_tokens), 0)"
                            + " FROM model_cost_logs"
                            + " WHERE created_at BETWEEN :start AND :end AND status = 'SUCCESS'"
                            + " GROUP BY DATE(created_at)"
                            + " ORDER BY d ASC",
            nativeQuery = true)
    List<Object[]> aggregateByDay(@Param("start") Instant start, @Param("end") Instant end);
```

Note: `aggregateByDay` uses a native query because JPQL `CAST(m.createdAt AS LocalDate)` is not reliably supported in Hibernate 6. The native `DATE()` function works directly with PostgreSQL.

- [ ] **Step 4: Add service methods to CostTrackingService**

In `CostTrackingService.java`, add after the `estimateCost` method (after line 187), and add the missing imports at the top:

Add imports:
```java
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```

Add methods:
```java
    /** Get aggregated totals for a time period. */
    @Transactional(readOnly = true)
    public Map<String, Object> getTotals(Instant start, Instant end) {
        Object[] row = costLogRepository.calculateTotals(start, end);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCostUsd", row[0] != null ? ((Number) row[0]).doubleValue() : 0.0);
        result.put("totalTokens", row[1] != null ? ((Number) row[1]).longValue() : 0L);
        result.put("callCount", ((Number) row[2]).longValue());
        result.put("avgLatencyMs", row[3] != null ? ((Number) row[3]).doubleValue() : 0.0);
        result.put("successCount", ((Number) row[4]).longValue());
        result.put("failureCount", ((Number) row[5]).longValue());
        return result;
    }

    /** Get per-model aggregation for a time period. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getByModel(Instant start, Instant end) {
        return costLogRepository.aggregateByModel(start, end).stream()
                .map(
                        row -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("modelName", row[0]);
                            m.put("callCount", ((Number) row[1]).longValue());
                            m.put("inputTokens", ((Number) row[2]).longValue());
                            m.put("outputTokens", ((Number) row[3]).longValue());
                            m.put("totalTokens", ((Number) row[4]).longValue());
                            m.put("totalCostUsd", ((Number) row[5]).doubleValue());
                            m.put("avgLatencyMs", ((Number) row[6]).doubleValue());
                            return m;
                        })
                .toList();
    }

    /** Get per-day aggregation for a time period. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getByDay(Instant start, Instant end) {
        return costLogRepository.aggregateByDay(start, end).stream()
                .map(
                        row -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            // Native query returns java.sql.Date for DATE()
                            m.put("date", row[0].toString());
                            m.put("totalCostUsd", ((Number) row[1]).doubleValue());
                            m.put("callCount", ((Number) row[2]).longValue());
                            m.put("totalTokens", ((Number) row[3]).longValue());
                            return m;
                        })
                .toList();
    }
```

- [ ] **Step 5: Run tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=CostTrackingServiceTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "(Tests run|BUILD)" | cat`
Expected: All tests PASS (existing 12 + 4 new = 16 total).

- [ ] **Step 6: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-llm-orchestrator -q
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/repository/ModelCostLogRepository.java emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/CostTrackingService.java emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/CostTrackingServiceTest.java
git commit -m "feat(llm-orchestrator): add cost aggregation queries and service methods"
```

---

### Task 2: OrchestratorController cost endpoints

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerCostsTest.java`

- [ ] **Step 1: Write the test**

Create `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerCostsTest.java`:

```java
package io.emcip.llm.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import io.emcip.llm.orchestrator.service.CostTrackingService;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OrchestratorControllerCostsTest {

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private CostTrackingService costTrackingService;
    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private LlmProviderConfigService providerConfigService;
    @Mock private LlmProviderConfigRepository providerConfigRepository;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @InjectMocks private OrchestratorController controller;

    @Test
    void costTotals_returnsTotals() {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("totalCostUsd", 42.5);
        totals.put("totalTokens", 120000L);
        totals.put("callCount", 210L);
        totals.put("avgLatencyMs", 795.0);
        totals.put("successCount", 205L);
        totals.put("failureCount", 5L);
        when(costTrackingService.getTotals(any(), any())).thenReturn(totals);

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T23:59:59Z");

        var response = controller.costTotals(from, to);

        assertThat(response.get("totalCostUsd")).isEqualTo(42.5);
        assertThat(response.get("callCount")).isEqualTo(210L);
        assertThat(response.get("from")).isEqualTo(from.toString());
        assertThat(response.get("to")).isEqualTo(to.toString());
    }

    @Test
    void costByModel_returnsModelBreakdown() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("modelName", "qwen3-30b-a3b");
        model.put("callCount", 142L);
        when(costTrackingService.getByModel(any(), any())).thenReturn(List.of(model));

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T23:59:59Z");

        var response = controller.costByModel(from, to);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().get("modelName")).isEqualTo("qwen3-30b-a3b");
    }

    @Test
    void costByDay_returnsDailyBreakdown() {
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "2026-06-14");
        day.put("callCount", 47L);
        when(costTrackingService.getByDay(any(), any())).thenReturn(List.of(day));

        Instant from = Instant.parse("2026-06-14T00:00:00Z");
        Instant to = Instant.parse("2026-06-15T23:59:59Z");

        var response = controller.costByDay(from, to);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().get("date")).isEqualTo("2026-06-14");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=OrchestratorControllerCostsTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -5 | cat`
Expected: Compilation error — `costTotals`, `costByModel`, `costByDay` methods don't exist.

- [ ] **Step 3: Add endpoints to OrchestratorController**

In `OrchestratorController.java`, add after the existing `costSummary()` method (after line 183, before the `// --- Provider Config ---` comment):

```java
    @Operation(summary = "Get aggregated LLM cost totals for a time range")
    @GetMapping("/costs/totals")
    public Map<String, Object> costTotals(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Map<String, Object> totals = costTrackingService.getTotals(from, to);
        totals.put("from", from.toString());
        totals.put("to", to.toString());
        return totals;
    }

    @Operation(summary = "Get LLM costs aggregated by model for a time range")
    @GetMapping("/costs/by-model")
    public List<Map<String, Object>> costByModel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return costTrackingService.getByModel(from, to);
    }

    @Operation(summary = "Get LLM costs aggregated by day for a time range")
    @GetMapping("/costs/by-day")
    public List<Map<String, Object>> costByDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return costTrackingService.getByDay(from, to);
    }
```

- [ ] **Step 4: Run tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=OrchestratorControllerCostsTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "(Tests run|BUILD)" | cat`
Expected: 3 tests PASS.

- [ ] **Step 5: Run all orchestrator tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator 2>&1 | tail -5 | cat`
Expected: All tests PASS.

- [ ] **Step 6: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-llm-orchestrator -q
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerCostsTest.java
git commit -m "feat(llm-orchestrator): add cost totals, by-model, by-day endpoints"
```

---

### Task 3: admin-api CostsProxyController

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/CostsProxyController.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/CostsProxyControllerTest.java`

- [ ] **Step 1: Write the test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/CostsProxyControllerTest.java`:

```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

class CostsProxyControllerTest {

    private MockWebServer server;
    private WebTestClient webClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        WebClient orchestratorClient =
                WebClient.builder().baseUrl(server.url("").toString()).build();
        CircuitBreakerRegistry registry =
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        CostsProxyController controller =
                new CostsProxyController(orchestratorClient, registry);
        webClient =
                WebTestClient.bindToController(controller)
                        .controllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void totals_proxiesToOrchestrator() {
        server.enqueue(
                new MockResponse.Builder()
                        .body("{\"totalCostUsd\":42.5,\"callCount\":210}")
                        .addHeader("Content-Type", "application/json")
                        .build());

        webClient
                .get()
                .uri("/api/costs/totals?from=2026-01-01T00:00:00Z&to=2026-01-31T23:59:59Z")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalCostUsd")
                .isEqualTo(42.5);
    }

    @Test
    void byModel_proxiesToOrchestrator() {
        server.enqueue(
                new MockResponse.Builder()
                        .body("[{\"modelName\":\"qwen3\",\"callCount\":100}]")
                        .addHeader("Content-Type", "application/json")
                        .build());

        webClient
                .get()
                .uri("/api/costs/by-model?from=2026-01-01T00:00:00Z&to=2026-01-31T23:59:59Z")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].modelName")
                .isEqualTo("qwen3");
    }

    @Test
    void byDay_proxiesToOrchestrator() {
        server.enqueue(
                new MockResponse.Builder()
                        .body("[{\"date\":\"2026-06-14\",\"callCount\":47}]")
                        .addHeader("Content-Type", "application/json")
                        .build());

        webClient
                .get()
                .uri("/api/costs/by-day?from=2026-06-14T00:00:00Z&to=2026-06-15T23:59:59Z")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].date")
                .isEqualTo("2026-06-14");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -Dtest=CostsProxyControllerTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -5 | cat`
Expected: Compilation error — `CostsProxyController` doesn't exist.

- [ ] **Step 3: Implement CostsProxyController**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/CostsProxyController.java`:

```java
package io.emcip.admin.api.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Proxies cost analytics queries to the llm-orchestrator service. */
@RestController
@RequestMapping("/api/costs")
@PreAuthorize("hasAuthority('COSTS_READ')")
@Tag(name = "Costs", description = "Proxy to llm-orchestrator cost analytics")
public class CostsProxyController {

    private final WebClient orchestratorClient;
    private final CircuitBreaker circuitBreaker;

    public CostsProxyController(
            @Qualifier("orchestratorWebClient") WebClient orchestratorClient,
            CircuitBreakerRegistry registry) {
        this.orchestratorClient = orchestratorClient;
        this.circuitBreaker = registry.circuitBreaker("orchestrator");
    }

    @Operation(summary = "Get aggregated LLM cost totals")
    @GetMapping("/totals")
    public Mono<String> totals(@RequestParam String from, @RequestParam String to) {
        return orchestratorClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/costs/totals")
                                        .queryParam("from", from)
                                        .queryParam("to", to)
                                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get LLM costs aggregated by model")
    @GetMapping("/by-model")
    public Mono<String> byModel(@RequestParam String from, @RequestParam String to) {
        return orchestratorClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/costs/by-model")
                                        .queryParam("from", from)
                                        .queryParam("to", to)
                                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get LLM costs aggregated by day")
    @GetMapping("/by-day")
    public Mono<String> byDay(@RequestParam String from, @RequestParam String to) {
        return orchestratorClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/costs/by-day")
                                        .queryParam("from", from)
                                        .queryParam("to", to)
                                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -Dtest=CostsProxyControllerTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "(Tests run|BUILD)" | cat`
Expected: 3 tests PASS.

- [ ] **Step 5: Run all admin-api tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api 2>&1 | tail -5 | cat`
Expected: All tests PASS.

- [ ] **Step 6: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/CostsProxyController.java emcip-admin-api/src/test/java/io/emcip/admin/api/controller/CostsProxyControllerTest.java
git commit -m "feat(admin-api): add CostsProxyController for cost analytics"
```

---

### Task 4: Admin UI — permissions, routing, API module

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/auth/permissions.js`
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/App.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/api/costs.js`

- [ ] **Step 1: Add COSTS_READ permission**

In `emcip-admin-ui/src/main/frontend/src/auth/permissions.js`, add `'COSTS_READ'` to both roles:

Replace:
```js
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
```

With:
```js
export const ROLE_PERMISSIONS = {
  ADMIN: [
    'GROUPS_READ', 'GROUPS_WRITE',
    'POLICY_RULES_READ', 'POLICY_RULES_WRITE',
    'MODERATION_RULES_READ', 'MODERATION_RULES_WRITE',
    'AUDIT_READ',
    'TELEGRAM_READ', 'TELEGRAM_WRITE',
    'SIMULATE_WRITE',
    'AI_CONFIG_READ', 'AI_CONFIG_WRITE',
    'COSTS_READ',
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
    'COSTS_READ',
  ],
}
```

- [ ] **Step 2: Add sidebar nav entry**

In `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`, add after the AI Config entry in the NAV array (after line 19):

```js
  { to: '/costs',            label: 'LLM Costs',         icon: '\u229B', permission: 'COSTS_READ' },
```

- [ ] **Step 3: Add route to App.jsx**

In `emcip-admin-ui/src/main/frontend/src/App.jsx`, add the import after line 17:

```js
import { Costs } from './pages/Costs/Costs'
```

Add the route after the `ai-config` route (after line 55):

```jsx
        <Route path="costs" element={<Costs />} />
```

- [ ] **Step 4: Create API module**

Create `emcip-admin-ui/src/main/frontend/src/api/costs.js`:

```js
export function costsApi(request) {
  return {
    totals: (from, to) =>
      request(`/api/costs/totals?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    byModel: (from, to) =>
      request(`/api/costs/by-model?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    byDay: (from, to) =>
      request(`/api/costs/by-day?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  }
}
```

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/auth/permissions.js emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx emcip-admin-ui/src/main/frontend/src/App.jsx emcip-admin-ui/src/main/frontend/src/api/costs.js
git commit -m "feat(admin-ui): add COSTS_READ permission, nav entry, route, and API module"
```

---

### Task 5: Admin UI — Costs page component and styles

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Costs/Costs.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Costs/Costs.module.css`

- [ ] **Step 1: Create CSS module**

Create `emcip-admin-ui/src/main/frontend/src/pages/Costs/Costs.module.css`:

```css
/* Page header */
.pageHeader {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--sp-5);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--rule);
}

.systemId {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.10em;
  text-transform: uppercase;
  color: var(--fg-3);
  margin-top: 6px;
}

.filters {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}

.select {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

/* Summary cards */
.summaryRow {
  display: flex;
  gap: var(--sp-3);
  margin-bottom: var(--sp-5);
}

.summaryCard {
  flex: 1;
  background: var(--bg-card);
  border: 1px solid var(--border);
  padding: var(--sp-4) var(--sp-4);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summaryValue {
  font-family: var(--font-mono);
  font-size: 24px;
  color: var(--fg-1);
  line-height: 1;
}

.summaryLabel {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fg-3);
}

/* Bar chart */
.chartSection {
  margin-bottom: var(--sp-5);
}

.chartContainer {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 160px;
  padding-top: var(--sp-2);
  border-bottom: 1px solid var(--rule);
}

.chartBarWrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
  justify-content: flex-end;
}

.chartBar {
  width: 100%;
  background: var(--accent);
  min-height: 2px;
  transition: opacity 0.15s;
}

.chartBar:hover {
  opacity: 0.8;
}

.chartLabel {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--fg-3);
  text-align: center;
  padding-top: 4px;
  white-space: nowrap;
}

.chartEmpty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 160px;
  color: var(--fg-3);
  font-family: var(--font-mono);
  font-size: 12px;
  font-style: italic;
}

/* Table */
.tableWrapper {
  overflow-x: auto;
}

.table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 0;
}

.table th {
  padding: 10px 16px;
  text-align: left;
  font-family: var(--font-body);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fg-3);
  background: var(--accent-soft);
  border-bottom: 1px solid var(--rule);
}

.table td {
  padding: 10px 16px;
  border-bottom: 1px solid var(--rule);
  font-size: 13px;
  color: var(--fg-1);
}

.table tr:hover td {
  background: var(--accent-soft);
}

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.right {
  text-align: right;
}

/* Alert banner */
.alertBanner {
  color: var(--signal-stop-fg);
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.25);
  padding: 8px 12px;
  font-family: var(--font-mono);
  font-size: 12px;
  margin-bottom: var(--sp-3);
}

/* Filter row for custom dates */
.filterInput {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  outline: none;
  min-width: 120px;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.filterInput:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

.sectionLabel {
  font-family: var(--font-display);
  font-size: 10px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--accent);
  margin-bottom: var(--sp-2);
}
```

- [ ] **Step 2: Create Costs page component**

Create `emcip-admin-ui/src/main/frontend/src/pages/Costs/Costs.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { costsApi } from '../../api/costs'
import styles from './Costs.module.css'

const TIME_PRESETS = [
  { value: 'today', label: 'Today' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
  { value: 'thismonth', label: 'This month' },
  { value: 'lastmonth', label: 'Last month' },
  { value: 'custom', label: 'Custom range\u2026' },
]

function presetToRange(preset) {
  const now = new Date()
  if (preset === 'today') {
    const start = new Date(now)
    start.setHours(0, 0, 0, 0)
    return { from: start.toISOString(), to: now.toISOString() }
  }
  if (preset === '7d') return { from: new Date(now - 7 * 86400000).toISOString(), to: now.toISOString() }
  if (preset === '30d') return { from: new Date(now - 30 * 86400000).toISOString(), to: now.toISOString() }
  if (preset === 'thismonth') {
    return { from: new Date(now.getFullYear(), now.getMonth(), 1).toISOString(), to: now.toISOString() }
  }
  if (preset === 'lastmonth') {
    return {
      from: new Date(now.getFullYear(), now.getMonth() - 1, 1).toISOString(),
      to: new Date(now.getFullYear(), now.getMonth(), 1).toISOString(),
    }
  }
  return { from: null, to: null }
}

function formatTokens(n) {
  if (n == null) return '\u2014'
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K'
  return String(n)
}

function formatCost(n) {
  if (n == null) return '\u2014'
  return '$' + n.toFixed(4)
}

export function Costs() {
  const api = costsApi(useAuthRequest())
  const [timePreset, setTimePreset] = useState('30d')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const [totals, setTotals] = useState(null)
  const [byModel, setByModel] = useState([])
  const [byDay, setByDay] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    const range =
      timePreset === 'custom'
        ? {
            from: customFrom ? new Date(customFrom).toISOString() : null,
            to: customTo ? new Date(customTo).toISOString() : null,
          }
        : presetToRange(timePreset)

    if (!range.from || !range.to) return

    setLoading(true)
    setError('')
    Promise.all([
      api.totals(range.from, range.to),
      api.byModel(range.from, range.to),
      api.byDay(range.from, range.to),
    ])
      .then(([t, m, d]) => {
        setTotals(t)
        setByModel(m ?? [])
        setByDay(d ?? [])
      })
      .catch(e => setError(e.message || 'Failed to load cost data'))
      .finally(() => setLoading(false))
  }, [timePreset, customFrom, customTo])

  const maxCalls = byDay.length > 0 ? Math.max(...byDay.map(d => d.callCount)) : 0

  return (
    <>
      <div className={styles.pageHeader}>
        <div>
          <h2>LLM Costs</h2>
          <div className={styles.systemId}>{'\u2726'} llm-orchestrator {'\u00b7'} model_cost_logs</div>
        </div>
        <div className={styles.filters}>
          <select
            value={timePreset}
            onChange={e => setTimePreset(e.target.value)}
            className={styles.select}
          >
            {TIME_PRESETS.map(o => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
          {timePreset === 'custom' && (
            <>
              <input
                type="datetime-local"
                className={styles.filterInput}
                value={customFrom}
                onChange={e => setCustomFrom(e.target.value)}
              />
              <input
                type="datetime-local"
                className={styles.filterInput}
                value={customTo}
                onChange={e => setCustomTo(e.target.value)}
              />
            </>
          )}
        </div>
      </div>

      {error && <p role="alert" className={styles.alertBanner}>{error}</p>}

      {loading && <p className={styles.mono} style={{ textAlign: 'center', padding: 'var(--sp-5)' }}>Loading{'\u2026'}</p>}

      {!loading && totals && (
        <>
          <div className={styles.summaryRow}>
            <div className={styles.summaryCard}>
              <span className={styles.summaryValue}>${(totals.totalCostUsd ?? 0).toFixed(2)}</span>
              <span className={styles.summaryLabel}>Total Cost</span>
            </div>
            <div className={styles.summaryCard}>
              <span className={styles.summaryValue}>{formatTokens(totals.totalTokens)}</span>
              <span className={styles.summaryLabel}>Total Tokens</span>
            </div>
            <div className={styles.summaryCard}>
              <span className={styles.summaryValue}>
                {totals.successCount ?? 0} / {totals.failureCount ?? 0}
              </span>
              <span className={styles.summaryLabel}>Calls (ok / fail)</span>
            </div>
            <div className={styles.summaryCard}>
              <span className={styles.summaryValue}>{Math.round(totals.avgLatencyMs ?? 0)}ms</span>
              <span className={styles.summaryLabel}>Avg Latency</span>
            </div>
          </div>

          <div className={styles.chartSection}>
            <div className={styles.sectionLabel}>Calls per day</div>
            {byDay.length === 0 ? (
              <div className={styles.chartEmpty}>No data for this period</div>
            ) : (
              <>
                <div className={styles.chartContainer}>
                  {byDay.map((d, i) => (
                    <div key={i} className={styles.chartBarWrap}>
                      <div
                        className={styles.chartBar}
                        style={{ height: maxCalls > 0 ? `${(d.callCount / maxCalls) * 100}%` : '2px' }}
                        title={`${d.date}: ${d.callCount} calls, ${formatCost(d.totalCostUsd)}, ${formatTokens(d.totalTokens)} tokens`}
                      />
                    </div>
                  ))}
                </div>
                <div style={{ display: 'flex', gap: '2px' }}>
                  {byDay.map((d, i) => (
                    <div key={i} className={styles.chartLabel} style={{ flex: 1 }}>
                      {d.date?.slice(5)}
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>

          <div className={styles.sectionLabel}>By model</div>
          <div className={styles.tableWrapper}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>Model</th>
                  <th className={styles.right}>Calls</th>
                  <th className={styles.right}>Input Tokens</th>
                  <th className={styles.right}>Output Tokens</th>
                  <th className={styles.right}>Total Tokens</th>
                  <th className={styles.right}>Cost</th>
                  <th className={styles.right}>Avg Latency</th>
                </tr>
              </thead>
              <tbody>
                {byModel.length === 0 && (
                  <tr>
                    <td colSpan={7} style={{ textAlign: 'center', color: 'var(--fg-3)', padding: 'var(--sp-5)', fontStyle: 'italic' }}>
                      No LLM calls recorded for this period
                    </td>
                  </tr>
                )}
                {byModel.map((m, i) => (
                  <tr key={i}>
                    <td>{m.modelName}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{m.callCount}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{formatTokens(m.inputTokens)}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{formatTokens(m.outputTokens)}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{formatTokens(m.totalTokens)}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{formatCost(m.totalCostUsd)}</td>
                    <td className={`${styles.mono} ${styles.right}`}>{Math.round(m.avgLatencyMs)}ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  )
}
```

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Costs/Costs.jsx emcip-admin-ui/src/main/frontend/src/pages/Costs/Costs.module.css
git commit -m "feat(admin-ui): add LLM Costs page with summary cards, bar chart, model table"
```

---

### Task 6: Final verification + docs

**Files:**
- All modified files from Tasks 1-5
- `docs/superpowers/BACKLOG.md`
- `documentation/architecture-guide.adoc`

- [ ] **Step 1: Run all orchestrator tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator 2>&1 | tail -5 | cat`
Expected: All tests PASS.

- [ ] **Step 2: Run all admin-api tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api 2>&1 | tail -5 | cat`
Expected: All tests PASS.

- [ ] **Step 3: Spotless check both modules**

Run: `cd /home/ben/Development/ecip && mvn spotless:check -pl emcip-llm-orchestrator,emcip-admin-api 2>&1 | grep -E "(Spotless|BUILD)" | cat`
Expected: Clean — 0 files changed.

- [ ] **Step 4: Update BACKLOG.md**

In `docs/superpowers/BACKLOG.md`, update the #7 entry in the open items table:

Replace:
```
| 7 | **LLM cost analytics dashboard** | M | Admin UI page: per-tenant call counts + token spend. Data already in `model_cost_logs`. Needed before adding more LLM-heavy features. |
```

With:
```
| 7 | **LLM cost analytics dashboard** | M | ✅ Done. Costs page with summary cards, CSS bar chart, model breakdown table. Spec: `docs/superpowers/specs/2026-06-15-llm-cost-analytics-design.md`. |
```

Add to §5 Completed:
```
| 7 | LLM cost analytics dashboard | ✅ 2026-06-15. Spec: `specs/2026-06-15-llm-cost-analytics-design.md` |
```

- [ ] **Step 5: Update architecture-guide.adoc**

In `documentation/architecture-guide.adoc`, in the admin-api section (around line 175), after the AI Research chat paragraph add:

```
The admin-api exposes *LLM cost analytics* via `CostsProxyController` — three read-only endpoints (`/api/costs/totals`, `/api/costs/by-model`, `/api/costs/by-day`) that proxy to the LLM Orchestrator's cost aggregation queries. The `COSTS_READ` permission is granted to both ADMIN and TENANT_ADMIN roles, giving operators visibility into LLM usage and spend.
```

- [ ] **Step 6: Commit docs**

```bash
cd /home/ben/Development/ecip
git add docs/superpowers/BACKLOG.md documentation/architecture-guide.adoc
git commit -m "docs: update backlog and architecture guide for LLM cost analytics (#7)"
```
