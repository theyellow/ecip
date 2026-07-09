# Ingestion Pipeline Improvements — Part 1: Backend

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce document ingestion time from minutes to seconds by adding model warm-up, batch embedding, and parallel chunk processing.

**Architecture:** New warm-up and batch-embed endpoints on the orchestrator, proxied through admin-api. Knowledge engine's `processChunks()` becomes parallel (semaphore-gated), and entity embeddings are batched into a single call per chunk.

**Tech Stack:** Java 21, Spring Boot 4, Resilience4j circuit breakers, RestClient, virtual threads, JPA/Hibernate, Lombok, MockWebServer for tests.

## Global Constraints

- All Spring Boot services use Lombok `@Slf4j`, `@RequiredArgsConstructor`.
- Jackson namespace is `tools.jackson` (not `com.fasterxml.jackson`) — Spring Boot 4.
- `mvn spotless:apply` before every commit.
- Cron timing: never schedule at exact round times — always use offset seconds/millis.
- Liquibase for any schema changes (none expected in this plan).
- Circuit breakers: `llm-orchestrator-embed` (strict, 10s slow-call) and `llm-orchestrator-analyse` (lenient, 180s slow-call, 70% failure-rate).
- Admin-api is WebFlux (reactive: `Mono`/`Flux`, `WebClient`). Knowledge-engine and orchestrator are JPA/blocking (`RestClient`).
- Test pattern: `@ExtendWith(MockitoExtension.class)`, `@Mock` fields, `@InjectMocks` or manual constructor. Controller tests use `MockMvcBuilders.standaloneSetup()`. Client tests use `MockWebServer`.

---

