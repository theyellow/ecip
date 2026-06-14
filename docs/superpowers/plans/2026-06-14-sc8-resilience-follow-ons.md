# #40 — SC8 Resilience Follow-ons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add retry with exponential backoff and per-service read fallbacks to admin-api downstream calls so the UI stays navigable when a service is temporarily down.

**Architecture:** Resilience4j retry config in YAML (3 attempts, 500ms base, 2x multiplier). Each client injects `RetryRegistry`, applies retry before circuit breaker in the reactive chain. Read methods (list endpoints) get `.onErrorResume()` fallbacks returning empty responses. Write methods fail through to 503.

**Tech Stack:** Java 21, Spring Boot 4, Resilience4j 2.3.0, WebFlux, Reactor

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `emcip-admin-api/src/main/resources/application.yml` | Modify | Add `resilience4j.retry` config |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java` | Modify | Add retry + fallback on `listEvents()` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/client/PolicyEngineClient.java` | Modify | Add retry to all + fallback on `listRules()`, `listDecisions()` |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/client/ModerationServiceClient.java` | Modify | Add retry to all + fallback on `listRules()` |
| `emcip-admin-api/src/test/java/io/emcip/admin/api/client/AuditServiceClientTest.java` | Create | Test retry + fallback behavior |
| `emcip-admin-api/src/test/java/io/emcip/admin/api/client/PolicyEngineClientTest.java` | Create | Test retry + fallback behavior |
| `emcip-admin-api/src/test/java/io/emcip/admin/api/client/ModerationServiceClientTest.java` | Create | Test retry + fallback behavior |

---

### Task 1: Add Resilience4j retry configuration to application.yml

**Files:**
- Modify: `emcip-admin-api/src/main/resources/application.yml`

- [ ] **Step 1: Add retry config after the existing circuitbreaker section**

In `emcip-admin-api/src/main/resources/application.yml`, add the `retry` section inside the existing `resilience4j:` block, after the `circuitbreaker:` section (after line 97):

```yaml
  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 500ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        retryExceptions:
          - org.springframework.web.reactive.function.client.WebClientRequestException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - org.springframework.web.reactive.function.client.WebClientResponseException$BadRequest
          - org.springframework.web.reactive.function.client.WebClientResponseException$NotFound
          - org.springframework.web.reactive.function.client.WebClientResponseException$Forbidden
    instances:
      policy-engine:
        baseConfig: default
      audit-service:
        baseConfig: default
      moderation-service:
        baseConfig: default
      tdlib-adapter:
        baseConfig: default
      orchestrator:
        baseConfig: default
```

The final `resilience4j:` block should look like:

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
        registerHealthIndicator: true
    instances:
      policy-engine:
        baseConfig: default
      audit-service:
        baseConfig: default
      moderation-service:
        baseConfig: default
      tdlib-adapter:
        baseConfig: default
      orchestrator:
        baseConfig: default
  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 500ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        retryExceptions:
          - org.springframework.web.reactive.function.client.WebClientRequestException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - org.springframework.web.reactive.function.client.WebClientResponseException$BadRequest
          - org.springframework.web.reactive.function.client.WebClientResponseException$NotFound
          - org.springframework.web.reactive.function.client.WebClientResponseException$Forbidden
    instances:
      policy-engine:
        baseConfig: default
      audit-service:
        baseConfig: default
      moderation-service:
        baseConfig: default
      tdlib-adapter:
        baseConfig: default
      orchestrator:
        baseConfig: default
```

- [ ] **Step 2: Commit**

```bash
git add emcip-admin-api/src/main/resources/application.yml
git commit -m "feat(admin-api): add resilience4j retry config with exponential backoff"
```

---

### Task 2: Add retry + fallback to AuditServiceClient

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/client/AuditServiceClientTest.java`

- [ ] **Step 1: Write the test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/client/AuditServiceClientTest.java`:

