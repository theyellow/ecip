# Red Team Standalone Items — Rate Limiting & Circuit Breakers

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Resilience4j rate limiting to admin-api endpoints and circuit breakers with timeouts to the bidirectional REST calls between knowledge-engine and llm-orchestrator.

**Architecture:** S1 uses Resilience4j `@RateLimiter` (already a dependency in admin-api) with three named configs: `auth` (10/min per IP), `llm-trigger` (20/min per user), `admin-crud` (100/min per user). S2 adds `resilience4j-spring-boot3` to both knowledge-engine and llm-orchestrator, wraps the synchronous `RestClient` calls with `CircuitBreaker` + 10s HTTP timeout, and returns empty/graceful results on open circuit.

**Tech Stack:** Java 21, Spring Boot 4, Resilience4j 2.3.0, RestClient (blocking)

## Global Constraints

- Spotless: `mvn spotless:apply` before every commit
- Lombok: `@Slf4j`, `@RequiredArgsConstructor`
- Cron: never schedule at exact round times
- knowledge-engine and llm-orchestrator are JPA/blocking (NOT reactive)
- admin-api is R2DBC/reactive — uses `resilience4j-reactor` with `CircuitBreakerOperator`
- Resilience4j version: `2.3.0` (matches existing admin-api dependency)

---

### Task 1: Rate limiting on admin-api endpoints (S1)

**Files:**
- Modify: `emcip-admin-api/src/main/resources/application.yml`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuthController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/SimulateController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/UserManagementController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TenantController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/GroupProfileController.java`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/AuthControllerTest.java` (if exists)

**Interfaces:**
- Produces: 429 Too Many Requests when rate limits are exceeded.

- [ ] **Step 1: Add rate limiter configuration to application.yml**

Add to `emcip-admin-api/src/main/resources/application.yml`:

```yaml
resilience4j:
  ratelimiter:
    instances:
      auth:
        limit-for-period: 10
        limit-refresh-period: 60s
        timeout-duration: 0s
      llm-trigger:
        limit-for-period: 20
        limit-refresh-period: 60s
        timeout-duration: 0s
      admin-crud:
        limit-for-period: 100
        limit-refresh-period: 60s
        timeout-duration: 0s
```

Note: `timeout-duration: 0s` means requests are rejected immediately when the limit is exceeded (no waiting).

- [ ] **Step 2: Add RateLimiterRegistry bean and exception handler**

The `resilience4j-spring-boot3` auto-configuration creates the `RateLimiterRegistry` bean from the YAML config. No manual bean needed.