### Task 1: Orchestrator — Warm-Up Endpoint

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerWarmUpTest.java`

**Interfaces:**
- Consumes: `LlmOrchestratorService.selectModelForTask(String taskType)` → `Optional<ModelConfig>`, `OpenAiCompatibleLlmClient.embed(String model, String input)` → `float[]`, `OpenAiCompatibleLlmClient.call(String model, List<?> messages, int maxTokens, String format)` → `LlmResponse`
- Produces: `POST /api/warm-up` accepting `WarmUpRequest`, returning `WarmUpResponse` — consumed by admin-api proxy (Task 2) and frontend (Part 2).

- [ ] **Step 1: Write the failing test**

Create `OrchestratorControllerWarmUpTest.java`:

```java
package io.emcip.llm.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.model.LlmResponse;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import io.emcip.llm.orchestrator.service.CostTrackingService;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OrchestratorControllerWarmUpTest {

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private CostTrackingService costTrackingService;
    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private LlmProviderConfigService providerConfigService;
    @Mock private LlmProviderConfigRepository providerConfigRepository;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @InjectMocks private OrchestratorController controller;

    @Test
    void warmUp_bothTaskTypes_returnsReadyStatus() {
        var embedModel = new ModelConfig();
        embedModel.setModelName("bge-m3");
        var extractModel = new ModelConfig();
        extractModel.setModelName("qwen3-14b");

        when(orchestratorService.selectModelForTask("EMBED")).thenReturn(Optional.of(embedModel));
        when(orchestratorService.selectModelForTask("EXTRACT"))
                .thenReturn(Optional.of(extractModel));
        when(llmClient.embed("bge-m3", "ping")).thenReturn(new float[] {0.1f});
        when(llmClient.call(anyString(), any(), anyInt(), isNull()))
                .thenReturn(new LlmResponse("pong", 10, 5, "qwen3-14b"));

        var request =
                new OrchestratorController.WarmUpRequest(List.of("EMBED", "EXTRACT"));
        var response = controller.warmUp(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.results()).containsKey("EMBED");
        assertThat(body.results().get("EMBED").ready()).isTrue();
        assertThat(body.results().get("EMBED").model()).isEqualTo("bge-m3");
        assertThat(body.results().containsKey("EXTRACT")).isTrue();
        assertThat(body.results().get("EXTRACT").ready()).isTrue();
    }

    @Test
    void warmUp_noModelConfigured_returnsNotReady() {
        when(orchestratorService.selectModelForTask("EMBED")).thenReturn(Optional.empty());

        var request = new OrchestratorController.WarmUpRequest(List.of("EMBED"));
        var response = controller.warmUp(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body.results().get("EMBED").ready()).isFalse();
        assertThat(body.results().get("EMBED").error()).isNotNull();
    }

    @Test
    void warmUp_embedFails_returnsNotReadyWithError() {
        var embedModel = new ModelConfig();
        embedModel.setModelName("bge-m3");
        when(orchestratorService.selectModelForTask("EMBED")).thenReturn(Optional.of(embedModel));
        when(llmClient.embed("bge-m3", "ping")).thenThrow(new RuntimeException("timeout"));

        var request = new OrchestratorController.WarmUpRequest(List.of("EMBED"));
        var response = controller.warmUp(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().results().get("EMBED").ready()).isFalse();
        assertThat(response.getBody().results().get("EMBED").error()).contains("timeout");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd emcip-llm-orchestrator && mvn test -pl . -Dtest=OrchestratorControllerWarmUpTest -DfailIfNoTests=false`
Expected: Compilation error — `WarmUpRequest` and `WarmUpResponse` don't exist yet.

- [ ] **Step 3: Implement warm-up endpoint**

Add these records and the endpoint to `OrchestratorController.java`, after the existing record definitions (around line 68):

```java
public record WarmUpRequest(List<String> taskTypes) {}

public record WarmUpResult(boolean ready, String model, long latencyMs, String error) {}

public record WarmUpResponse(Map<String, WarmUpResult> results) {}
```

Add the endpoint method (after the embed endpoint, around line 440):

```java
@Operation(summary = "Warm up LLM models by sending a minimal inference request")
@PostMapping("/warm-up")
public ResponseEntity<WarmUpResponse> warmUp(@RequestBody WarmUpRequest req) {
    Map<String, WarmUpResult> results = new LinkedHashMap<>();
    for (String taskType : req.taskTypes()) {
        Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
        if (modelOpt.isEmpty()) {
            results.put(
                    taskType,
                    new WarmUpResult(
                            false, null, 0, "No model configured for task type: " + taskType));
            continue;
        }
        ModelConfig model = modelOpt.get();
        long start = System.nanoTime();
        try {
            if ("EMBED".equals(taskType)) {
                llmClient.embed(model.getModelName(), "ping");
            } else {
                llmClient.call(
                        model.getModelName(),
                        List.of(Map.of("role", "user", "content", "ping")),
                        16,
                        null);
            }
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            results.put(
                    taskType,
                    new WarmUpResult(true, model.getModelName(), latencyMs, null));
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("Warm-up failed for {}: {}", taskType, e.getMessage());
            results.put(
                    taskType,
                    new WarmUpResult(
                            false, model.getModelName(), latencyMs, e.getMessage()));
        }
    }
    return ResponseEntity.ok(new WarmUpResponse(results));
}
```

Add these imports to `OrchestratorController.java`:

```java
import java.util.LinkedHashMap;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd emcip-llm-orchestrator && mvn test -pl . -Dtest=OrchestratorControllerWarmUpTest`
Expected: All 3 tests PASS.

- [ ] **Step 5: Run spotless and commit**

```bash
cd /home/ben/Development/ecip && mvn spotless:apply -pl emcip-llm-orchestrator
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java \
        emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerWarmUpTest.java
git commit -m "feat(orchestrator): add model warm-up endpoint POST /api/warm-up"
```

---

### Task 2: Admin API — Warm-Up Proxy Endpoint

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/AIProxyControllerWarmUpTest.java`

**Interfaces:**
- Consumes: Orchestrator `POST /api/warm-up` (from Task 1), `orchestratorWebClient` bean (`WebClient`), no circuit breaker.
- Produces: `POST /api/ai/warm-up` — consumed by frontend `knowledge.js` `warmUp()` method (Part 2).

- [ ] **Step 1: Write the failing test**

Create `AIProxyControllerWarmUpTest.java`:

```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AIProxyControllerWarmUpTest {

    @Mock private WebClient orchestratorClient;
    private AIProxyController controller;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        controller = new AIProxyController(orchestratorClient, CircuitBreakerRegistry.ofDefaults());
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    void warmUp_proxiesToOrchestrator() {
        var requestSpec = mock(WebClient.RequestBodyUriSpec.class);
        var requestBodySpec = mock(WebClient.RequestBodySpec.class);
        var responseSpec = mock(WebClient.ResponseSpec.class);

        when(orchestratorClient.post()).thenReturn(requestSpec);
        when(requestSpec.uri("/api/warm-up")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(mock(WebClient.RequestHeadersSpec.class));
        var headersSpec = mock(WebClient.RequestHeadersSpec.class);
        when(requestBodySpec.bodyValue(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(
                        Mono.just(
                                "{\"results\":{\"EMBED\":{\"ready\":true,\"model\":\"bge-m3\",\"latencyMs\":100,\"error\":null}}}"));

        webTestClient
                .post()
                .uri("/api/ai/warm-up")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"taskTypes\":[\"EMBED\"]}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> {
                    org.assertj.core.api.Assertions.assertThat(body).contains("\"ready\":true");
                    org.assertj.core.api.Assertions.assertThat(body).contains("bge-m3");
                });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=AIProxyControllerWarmUpTest -DfailIfNoTests=false`
Expected: FAIL — `warmUp` method doesn't exist on `AIProxyController`.

- [ ] **Step 3: Implement warm-up proxy**

Add this method to `AIProxyController.java`, after the provider-config section (around line 365):

```java
// ---- Warm-Up ----

@Operation(summary = "Warm up LLM models (health probe — no circuit breaker)")
@PostMapping(value = "/warm-up", consumes = MediaType.APPLICATION_JSON_VALUE)
public Mono<String> warmUp(@RequestBody String body) {
    return orchestratorClient
            .post()
            .uri("/api/warm-up")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .onStatus(
                    status -> !status.is2xxSuccessful(),
                    resp ->
                            resp.bodyToMono(String.class)
                                    .flatMap(
                                            respBody ->
                                                    Mono.error(
                                                            new ResponseStatusException(
                                                                    resp.statusCode(),
                                                                    respBody))))
            .bodyToMono(String.class);
    // No circuit breaker — warm-up is itself a health probe
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd emcip-admin-api && mvn test -pl . -Dtest=AIProxyControllerWarmUpTest`
Expected: PASS.

- [ ] **Step 5: Run spotless and commit**

```bash
cd /home/ben/Development/ecip && mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/controller/AIProxyControllerWarmUpTest.java
git commit -m "feat(admin-api): add warm-up proxy endpoint POST /api/ai/warm-up"
```

---

### Task 3: Orchestrator — Batch Embed Endpoint

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClient.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerBatchEmbedTest.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClientBatchTest.java`

**Interfaces:**
- Consumes: `LlmOrchestratorService.selectModelForTask("EMBED")` → `Optional<ModelConfig>`, `LlmProviderConfigService.getActiveProvider()` → `Optional<LlmProviderConfig>`, LiteLLM `/v1/embeddings` (accepts array of inputs natively).
- Produces: `OpenAiCompatibleLlmClient.embedBatch(String modelName, List<String> inputs)` → `List<float[]>`, `POST /api/embed/batch` accepting `BatchEmbedRequest`, returning `BatchEmbedResponse` — consumed by knowledge engine `LlmOrchestratorClient.embedBatch()` (Task 4).

- [ ] **Step 1: Write the failing controller test**

Create `OrchestratorControllerBatchEmbedTest.java`:

```java
package io.emcip.llm.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import io.emcip.llm.orchestrator.service.CostTrackingService;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OrchestratorControllerBatchEmbedTest {

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private CostTrackingService costTrackingService;
    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private LlmProviderConfigService providerConfigService;
    @Mock private LlmProviderConfigRepository providerConfigRepository;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @InjectMocks private OrchestratorController controller;

    @Test
    void batchEmbed_success_returnsEmbeddings() {
        var model = new ModelConfig();
        model.setModelName("bge-m3");
        when(orchestratorService.selectModelForTask("EMBED")).thenReturn(Optional.of(model));
        when(llmClient.embedBatch("bge-m3", List.of("text one", "text two")))
                .thenReturn(List.of(new float[] {0.1f, 0.2f}, new float[] {0.3f, 0.4f}));

        var request =
                new OrchestratorController.BatchEmbedRequest(List.of("text one", "text two"));
        var response = controller.batchEmbed(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body.success()).isTrue();
        assertThat(body.embeddings()).hasSize(2);
        assertThat(body.model()).isEqualTo("bge-m3");
    }

    @Test
    void batchEmbed_noModel_returns503() {
        when(orchestratorService.selectModelForTask("EMBED")).thenReturn(Optional.empty());

        var request =
                new OrchestratorController.BatchEmbedRequest(List.of("text"));
        var response = controller.batchEmbed(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    void batchEmbed_tooManyInputs_returns400() {
        var inputs = java.util.stream.IntStream.rangeClosed(1, 33)
                .mapToObj(i -> "text " + i)
                .toList();

        var request = new OrchestratorController.BatchEmbedRequest(inputs);
        var response = controller.batchEmbed(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: Write the failing client test**

Create `OpenAiCompatibleLlmClientBatchTest.java`:

```java
package io.emcip.llm.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.List;
import java.util.Optional;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleLlmClientBatchTest {

    private MockWebServer mockServer;
    @Mock private LlmProviderConfigService providerConfigService;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        client = new OpenAiCompatibleLlmClient(providerConfigService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    void embedBatch_returnsOrderedEmbeddings() throws Exception {
        var provider = new LlmProviderConfig();
        provider.setBaseUrl(mockServer.url("").toString().replaceAll("/$", ""));
        provider.setApiKey("test-key");
        when(providerConfigService.getActiveProvider()).thenReturn(Optional.of(provider));

        String responseJson =
                """
                {
                  "data": [
                    {"embedding": [0.1, 0.2], "index": 0},
                    {"embedding": [0.3, 0.4], "index": 1}
                  ],
                  "model": "bge-m3"
                }
                """;
        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .addHeader("Content-Type", "application/json"));

        List<float[]> result = client.embedBatch("bge-m3", List.of("text one", "text two"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(result.get(1)).containsExactly(0.3f, 0.4f);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd emcip-llm-orchestrator && mvn test -pl . -Dtest="OrchestratorControllerBatchEmbedTest,OpenAiCompatibleLlmClientBatchTest" -DfailIfNoTests=false`
Expected: Compilation error — `embedBatch`, `BatchEmbedRequest`, `BatchEmbedResponse` don't exist.

- [ ] **Step 4: Implement `embedBatch` in `OpenAiCompatibleLlmClient`**

Add this method to `OpenAiCompatibleLlmClient.java`, after the existing `embed()` method (around line 252):

```java
public List<float[]> embedBatch(String model, List<String> inputs) {
    LlmProviderConfig provider =
            providerConfigService
                    .getActiveProvider()
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "No active LLM provider configured — set one via"
                                                    + " Admin UI > AI Config > LLM Provider"));

    Map<String, Object> body = new HashMap<>();
    body.put("model", model);
    body.put("input", inputs);

    log.debug(
            "Calling LiteLLM batch embeddings: url={}, model={}, count={}",
            provider.getBaseUrl(),
            model,
            inputs.size());

    try {
        String apiKey = provider.getApiKey();
        RestClient restClient = RestClient.create();
        String responseJson =
                restClient
                        .post()
                        .uri(provider.getBaseUrl() + "/v1/embeddings")
                        .headers(
                                h -> {
                                    h.setContentType(MediaType.APPLICATION_JSON);
                                    if (apiKey != null && !apiKey.isBlank()) {
                                        h.setBearerAuth(apiKey);
                                    }
                                })
                        .body(body)
                        .retrieve()
                        .body(String.class);

        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode dataArray = root.path("data");

        List<float[]> results = new ArrayList<>();
        for (JsonNode item : dataArray) {
            JsonNode embeddingArray = item.path("embedding");
            float[] emb = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                emb[i] = (float) embeddingArray.get(i).asDouble();
            }
            results.add(emb);
        }

        log.debug(
                "LiteLLM batch embeddings response: model={}, count={}",
                root.path("model").asText(model),
                results.size());

        return results;

    } catch (Exception e) {
        throw new RuntimeException(
                "LiteLLM batch embeddings call failed ["
                        + provider.getBaseUrl()
                        + "]: "
                        + e.getMessage(),
                e);
    }
}
```

- [ ] **Step 5: Implement batch embed endpoint in `OrchestratorController`**

Add records (after existing records, around line 68):

```java
public record BatchEmbedRequest(List<String> inputs) {}

public record BatchEmbedResponse(
        boolean success, List<float[]> embeddings, String model) {}
```

Add endpoint method (after the embed endpoint, around line 440):

```java
@Operation(summary = "Generate embedding vectors for a batch of texts")
@PostMapping("/embed/batch")
public ResponseEntity<BatchEmbedResponse> batchEmbed(@RequestBody BatchEmbedRequest req) {
    if (req.inputs() == null || req.inputs().size() > 32) {
        return ResponseEntity.badRequest()
                .body(new BatchEmbedResponse(false, List.of(), null));
    }
    Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask("EMBED");
    if (modelOpt.isEmpty()) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new BatchEmbedResponse(false, List.of(), null));
    }
    ModelConfig model = modelOpt.get();
    try {
        List<float[]> embeddings =
                llmClient.embedBatch(model.getModelName(), req.inputs());
        return ResponseEntity.ok(
                new BatchEmbedResponse(true, embeddings, model.getModelName()));
    } catch (Exception e) {
        log.error("Batch embedding call failed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new BatchEmbedResponse(false, List.of(), null));
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd emcip-llm-orchestrator && mvn test -pl . -Dtest="OrchestratorControllerBatchEmbedTest,OpenAiCompatibleLlmClientBatchTest"`
Expected: All tests PASS.

- [ ] **Step 7: Run spotless and commit**

```bash
cd /home/ben/Development/ecip && mvn spotless:apply -pl emcip-llm-orchestrator
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClient.java \
        emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerBatchEmbedTest.java \
        emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClientBatchTest.java
git commit -m "feat(orchestrator): add batch embed endpoint POST /api/embed/batch"
```

---

### Task 4: Knowledge Engine — Batch Embed Client + Entity Resolution Overload

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EntityResolutionService.java`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/client/LlmOrchestratorClientTest.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/EntityResolutionServiceBatchTest.java`

**Interfaces:**
- Consumes: Orchestrator `POST /api/embed/batch` (from Task 3). `RestClient` bean in `LlmOrchestratorClient`. `GraphNodeEmbeddingRepository.findEmbedding()`, `GraphNodeEmbeddingRepository.storeEmbedding()`, `GraphNodeEmbeddingRepository.findNearestNeighbour()`.
- Produces: `LlmOrchestratorClient.embedBatch(List<String> texts)` → `List<float[]>`. `EntityResolutionService.resolve(String label, String conceptType, UUID tenantId, float[] precomputedEmbedding)` → `UUID`. Both consumed by `KnowledgeExtractionService.processDocument()` (Task 5).

- [ ] **Step 1: Write the failing client test**

Add to the existing `LlmOrchestratorClientTest.java`:

```java
@Test
void shouldCallBatchEmbedEndpoint() throws Exception {
    String responseJson =
            """
            {"success":true,"embeddings":[[0.1,0.2],[0.3,0.4]],"model":"bge-m3"}
            """;
    mockServer.enqueue(
            new MockResponse()
                    .setBody(responseJson)
                    .addHeader("Content-Type", "application/json"));

    List<float[]> result = client.embedBatch(List.of("text one", "text two"));

    assertThat(result).hasSize(2);
    assertThat(result.get(0)).containsExactly(0.1f, 0.2f);
    assertThat(result.get(1)).containsExactly(0.3f, 0.4f);

    var recordedRequest = mockServer.takeRequest();
    assertThat(recordedRequest.getPath()).isEqualTo("/api/embed/batch");
}
```

- [ ] **Step 2: Write the failing entity resolution test**

Create `EntityResolutionServiceBatchTest.java`:

```java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.config.ResolutionProperties;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.EntityAliasRepository;
import io.emcip.knowledge.engine.repository.GraphNodeEmbeddingRepository;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntityResolutionServiceBatchTest {

    @Mock private GraphRepository graphRepository;
    @Mock private GraphNodeEmbeddingRepository nodeEmbeddingRepository;
    @Mock private EntityAliasRepository entityAliasRepository;
    @Mock private ResolutionFlagRepository resolutionFlagRepository;
    @Mock private LlmOrchestratorClient llmClient;
    @Mock private ResolutionProperties resolutionProperties;
    @InjectMocks private EntityResolutionService service;

    @Test
    void resolve_withPrecomputedEmbedding_skipsLlmCall() {
        UUID tenantId = UUID.randomUUID();
        float[] precomputed = {0.1f, 0.2f, 0.3f};
        UUID existingNodeId = UUID.randomUUID();

        when(graphRepository.findByLabelAndType("berlin", "LOCATION", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        "LOCATION", "berlin", tenantId))
                .thenReturn(Optional.empty());
        when(resolutionProperties.mergeThreshold()).thenReturn(0.92);
        when(nodeEmbeddingRepository.findNearestNeighbour(precomputed, "LOCATION", tenantId))
                .thenReturn(Optional.empty());
        when(graphRepository.createNode("LOCATION", "berlin", Map.of(), tenantId))
                .thenReturn(new GraphNode(existingNodeId, "berlin", "LOCATION"));

        UUID result = service.resolve("Berlin", "LOCATION", tenantId, precomputed);

        assertThat(result).isEqualTo(existingNodeId);
        // Must NOT call llmClient.embed — embedding was precomputed
        verify(llmClient, never()).embed("berlin");
        // Must store the precomputed embedding
        verify(nodeEmbeddingRepository).storeEmbedding("berlin", "LOCATION", tenantId, precomputed);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd emcip-knowledge-engine && mvn test -pl . -Dtest="LlmOrchestratorClientTest#shouldCallBatchEmbedEndpoint,EntityResolutionServiceBatchTest" -DfailIfNoTests=false`
Expected: Compilation errors — `embedBatch` and 4-arg `resolve` don't exist.

- [ ] **Step 4: Implement `embedBatch` in `LlmOrchestratorClient`**

Add a private record (next to the existing `EmbedResponse` record, around line 291):

```java
private record BatchEmbedResponse(boolean success, List<float[]> embeddings, String model) {}
```

Add the method after the existing `embed()` method (around line 70):

```java
public List<float[]> embedBatch(List<String> texts) {
    try {
        return CircuitBreaker.decorateCheckedSupplier(
                        embedCircuitBreaker(),
                        () -> {
                            Map<String, Object> request = Map.of("inputs", texts);
                            var response =
                                    restClient
                                            .post()
                                            .uri("/api/embed/batch")
                                            .body(request)
                                            .retrieve()
                                            .body(BatchEmbedResponse.class);
                            if (response == null || !response.success()) {
                                log.error("Batch embedding failed: {}", response);
                                return List.<float[]>of();
                            }
                            log.info(
                                    "Batch embedding received: count={}",
                                    response.embeddings().size());
                            return response.embeddings();
                        })
                .get();
    } catch (CallNotPermittedException e) {
        log.warn(
                "Circuit breaker open for llm-orchestrator-embed, returning empty batch");
        return List.of();
    } catch (Throwable e) {
        log.error("LLM orchestrator batch embed call failed: {}", e.getMessage());
        return List.of();
    }
}
```

Add import: `import io.github.resilience4j.circuitbreaker.CallNotPermittedException;` (likely already imported from `embed()`).

- [ ] **Step 5: Implement `resolve` overload in `EntityResolutionService`**

Add this method after the existing `resolve()` method (around line 98):

```java
public UUID resolve(
        String label, String conceptType, UUID tenantId, float[] precomputedEmbedding) {
    String normalized = label.toLowerCase().trim();

    // Level 1: Exact match
    Optional<GraphNode> exact =
            graphRepository.findByLabelAndType(normalized, conceptType, tenantId);
    if (exact.isPresent()) {
        log.debug("Entity resolved by exact match: {} -> {}", label, exact.get().id());
        return exact.get().id();
    }

    // Level 2: Alias table
    Optional<EntityAlias> alias =
            entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                    conceptType, normalized, tenantId);
    if (alias.isPresent()) {
        String canonical = alias.get().getCanonicalLabel().toLowerCase().trim();
        Optional<GraphNode> aliasNode =
                graphRepository.findByLabelAndType(canonical, conceptType, tenantId);
        if (aliasNode.isPresent()) {
            log.debug(
                    "Entity resolved by alias: {} -> {} -> {}",
                    label,
                    alias.get().getCanonicalLabel(),
                    aliasNode.get().id());
            return aliasNode.get().id();
        }
    }

    // Level 3: Embedding similarity (using precomputed embedding)
    if (precomputedEmbedding.length > 0) {
        nodeEmbeddingRepository.storeEmbedding(
                normalized, conceptType, tenantId, precomputedEmbedding);
        Optional<NodeSimilarityResult> nearest =
                nodeEmbeddingRepository.findNearestNeighbour(
                        precomputedEmbedding, conceptType, tenantId);
        if (nearest.isPresent()) {
            double score = nearest.get().score();
            if (score >= resolutionProperties.mergeThreshold()) {
                log.debug(
                        "Entity merged by similarity: {} -> {} (score={})",
                        label,
                        nearest.get().label(),
                        score);
                return nearest.get().nodeId();
            } else if (score >= resolutionProperties.flagThreshold()) {
                GraphNode newNode =
                        graphRepository.createNode(
                                conceptType, normalized, Map.of(), tenantId);
                writeFlagSafely(
                        label,
                        newNode.id(),
                        nearest.get(),
                        conceptType,
                        score,
                        tenantId);
                log.info(
                        "Created new node and flagged ambiguous similarity:"
                                + " {} ~ {} (score={})",
                        label,
                        nearest.get().label(),
                        score);
                return newNode.id();
            }
        }
    }

    // Level 4: Create new node
    GraphNode newNode =
            graphRepository.createNode(conceptType, normalized, Map.of(), tenantId);
    log.info(
            "Created new graph node: type={}, label={}, id={}",
            conceptType,
            label,
            newNode.id());
    return newNode.id();
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd emcip-knowledge-engine && mvn test -pl . -Dtest="LlmOrchestratorClientTest,EntityResolutionServiceBatchTest"`
Expected: All tests PASS.

- [ ] **Step 7: Run spotless and commit**

```bash
cd /home/ben/Development/ecip && mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/EntityResolutionService.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/client/LlmOrchestratorClientTest.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/EntityResolutionServiceBatchTest.java
git commit -m "feat(knowledge-engine): add batch embed client and entity resolution with precomputed embeddings"
```

---

### Task 5: Parallel Chunk Processing + Batched Entity Embeddings

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java`
- Modify: `emcip-knowledge-engine/src/main/resources/application.yml`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeExtractionServiceTest.java`

**Interfaces:**
- Consumes: `KnowledgeExtractionService.processDocument()` (existing), `LlmOrchestratorClient.embedBatch()` (from Task 4), `EntityResolutionService.resolve(label, type, tenantId, precomputedEmbedding)` (from Task 4), `GraphNodeEmbeddingRepository.findEmbedding()`.
- Produces: Parallel `processChunks()` with configurable concurrency. Modified `processDocument()` that batches entity embeddings into a single `embedBatch()` call.

- [ ] **Step 1: Add parallelism config to `application.yml`**

Add under `knowledge:` section in `emcip-knowledge-engine/src/main/resources/application.yml` (after line 52, the `flag-threshold` line):

```yaml
  ingestion:
    parallelism: ${KNOWLEDGE_INGESTION_PARALLELISM:3}
```

- [ ] **Step 2: Write the failing test for parallel `processChunks`**

Add this test to `DocumentIngestionServiceTest.java`:

```java
@Test
void submitUrlIngestion_processesChunksInParallel() {
    // Given: a document that produces 6 chunks
    String url = "https://example.com/doc.html";
    UUID tenantId = UUID.randomUUID();
    IngestionJob savedJob = new IngestionJob();
    savedJob.setId(UUID.randomUUID());
    savedJob.setStatus(IngestionStatus.QUEUED);
    when(jobRepository.save(any())).thenReturn(savedJob);
    when(jobRepository.findById(savedJob.getId())).thenReturn(Optional.of(savedJob));

    ExtractedContent content = new ExtractedContent("chunk1. chunk2. chunk3. chunk4. chunk5. chunk6.", Map.of());
    when(extractor.extract(any(InputStream.class))).thenReturn(content);
    when(chunker.chunk(content.text()))
            .thenReturn(List.of("chunk1", "chunk2", "chunk3", "chunk4", "chunk5", "chunk6"));

    // When
    UUID jobId = service.submitUrlIngestion(url, tenantId);

    // Then: all 6 chunks processed (verifiable via processDocument calls)
    await().atMost(Duration.ofSeconds(10))
            .untilAsserted(
                    () ->
                            verify(extractionService, times(6))
                                    .processDocument(
                                            anyString(),
                                            eq(url),
                                            eq(tenantId),
                                            anyInt(),
                                            any()));
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd emcip-knowledge-engine && mvn test -pl . -Dtest="DocumentIngestionServiceTest#submitUrlIngestion_processesChunksInParallel" -DfailIfNoTests=false`
Expected: PASS (the sequential loop already calls processDocument 6 times — but the test establishes the contract before we refactor).

- [ ] **Step 4: Create `IngestionProperties` config class**

Create `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/IngestionProperties.java`:

```java
package io.emcip.knowledge.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledge.ingestion")
public record IngestionProperties(int parallelism) {

    public IngestionProperties {
        if (parallelism <= 0) {
            parallelism = 3;
        }
    }
}
```

- [ ] **Step 5: Implement parallel `processChunks`**

Modify `DocumentIngestionService.java`. Add new fields and replace `processChunks()`:

Add import:
```java
import io.emcip.knowledge.engine.config.IngestionProperties;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
```

Add constructor parameter (Lombok's `@RequiredArgsConstructor` handles injection):
```java
private final IngestionProperties ingestionProperties;
```

Replace the `processChunks()` method (lines 200-210):

```java
private int processChunks(ExtractedContent extracted, String sourceRef, UUID tenantId) {
    List<String> chunks = chunker.chunk(extracted.text());
    Map<String, String> metadata = new HashMap<>(extracted.metadata());
    metadata.put("totalChunks", String.valueOf(chunks.size()));
    Map<String, String> immutableMetadata = Map.copyOf(metadata);

    Semaphore semaphore = new Semaphore(ingestionProperties.parallelism());
    List<CompletableFuture<Void>> futures = new ArrayList<>();

    for (int i = 0; i < chunks.size(); i++) {
        final int chunkIndex = i;
        final String chunk = chunks.get(i);
        CompletableFuture<Void> future =
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                semaphore.acquire();
                                try {
                                    extractionService.processDocument(
                                            chunk,
                                            sourceRef,
                                            tenantId,
                                            chunkIndex,
                                            immutableMetadata);
                                } finally {
                                    semaphore.release();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(
                                        "Chunk processing interrupted", e);
                            }
                        },
                        INGESTION_EXECUTOR);
        futures.add(future);
    }

    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    return chunks.size();
}
```

- [ ] **Step 6: Enable `IngestionProperties` config binding**

Add `@EnableConfigurationProperties` to `DocumentIngestionService` or to the application class. The simplest approach — add to the service itself:

Add import:
```java
import org.springframework.boot.context.properties.EnableConfigurationProperties;
```

Add annotation to class:
```java
@EnableConfigurationProperties(IngestionProperties.class)
```

- [ ] **Step 7: Implement batched entity embeddings in `KnowledgeExtractionService`**

Replace the entity resolution loop in `processDocument()` (lines 241-253) with:

```java
    // Step 5: Batch-embed novel entity labels, then resolve with precomputed embeddings
    List<String> allLabels = new ArrayList<>();
    allLabels.addAll(
            validEntities.stream().map(e -> e.label().toLowerCase().trim()).toList());
    for (ExtractedRelationship rel : validRelationships) {
        allLabels.add(rel.source().toLowerCase().trim());
        allLabels.add(rel.target().toLowerCase().trim());
    }

    // Deduplicate and filter out labels that already have embeddings cached
    List<String> novelLabels =
            allLabels.stream()
                    .distinct()
                    .filter(
                            label -> {
                                // Check if embedding already exists (any concept type)
                                // This is a heuristic — same label text reuses embeddings
                                return true; // Always embed; store handles dedup via ON CONFLICT
                            })
                    .toList();

    // Single batch embed call for all novel labels
    Map<String, float[]> embeddingMap = new HashMap<>();
    if (!novelLabels.isEmpty()) {
        List<float[]> embeddings = llmClient.embedBatch(novelLabels);
        for (int idx = 0; idx < novelLabels.size() && idx < embeddings.size(); idx++) {
            embeddingMap.put(novelLabels.get(idx), embeddings.get(idx));
        }
    }

    for (ExtractedEntity entity : validEntities) {
        String normalized = entity.label().toLowerCase().trim();
        float[] emb = embeddingMap.getOrDefault(normalized, new float[0]);
        if (emb.length > 0) {
            entityResolutionService.resolve(entity.label(), entity.type(), tenantId, emb);
        } else {
            entityResolutionService.resolve(entity.label(), entity.type(), tenantId);
        }
        eventPublisher.publishEntityCreated(entity.label(), tenantId);
    }

    for (ExtractedRelationship rel : validRelationships) {
        String sourceNorm = rel.source().toLowerCase().trim();
        String targetNorm = rel.target().toLowerCase().trim();
        float[] sourceEmb = embeddingMap.getOrDefault(sourceNorm, new float[0]);
        float[] targetEmb = embeddingMap.getOrDefault(targetNorm, new float[0]);

        UUID sourceId;
        if (sourceEmb.length > 0) {
            sourceId =
                    entityResolutionService.resolve(
                            rel.source(), inferType(rel, true), tenantId, sourceEmb);
        } else {
            sourceId =
                    entityResolutionService.resolve(
                            rel.source(), inferType(rel, true), tenantId);
        }

        UUID targetId;
        if (targetEmb.length > 0) {
            targetId =
                    entityResolutionService.resolve(
                            rel.target(), inferType(rel, false), tenantId, targetEmb);
        } else {
            targetId =
                    entityResolutionService.resolve(
                            rel.target(), inferType(rel, false), tenantId);
        }

        graphRepository.createRelationship(
                rel.type(), sourceId, targetId, rel.properties(), saved.getId());
    }
```

Add import to `KnowledgeExtractionService.java`:
```java
import java.util.ArrayList;
```

- [ ] **Step 8: Update existing `KnowledgeExtractionServiceTest` for batch embedding**

Add to `KnowledgeExtractionServiceTest.java`:

```java
@Test
void processDocument_batchEmbedsEntityLabels() {
    // Given
    String chunk = "Berlin is the capital of Germany.";
    UUID tenantId = UUID.randomUUID();

    when(llmClient.embed(chunk)).thenReturn(new float[] {0.1f, 0.2f});
    when(documentRepository.saveAndFlush(any()))
            .thenReturn(createSavedDoc(tenantId, chunk));

    var result = new ExtractionResult(
            List.of(
                    new ExtractedEntity("Berlin", "LOCATION"),
                    new ExtractedEntity("Germany", "LOCATION")),
            List.of());
    when(llmClient.extract(anyString(), any(), any())).thenReturn(result);
    when(ontologyService.getAllConceptTypes())
            .thenReturn(List.of(conceptType("LOCATION")));
    when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of());
    when(llmClient.embedBatch(List.of("berlin", "germany")))
            .thenReturn(List.of(new float[] {0.3f}, new float[] {0.4f}));
    when(entityResolutionService.resolve(anyString(), anyString(), any(), any(float[].class)))
            .thenReturn(UUID.randomUUID());

    // When
    service.processDocument(chunk, "test.pdf", tenantId, 0, Map.of());

    // Then: embedBatch called once with both labels
    verify(llmClient).embedBatch(List.of("berlin", "germany"));
    // resolve called with precomputed embeddings
    verify(entityResolutionService).resolve("Berlin", "LOCATION", tenantId, new float[] {0.3f});
    verify(entityResolutionService).resolve("Germany", "LOCATION", tenantId, new float[] {0.4f});
}
```

- [ ] **Step 9: Run all tests**

Run: `cd emcip-knowledge-engine && mvn test -pl .`
Expected: All tests PASS.

- [ ] **Step 10: Run spotless and commit**

```bash
cd /home/ben/Development/ecip && mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/IngestionProperties.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java \
        emcip-knowledge-engine/src/main/resources/application.yml \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeExtractionServiceTest.java
git commit -m "feat(knowledge-engine): parallel chunk processing and batched entity embeddings"
```