```java
package io.emcip.admin.api.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Instant;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

class AuditServiceClientTest {

    private MockWebServer server;
    private AuditServiceClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client =
                new AuditServiceClient(
                        server.url("/").toString(),
                        "test-token",
                        CircuitBreakerRegistry.ofDefaults(),
                        RetryRegistry.ofDefaults());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void listEvents_returnsResponse() {
        server.enqueue(
                new MockResponse()
                        .setBody("{\"items\":[],\"total\":0,\"page\":0,\"size\":50}")
                        .addHeader("Content-Type", "application/json"));

        StepVerifier.create(client.listEvents(0, 50, null, null, null))
                .assertNext(
                        node -> {
                            assertThat(node.get("total").asInt()).isEqualTo(0);
                            assertThat(node.get("items").size()).isEqualTo(0);
                        })
                .verifyComplete();
    }

    @Test
    void listEvents_retriesOnFailureThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(
                new MockResponse()
                        .setBody("{\"items\":[],\"total\":5,\"page\":0,\"size\":50}")
                        .addHeader("Content-Type", "application/json"));

        StepVerifier.create(client.listEvents(0, 50, null, null, null))
                .assertNext(node -> assertThat(node.get("total").asInt()).isEqualTo(5))
                .verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void listEvents_fallsBackToEmptyPageWhenAllRetriesFail() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        StepVerifier.create(client.listEvents(0, 50, null, null, null))
                .assertNext(
                        node -> {
                            assertThat(node.get("total").asInt()).isEqualTo(0);
                            assertThat(node.get("items").size()).isEqualTo(0);
                        })
                .verifyComplete();
    }
}
```

- [ ] **Step 2: Add OkHttp MockWebServer test dependency to pom.xml**

Add to `emcip-admin-api/pom.xml` in the `<dependencies>` section, in the test dependencies area:

```xml
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>mockwebserver</artifactId>
      <scope>test</scope>
    </dependency>
```

Spring Boot's dependency management already manages the OkHttp version — no version tag needed.

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=AuditServiceClientTest -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20`
Expected: Compilation error — constructor doesn't accept `RetryRegistry` yet.

- [ ] **Step 4: Update AuditServiceClient**

Replace the full contents of `emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java`:

```java
package io.emcip.admin.api.client;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
@Slf4j
public class AuditServiceClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public AuditServiceClient(
            @Value("${services.audit-service.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
        this.circuitBreaker = cbRegistry.circuitBreaker("audit-service");
        this.retry = retryRegistry.retry("audit-service");
    }

    public Mono<JsonNode> listEvents(
            int page, int size, String eventType, Instant from, Instant to) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec =
                                    webClient
                                            .get()
                                            .uri(
                                                    uriBuilder -> {
                                                        uriBuilder
                                                                .path("/api/audit/events")
                                                                .queryParam("page", page)
                                                                .queryParam("size", size);
                                                        if (eventType != null
                                                                && !eventType.isBlank()) {
                                                            uriBuilder.queryParam(
                                                                    "eventType", eventType);
                                                        }
                                                        if (from != null) {
                                                            uriBuilder.queryParam(
                                                                    "from", from.toString());
                                                        }
                                                        if (to != null) {
                                                            uriBuilder.queryParam(
                                                                    "to", to.toString());
                                                        }
                                                        return uriBuilder.build();
                                                    });
                            return (tenantId != null
                                            ? spec.header("X-Tenant-Id", tenantId)
                                            : spec)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Fallback: returning empty response for listEvents ({})",
                                    e.getMessage());
                            return emptyPage();
                        });
    }

    private Mono<JsonNode> emptyPage() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.putArray("items");
        node.put("total", 0L);
        node.put("page", 0);
        node.put("size", 50);
        return Mono.just(node);
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=AuditServiceClientTest -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20`
Expected: 3 tests PASS.

- [ ] **Step 6: Update AuditControllerTest to use new 4-arg constructor**

The existing `AuditControllerTest` uses `@Mock AuditServiceClient` which Mockito creates directly — no constructor issue. Verify:

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=AuditControllerTest -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -10`
Expected: 2 tests PASS.

- [ ] **Step 7: Run Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java emcip-admin-api/src/test/java/io/emcip/admin/api/client/AuditServiceClientTest.java emcip-admin-api/pom.xml
git commit -m "feat(admin-api): add retry + fallback to AuditServiceClient"
```

---

### Task 3: Add retry + fallback to PolicyEngineClient

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/client/PolicyEngineClient.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/client/PolicyEngineClientTest.java`