Add rate limit exceeded handling to the existing `GlobalExceptionHandler` (or create one if it doesn't exist):

```java
@ExceptionHandler(RequestNotPermitted.class)
public ResponseEntity<Map<String, String>> handleRateLimitExceeded(RequestNotPermitted ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(Map.of("error", "Rate limit exceeded", "message", ex.getMessage()));
}
```

Since admin-api is reactive (WebFlux), the `@ExceptionHandler` approach works with `@ControllerAdvice`. However, `@RateLimiter` annotation from Resilience4j works with AOP which requires method return types to match. For reactive controllers returning `Mono`/`Flux`, use `RateLimiterOperator` instead of the annotation.

**Preferred approach for reactive:** Inject `RateLimiterRegistry` and apply `.transformDeferred(RateLimiterOperator.of(rateLimiter))` on the Mono/Flux chain, similar to how circuit breakers are already applied.

- [ ] **Step 3: Apply rate limiting to auth endpoints**

Modify `AuthController.java`:

```java
@RestController
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Obtain and refresh JWT tokens")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final RateLimiterRegistry rateLimiterRegistry;

    @Operation(summary = "Obtain a JWT token")
    @PostMapping({"/api/auth/token", "/auth/token"})
    public Mono<ResponseEntity<TokenResponse>> token(@Valid @RequestBody AuthRequest request) {
        return authService
                .authenticate(request.username(), request.password())
                .map(ResponseEntity::ok)
                .transformDeferred(RateLimiterOperator.of(
                        rateLimiterRegistry.rateLimiter("auth")));
    }

    @Operation(summary = "Refresh an access token using a valid refresh token")
    @PostMapping("/api/auth/refresh")
    public Mono<ResponseEntity<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken())
                .map(ResponseEntity::ok)
                .transformDeferred(RateLimiterOperator.of(
                        rateLimiterRegistry.rateLimiter("auth")));
    }

    // logout stays unlimited
}
```

Import: `io.github.resilience4j.ratelimiter.RateLimiterRegistry` and `io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator`.

- [ ] **Step 4: Apply rate limiting to LLM-triggering endpoints**

For `SimulateController`, `FlagController` (the `analyse` and `chat` methods only), and any research/ingestion endpoints that trigger LLM calls:

```java
// Inject RateLimiterRegistry
private final RateLimiterRegistry rateLimiterRegistry;

// In each LLM-triggering method:
.transformDeferred(RateLimiterOperator.of(
        rateLimiterRegistry.rateLimiter("llm-trigger")))
```

- [ ] **Step 5: Apply rate limiting to admin CRUD endpoints**

For `UserManagementController`, `TenantController`, `GroupProfileController`, and other CRUD controllers, apply the `admin-crud` rate limiter to mutating operations (POST, PUT, DELETE):

```java
.transformDeferred(RateLimiterOperator.of(
        rateLimiterRegistry.rateLimiter("admin-crud")))
```

Read operations (GET) should generally not be rate limited unless they're expensive.

- [ ] **Step 6: Handle RequestNotPermitted in GlobalExceptionHandler**

Check if `GlobalExceptionHandler` exists. If so, add handling for `RequestNotPermitted`:

```java
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

// In the handler class:
@ExceptionHandler(RequestNotPermitted.class)
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public Mono<Map<String, String>> handleRateLimitExceeded(RequestNotPermitted ex) {
    return Mono.just(Map.of("error", "Rate limit exceeded"));
}
```

For WebFlux, the handler may be a `WebExceptionHandler` bean. Follow the existing `CallNotPermittedException` handling pattern already in the codebase.

- [ ] **Step 7: Run admin-api tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -q 2>&1 | cat`
Expected: All tests pass. Some tests may need `RateLimiterRegistry` mocked or a real one injected.

- [ ] **Step 8: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -q 2>&1 | cat
git add emcip-admin-api/
git commit -m "feat(security): add Resilience4j rate limiting to admin-api (RT-014)

Three rate limit tiers: auth (10/min), llm-trigger (20/min),
admin-crud (100/min). Uses RateLimiterOperator on reactive chains.
Returns 429 Too Many Requests when exceeded."
```

---

### Task 2: Circuit breakers on KE↔LLM-O bidirectional REST (S2)

**Files:**
- Modify: `emcip-knowledge-engine/pom.xml`
- Modify: `emcip-llm-orchestrator/pom.xml`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/LlmClientConfig.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/KnowledgeClientConfig.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/KnowledgeEngineClient.java`
- Modify: `emcip-knowledge-engine/src/main/resources/application.yml`
- Modify: `emcip-llm-orchestrator/src/main/resources/application.yml`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/client/LlmOrchestratorClientTest.java` (if exists)
- Test: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/KnowledgeEngineClientTest.java`

**Interfaces:**
- Produces: Circuit breakers that open after failures, returning empty/default results. 10s HTTP timeouts on both clients.

- [ ] **Step 1: Add resilience4j dependency to both services**

Modify `emcip-knowledge-engine/pom.xml` — add inside `<dependencies>`:

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.3.0</version>
</dependency>
```

Modify `emcip-llm-orchestrator/pom.xml` — add the same dependency:

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.3.0</version>
</dependency>
```

- [ ] **Step 2: Add circuit breaker configuration to both services' application.yml**

Add to `emcip-knowledge-engine/src/main/resources/application.yml`:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      llm-orchestrator:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        slow-call-duration-threshold: 10s
        slow-call-rate-threshold: 80
```

Add to `emcip-llm-orchestrator/src/main/resources/application.yml`:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      knowledge-engine:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        slow-call-duration-threshold: 10s
        slow-call-rate-threshold: 80
```

- [ ] **Step 3: Add 10s HTTP timeout to LlmClientConfig (knowledge-engine)**

Modify `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/LlmClientConfig.java`:

```java
@Bean
public RestClient llmOrchestratorRestClient(
        @Value("${knowledge.llm-orchestrator.base-url}") String baseUrl) {
    return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(timeoutRequestFactory())
            .build();
}

private ClientHttpRequestFactory timeoutRequestFactory() {
    var httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();
    var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(java.time.Duration.ofSeconds(10));
    return factory;
}
```

- [ ] **Step 4: Add circuit breaker to LlmOrchestratorClient (knowledge-engine)**

Modify `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java`:

Inject `CircuitBreakerRegistry`:

```java
@RequiredArgsConstructor
public class LlmOrchestratorClient {

    private final RestClient restClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    // Get the circuit breaker instance
    private CircuitBreaker circuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker("llm-orchestrator");
    }
```

Wrap each REST call. For example, the `embed()` method:

```java
public float[] embed(String text) {
    try {
        return CircuitBreaker.decorateCheckedSupplier(circuitBreaker(), () -> {
            AnalyseResponse response = restClient.post()
                    .uri("/api/analyse")
                    .body(Map.of("prompt", text, "taskType", "EMBED"))
                    .retrieve()
                    .body(AnalyseResponse.class);
            if (response == null || response.analysis() == null) {
                return new float[0];
            }
            // ... parse embedding from response.analysis()
            return parseEmbedding(response.analysis());
        }).get();
    } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        log.warn("Circuit breaker open for llm-orchestrator, returning empty embedding");
        return new float[0];
    } catch (Throwable e) {
        log.error("LLM orchestrator embed call failed: {}", e.getMessage());
        return new float[0];
    }
}
```

Apply the same pattern to `extract()`, `analyse()`, and `resolve()`.

- [ ] **Step 5: Add 10s HTTP timeout to KnowledgeClientConfig (llm-orchestrator)**

Modify `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/KnowledgeClientConfig.java`:

```java
@Bean
public RestClient knowledgeEngineRestClient(
        @Value("${knowledge.engine.base-url}") String baseUrl) {
    return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(timeoutRequestFactory())
            .build();
}