- [ ] **Step 1: Write the test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/client/PolicyEngineClientTest.java`:

```java
package io.emcip.admin.api.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class PolicyEngineClientTest {

    private MockWebServer server;
    private PolicyEngineClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client =
                new PolicyEngineClient(
                        server.url("/").toString(),
                        "test-token",
                        CircuitBreakerRegistry.ofDefaults(),
                        RetryRegistry.ofDefaults());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void listRules_returnsResponse() {
        server.enqueue(
                new MockResponse()
                        .setBody("[{\"id\":\"1\",\"name\":\"rule1\"}]")
                        .addHeader("Content-Type", "application/json"));

        StepVerifier.create(client.listRules().collectList())
                .assertNext(list -> assertThat(list).hasSize(1))
                .verifyComplete();
    }

    @Test
    void listRules_fallsBackToEmptyListWhenAllRetriesFail() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        StepVerifier.create(client.listRules().collectList())
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
    }

    @Test
    void listDecisions_fallsBackToEmptyPageWhenAllRetriesFail() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        StepVerifier.create(client.listDecisions(0, 50, null, null, null, null, null))
                .assertNext(
                        node -> {
                            assertThat(node.get("total").asInt()).isEqualTo(0);
                            assertThat(node.get("items").size()).isEqualTo(0);
                        })
                .verifyComplete();
    }

    @Test
    void createRule_doesNotFallback_propagatesError() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        JsonNode body = JsonNodeFactory.instance.objectNode().put("name", "test");
        StepVerifier.create(client.createRule(body)).verifyError();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=PolicyEngineClientTest -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20`
Expected: Compilation error — constructor doesn't accept `RetryRegistry`.

- [ ] **Step 3: Update PolicyEngineClient**

Replace the full contents of `emcip-admin-api/src/main/java/io/emcip/admin/api/client/PolicyEngineClient.java`:

```java
package io.emcip.admin.api.client;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
@Slf4j
public class PolicyEngineClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public PolicyEngineClient(
            @Value("${services.policy-engine.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
        this.circuitBreaker = cbRegistry.circuitBreaker("policy-engine");
        this.retry = retryRegistry.retry("policy-engine");
    }

    public Flux<JsonNode> listRules() {
        return webClient
                .get()
                .uri("/api/policy-rules")
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Fallback: returning empty list for listRules ({})",
                                    e.getMessage());
                            return Flux.empty();
                        });
    }

    public Mono<JsonNode> createRule(JsonNode body) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            if (tenantId == null) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "A tenant must be selected before creating a"
                                                        + " policy rule"));
                            }
                            ObjectNode node = ((ObjectNode) body).deepCopy();
                            node.put("tenantId", tenantId);
                            return webClient
                                    .post()
                                    .uri("/api/policy-rules")
                                    .bodyValue(node)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> updateRule(String id, JsonNode body) {
        return webClient
                .put()
                .uri("/api/policy-rules/{id}", id)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<Void> deleteRule(String id) {
        return webClient
                .delete()
                .uri("/api/policy-rules/{id}", id)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> getDecision(String id) {
        return webClient
                .get()
                .uri("/api/policy-decisions/{id}", id)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> listDecisions(
            int page,
            int size,
            String decision,
            String intent,
            String from,
            String to,
            Double minConfidence) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec =
                                    webClient
                                            .get()
                                            .uri(
                                                    uriBuilder -> {
                                                        uriBuilder
                                                                .path("/api/policy-decisions")
                                                                .queryParam("page", page)
                                                                .queryParam("size", size);
                                                        if (decision != null
                                                                && !decision.isBlank()) {
                                                            uriBuilder.queryParam(
                                                                    "decision", decision);
                                                        }
                                                        if (intent != null && !intent.isBlank()) {
                                                            uriBuilder.queryParam("intent", intent);
                                                        }
                                                        if (from != null && !from.isBlank()) {
                                                            uriBuilder.queryParam("from", from);
                                                        }
                                                        if (to != null && !to.isBlank()) {
                                                            uriBuilder.queryParam("to", to);
                                                        }
                                                        if (minConfidence != null) {
                                                            uriBuilder.queryParam(
                                                                    "minConfidence", minConfidence);
                                                        }
                                                        return uriBuilder.build();
                                                    });
                            return (tenantId != null
                                            ? spec.header("X-Tenant-Id", tenantId)
                                            : spec)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Fallback: returning empty page for listDecisions ({})",
                                    e.getMessage());
                            return emptyPage();
                        });
    }

    public Mono<Void> updateDecision(String id, JsonNode body) {
        return webClient
                .put()
                .uri("/api/policy-decisions/{id}", id)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<Void> updateDecisionStatus(String id, String status) {
        return webClient
                .put()
                .uri("/api/policy-decisions/{id}", id)
                .bodyValue(java.util.Map.of("signalStatus", status))
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    private Mono<JsonNode> emptyPage() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.putArray("items");
        node.put("total", 0L);
        node.put("page", 0);
        node.put("size", 50);
        return Mono.just(node);
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=PolicyEngineClientTest -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20`
Expected: 4 tests PASS.

- [ ] **Step 5: Run Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/client/PolicyEngineClient.java emcip-admin-api/src/test/java/io/emcip/admin/api/client/PolicyEngineClientTest.java
git commit -m "feat(admin-api): add retry + fallback to PolicyEngineClient"
```

---

### Task 4: Add retry + fallback to ModerationServiceClient

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/client/ModerationServiceClient.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/client/ModerationServiceClientTest.java`

- [ ] **Step 1: Write the test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/client/ModerationServiceClientTest.java`:

```java
package io.emcip.admin.api.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

class ModerationServiceClientTest {

    private MockWebServer server;
    private ModerationServiceClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client =
                new ModerationServiceClient(
                        server.url("/").toString(),
                        "test-token",
                        CircuitBreakerRegistry.ofDefaults(),
                        RetryRegistry.ofDefaults());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void listRules_returnsResponse() {
        server.enqueue(
                new MockResponse()
                        .setBody("[{\"id\":\"1\",\"name\":\"rule1\"}]")
                        .addHeader("Content-Type", "application/json"));

        StepVerifier.create(client.listRules().collectList())
                .assertNext(list -> assertThat(list).hasSize(1))
                .verifyComplete();
    }

    @Test
    void listRules_fallsBackToEmptyListWhenAllRetriesFail() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        StepVerifier.create(client.listRules().collectList())
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
    }

    @Test
    void createRule_doesNotFallback_propagatesError() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        StepVerifier.create(
                        client.createRule(
                                JsonNodeFactory.instance.objectNode().put("name", "test")))
                .verifyError();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=ModerationServiceClientTest -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20`
Expected: Compilation error — constructor doesn't accept `RetryRegistry`.

- [ ] **Step 3: Update ModerationServiceClient**

Replace the full contents of `emcip-admin-api/src/main/java/io/emcip/admin/api/client/ModerationServiceClient.java`:

```java
package io.emcip.admin.api.client;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
@Slf4j
public class ModerationServiceClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ModerationServiceClient(
            @Value("${services.moderation-service.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
        this.circuitBreaker = cbRegistry.circuitBreaker("moderation-service");
        this.retry = retryRegistry.retry("moderation-service");
    }

    public Flux<JsonNode> listRules() {
        return Flux.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.get().uri("/api/moderation-rules");
                            return (tenantId != null
                                            ? spec.header("X-Tenant-Id", tenantId)
                                            : spec)
                                    .retrieve()
                                    .bodyToFlux(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Fallback: returning empty list for listRules ({})",
                                    e.getMessage());
                            return Flux.empty();
                        });
    }

    public Mono<JsonNode> createRule(JsonNode body) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.post().uri("/api/moderation-rules");
                            return (tenantId != null
                                            ? spec.header("X-Tenant-Id", tenantId)
                                            : spec)
                                    .bodyValue(body)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> updateRule(String id, JsonNode body) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.put().uri("/api/moderation-rules/{id}", id);
                            return (tenantId != null
                                            ? spec.header("X-Tenant-Id", tenantId)
                                            : spec)
                                    .bodyValue(body)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<Void> deleteRule(String id) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.delete().uri("/api/moderation-rules/{id}", id);
                            return (tenantId != null
                                            ? spec.header("X-Tenant-Id", tenantId)
                                            : spec)
                                    .retrieve()
                                    .bodyToMono(Void.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=ModerationServiceClientTest -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20`
Expected: 3 tests PASS.

- [ ] **Step 5: Run Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/client/ModerationServiceClient.java emcip-admin-api/src/test/java/io/emcip/admin/api/client/ModerationServiceClientTest.java
git commit -m "feat(admin-api): add retry + fallback to ModerationServiceClient"
```

---

### Task 5: Final verification — all tests pass

**Files:**
- All modified files from Tasks 1-4

- [ ] **Step 1: Run all admin-api tests**

Run: `cd emcip-admin-api && mvn test -pl . -q 2>&1 | tail -20`
Expected: All tests PASS (including existing AuditControllerTest + 3 new client tests).

- [ ] **Step 2: Run Spotless check**

Run: `mvn spotless:check -pl emcip-admin-api -q 2>&1 | tail -5`
Expected: Clean — 0 files changed.

- [ ] **Step 3: Commit if any remaining changes**

```bash
git status
# If clean, nothing to do.
```