private ClientHttpRequestFactory timeoutRequestFactory() {
    var httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();
    var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(java.time.Duration.ofSeconds(10));
    return factory;
}
```

- [ ] **Step 6: Add circuit breaker to KnowledgeEngineClient (llm-orchestrator)**

Modify `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/KnowledgeEngineClient.java`:

Inject `CircuitBreakerRegistry`:

```java
private final RestClient restClient;
private final CircuitBreakerRegistry circuitBreakerRegistry;

private CircuitBreaker circuitBreaker() {
    return circuitBreakerRegistry.circuitBreaker("knowledge-engine");
}
```

Wrap the `search()` method:

```java
public SearchResponse search(String query, String searchType, UUID tenantId, int limit) {
    try {
        return CircuitBreaker.decorateCheckedSupplier(circuitBreaker(), () -> {
            // ... existing REST call logic ...
            return restClient.post()
                    .uri("/api/knowledge/search")
                    .body(Map.of(
                            "query", query,
                            "searchType", searchType,
                            "tenantId", tenantId != null ? tenantId.toString() : null,
                            "limit", limit))
                    .retrieve()
                    .body(SearchResponse.class);
        }).get();
    } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        log.warn("Circuit breaker open for knowledge-engine, returning empty context");
        return SearchResponse.empty();
    } catch (Throwable e) {
        log.error("Knowledge engine search failed: {}", e.getMessage());
        return SearchResponse.empty();
    }
}
```

- [ ] **Step 7: Update LlmClientConfig and KnowledgeClientConfig constructors**

Both config classes need to inject `CircuitBreakerRegistry` and pass it to their respective client constructors. Update the `@Bean` methods:

For `LlmClientConfig`:
```java
@Bean
public LlmOrchestratorClient llmOrchestratorClient(
        RestClient llmOrchestratorRestClient,
        CircuitBreakerRegistry circuitBreakerRegistry) {
    return new LlmOrchestratorClient(llmOrchestratorRestClient, circuitBreakerRegistry);
}
```

For `KnowledgeClientConfig`:
```java
@Bean
public KnowledgeEngineClient knowledgeEngineClient(
        RestClient knowledgeEngineRestClient,
        CircuitBreakerRegistry circuitBreakerRegistry) {
    return new KnowledgeEngineClient(knowledgeEngineRestClient, circuitBreakerRegistry);
}
```

- [ ] **Step 8: Run tests for both services**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-knowledge-engine,emcip-llm-orchestrator -q 2>&1 | cat`
Expected: All tests pass. Existing tests that construct `LlmOrchestratorClient` or `KnowledgeEngineClient` directly will need a `CircuitBreakerRegistry.ofDefaults()` passed in.

- [ ] **Step 9: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -q 2>&1 | cat
git add emcip-knowledge-engine/pom.xml \
        emcip-llm-orchestrator/pom.xml \
        emcip-knowledge-engine/src/ \
        emcip-llm-orchestrator/src/
git commit -m "feat(security): circuit breakers + 10s timeouts on KE↔LLM-O REST (RT-025)

Adds Resilience4j circuit breakers to both LlmOrchestratorClient
(knowledge-engine) and KnowledgeEngineClient (llm-orchestrator).
Both clients get 5s connect + 10s read timeouts via JdkClientHttpRequestFactory.
On open circuit: KE returns empty results, LLM-O returns empty context
(graceful degradation)."
```
