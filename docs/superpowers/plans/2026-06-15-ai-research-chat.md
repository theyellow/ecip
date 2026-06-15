# AI Research Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-shot AI Analysis section in the Flag Detail modal with a multi-turn chat backed by LiteLLM.

**Architecture:** New `chat()` method on `OpenAiCompatibleLlmClient` accepts a messages array. New `POST /api/chat` on the orchestrator forwards it to `/v1/chat/completions`. Admin-api `FlagService.chat()` prepends a system prompt built from the flag context. UI renders messages in a scrollable list with a text input for follow-ups.

**Tech Stack:** Java 21, Spring Boot 4, Spring WebFlux, React, CSS Modules

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `emcip-llm-orchestrator/.../client/OpenAiCompatibleLlmClient.java` | Modify | Add `chat()` method accepting messages array |
| `emcip-llm-orchestrator/.../controller/OrchestratorController.java` | Modify | Add `POST /api/chat` endpoint + request/response records |
| `emcip-llm-orchestrator/.../client/OpenAiCompatibleLlmClientTest.java` | Create | Test `chat()` method |
| `emcip-llm-orchestrator/.../controller/OrchestratorControllerChatTest.java` | Create | Test `POST /api/chat` endpoint |
| `emcip-admin-api/.../service/FlagService.java` | Modify | Add `chat()` method |
| `emcip-admin-api/.../controller/FlagController.java` | Modify | Add `POST /{id}/chat` endpoint |
| `emcip-admin-api/.../controller/FlagControllerChatTest.java` | Create | Test chat endpoint |
| `emcip-admin-ui/.../api/flags.js` | Modify | Add `chat()` function |
| `emcip-admin-ui/.../pages/Flags/Flags.jsx` | Modify | Replace AI Analysis with chat UI |
| `emcip-admin-ui/.../pages/Flags/Flags.module.css` | Modify | Add chat styling classes |

---

### Task 1: OpenAiCompatibleLlmClient — add chat() method

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClient.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClientTest.java`

- [ ] **Step 1: Write the test**

Create `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClientTest.java`:

```java
package io.emcip.llm.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleLlmClientTest {

    @Mock private LlmProviderConfigService providerConfigService;
    private MockWebServer server;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OpenAiCompatibleLlmClient(providerConfigService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    private LlmProviderConfig mockProvider() {
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setBaseUrl(server.url("").toString().replaceAll("/$", ""));
        provider.setApiKey(null);
        provider.setActive(true);
        when(providerConfigService.getActiveProvider()).thenReturn(Optional.of(provider));
        return provider;
    }

    @Test
    void chat_sendsMessagesArrayAndReturnsContent() throws Exception {
        mockProvider();
        String responseJson =
                """
                {"choices":[{"message":{"content":"Hello from LLM"}}],\
                "usage":{"prompt_tokens":10,"completion_tokens":5},\
                "model":"qwen3-30b-a3b"}""";
        server.enqueue(
                new MockResponse.Builder()
                        .body(responseJson)
                        .addHeader("Content-Type", "application/json")
                        .build());

        List<Map<String, String>> messages =
                List.of(
                        Map.of("role", "system", "content", "You are helpful"),
                        Map.of("role", "user", "content", "Hi"));

        LlmResponse response = client.chat("qwen3-30b-a3b", messages, 1024, 0.3);

        assertThat(response.content()).isEqualTo("Hello from LLM");
        assertThat(response.inputTokens()).isEqualTo(10);
        assertThat(response.outputTokens()).isEqualTo(5);
        assertThat(response.model()).isEqualTo("qwen3-30b-a3b");
    }

    @Test
    void chat_noActiveProvider_throws() {
        when(providerConfigService.getActiveProvider()).thenReturn(Optional.empty());

        List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", "Hi"));

        assertThatThrownBy(() -> client.chat("model", messages, 1024, 0.3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active LLM provider");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=OpenAiCompatibleLlmClientTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -5 | cat`
Expected: Compilation error — `chat()` method doesn't exist yet.

- [ ] **Step 3: Add mockwebserver3 test dependency to emcip-llm-orchestrator/pom.xml**

Add in the `<dependencies>` section alongside other test deps:

```xml
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>mockwebserver3</artifactId>
      <version>5.2.1</version>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 4: Implement chat() method**

In `OpenAiCompatibleLlmClient.java`, add this method after the existing `call()` method:

```java
    /**
     * Call the OpenAI-compatible chat completions endpoint with a pre-built messages array.
     * Supports multi-turn conversations.
     *
     * @param model Model name as configured in LiteLLM
     * @param messages List of {role, content} maps — system, user, assistant turns
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0–2.0)
     * @return LlmResponse with content and token counts
     */
    public LlmResponse chat(
            String model,
            List<Map<String, String>> messages,
            int maxTokens,
            double temperature) {

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
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("messages", messages);

        log.debug(
                "Calling LiteLLM chat: url={}, model={}, turns={}, maxTokens={}",
                provider.getBaseUrl(),
                model,
                messages.size(),
                maxTokens);

        try {
            String apiKey = provider.getApiKey();
            RestClient restClient = RestClient.create();
            String responseJson =
                    restClient
                            .post()
                            .uri(provider.getBaseUrl() + "/v1/chat/completions")
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
            String content = root.path("choices").get(0).path("message").path("content").asText();
            int inputTokens = root.path("usage").path("prompt_tokens").asInt();
            int outputTokens = root.path("usage").path("completion_tokens").asInt();
            String modelUsed = root.path("model").asText(model);

            log.debug(
                    "LiteLLM chat response: model={}, input_tokens={}, output_tokens={}",
                    modelUsed,
                    inputTokens,
                    outputTokens);

            return new LlmResponse(content, inputTokens, outputTokens, modelUsed);

        } catch (Exception e) {
            throw new RuntimeException(
                    "LiteLLM API call failed [" + provider.getBaseUrl() + "]: " + e.getMessage(),
                    e);
        }
    }
```

- [ ] **Step 5: Run tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=OpenAiCompatibleLlmClientTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "(Tests run|BUILD)" | cat`
Expected: 2 tests PASS.

- [ ] **Step 6: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-llm-orchestrator -q
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClient.java emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/OpenAiCompatibleLlmClientTest.java emcip-llm-orchestrator/pom.xml
git commit -m "feat(llm-orchestrator): add chat() method for multi-turn conversations"
```

---

### Task 2: OrchestratorController — add POST /api/chat endpoint

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerChatTest.java`

- [ ] **Step 1: Write the test**

Create `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerChatTest.java`:

```java
package io.emcip.llm.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.client.LlmResponse;
import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OrchestratorControllerChatTest {

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @InjectMocks private OrchestratorController controller;

    @Test
    void chat_success() {
        ModelConfig model = new ModelConfig();
        model.setModelName("qwen3-30b-a3b");
        when(orchestratorService.selectModelForTask("GENERAL")).thenReturn(Optional.of(model));
        when(llmClient.chat(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(new LlmResponse("analysis result", 100, 50, "qwen3-30b-a3b"));

        var request =
                new OrchestratorController.ChatRequest(
                        List.of(
                                new OrchestratorController.ChatMessage("system", "You are helpful"),
                                new OrchestratorController.ChatMessage("user", "Analyse this")),
                        "GENERAL");

        var response = controller.chat(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().content()).isEqualTo("analysis result");
        assertThat(response.getBody().model()).isEqualTo("qwen3-30b-a3b");
    }

    @Test
    void chat_noModel_returns503() {
        when(orchestratorService.selectModelForTask("GENERAL")).thenReturn(Optional.empty());

        var request =
                new OrchestratorController.ChatRequest(
                        List.of(new OrchestratorController.ChatMessage("user", "Hi")), null);

        var response = controller.chat(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().success()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=OrchestratorControllerChatTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -5 | cat`
Expected: Compilation error — `ChatRequest`, `ChatMessage`, `ChatResponse`, and `chat()` method don't exist.

- [ ] **Step 3: Add records and endpoint to OrchestratorController**

In `OrchestratorController.java`, add the records after the existing `AnalyseResponse` record (around line 58):

```java
    public record ChatMessage(String role, String content) {}

    public record ChatRequest(List<ChatMessage> messages, String taskType) {}

    public record ChatResponse(boolean success, String content, String model) {}
```

Add the endpoint after the existing `analyse()` method (before the closing `}` of the class):

```java
    @Operation(summary = "Multi-turn chat using the specified task model")
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest req) {
        String taskType = req.taskType() != null ? req.taskType() : "GENERAL";
        Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
        if (modelOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(
                            new ChatResponse(
                                    false, "No model configured for task: " + taskType, null));
        }
        ModelConfig model = modelOpt.get();
        try {
            List<Map<String, String>> messages =
                    req.messages().stream()
                            .map(m -> Map.of("role", m.role(), "content", m.content()))
                            .toList();
            LlmResponse response =
                    llmClient.chat(model.getModelName(), messages, 1024, 0.3);
            return ResponseEntity.ok(
                    new ChatResponse(true, response.content(), response.model()));
        } catch (Exception e) {
            log.error("Chat call failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ChatResponse(false, "LLM call failed: " + e.getMessage(), null));
        }
    }
```

- [ ] **Step 4: Run tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator -Dtest=OrchestratorControllerChatTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "(Tests run|BUILD)" | cat`
Expected: 2 tests PASS.

- [ ] **Step 5: Run all orchestrator tests to confirm no regressions**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-llm-orchestrator 2>&1 | tail -5 | cat`
Expected: All tests PASS.

- [ ] **Step 6: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-llm-orchestrator -q
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerChatTest.java
git commit -m "feat(llm-orchestrator): add POST /api/chat endpoint for multi-turn conversations"
```

---

### Task 3: admin-api — FlagController + FlagService chat endpoint

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/FlagControllerChatTest.java`

- [ ] **Step 1: Write the test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/FlagControllerChatTest.java`:

```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.service.FlagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@WebFluxTest(FlagController.class)
class FlagControllerChatTest {

    @Autowired private WebTestClient webClient;
    @MockitoBean private FlagService flagService;

    @Test
    @WithMockUser
    void chat_returnsOrchestratorResponse() {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("success", true);
        response.put("content", "Here is my analysis");
        response.put("model", "qwen3-30b-a3b");

        when(flagService.chat(anyString(), any(JsonNode.class))).thenReturn(Mono.just(response));

        webClient
                .post()
                .uri("/api/flags/test-id/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"messages\":[{\"role\":\"user\",\"content\":\"Analyse this\"}]}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(true)
                .jsonPath("$.content")
                .isEqualTo("Here is my analysis");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -Dtest=FlagControllerChatTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -10 | cat`
Expected: Compilation error — `chat()` method doesn't exist on FlagService.

- [ ] **Step 3: Add chat() to FlagService**

In `FlagService.java`, add this method after the existing `analyse()` method (after line 153):

```java
    public Mono<JsonNode> chat(String flagId, JsonNode body) {
        return policyEngineClient
                .getDecision(flagId)
                .flatMap(
                        flag -> {
                            String systemPrompt = buildChatSystemPrompt(flag);
                            JsonNode clientMessages = body.get("messages");

                            // Build full messages array: system prompt + client messages
                            tools.jackson.databind.node.ArrayNode messages =
                                    JsonNodeFactory.instance.arrayNode();
                            ObjectNode systemMsg = JsonNodeFactory.instance.objectNode();
                            systemMsg.put("role", "system");
                            systemMsg.put("content", systemPrompt);
                            messages.add(systemMsg);
                            if (clientMessages != null && clientMessages.isArray()) {
                                for (JsonNode msg : clientMessages) {
                                    messages.add(msg);
                                }
                            }

                            ObjectNode chatBody = JsonNodeFactory.instance.objectNode();
                            chatBody.set("messages", messages);
                            chatBody.put("taskType", "GENERAL");

                            return orchestratorWebClient
                                    .post()
                                    .uri("/api/chat")
                                    .bodyValue(chatBody)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        });
    }

    private String buildChatSystemPrompt(JsonNode flag) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "You are a moderation analyst for the EMCIP platform. You are assisting an"
                        + " operator investigating a flagged message.\n\n");
        sb.append("Context:\n");
        sb.append("- Intent: ").append(flag.path("originalIntent").asText("unknown")).append("\n");
        sb.append("- Decision: ").append(flag.path("decision").asText("unknown")).append("\n");
        sb.append("- Confidence: ")
                .append(String.format("%.1f%%", flag.path("confidence").asDouble(0) * 100))
                .append("\n");
        sb.append("- Reason: ").append(flag.path("reason").asText("none")).append("\n");
        JsonNode meta = flag.path("metadata");
        if (!meta.isMissingNode() && !meta.isNull() && meta.has("messageText")) {
            sb.append("- Message text: ").append(meta.path("messageText").asText()).append("\n");
        }
        sb.append(
                "\nHelp the operator understand this flag and research appropriate responses.");
        return sb.toString();
    }
```

- [ ] **Step 4: Add chat endpoint to FlagController**

In `FlagController.java`, add after the existing `analyse()` method (after line 108):

```java
    @Operation(summary = "Multi-turn AI research chat about a flag")
    @PostMapping("/{id}/chat")
    public Mono<ResponseEntity<JsonNode>> chat(
            @PathVariable String id, @RequestBody JsonNode body) {
        return flagService
                .chat(id, body)
                .map(ResponseEntity::ok)
                .onErrorReturn(
                        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(
                                        (JsonNode)
                                                JsonNodeFactory.instance
                                                        .objectNode()
                                                        .put("success", false)
                                                        .put("content", "Chat unavailable")));
    }
```

Add the missing import at the top of FlagController.java:

```java
import tools.jackson.databind.node.JsonNodeFactory;
```

- [ ] **Step 5: Run tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -Dtest=FlagControllerChatTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "(Tests run|BUILD)" | cat`
Expected: 1 test PASS.

- [ ] **Step 6: Run all admin-api tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api 2>&1 | tail -5 | cat`
Expected: All tests PASS.

- [ ] **Step 7: Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java emcip-admin-api/src/test/java/io/emcip/admin/api/controller/FlagControllerChatTest.java
git commit -m "feat(admin-api): add POST /flags/{id}/chat for multi-turn AI research"
```

---

### Task 4: admin-ui — API layer and chat UI

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/api/flags.js`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.module.css`

- [ ] **Step 1: Add chat() to flags API**

Replace the `analyse` function in `emcip-admin-ui/src/main/frontend/src/api/flags.js` with `chat`:

```js
export function flagsApi(request) {
  return {
    list: (page = 0, size = 50, decision = '', intent = '', from = null, to = null, minConfidence = null) => {
      const params = new URLSearchParams({ page, size })
      if (decision) params.set('decision', decision)
      if (intent) params.set('intent', intent)
      if (from) params.set('from', from)
      if (to) params.set('to', to)
      if (minConfidence != null) params.set('minConfidence', minConfidence)
      return request(`/api/flags?${params}`)
    },
    updateStatus: (id, status) =>
      request(`/api/flags/${encodeURIComponent(id)}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
      }),
    reply: (id, body) =>
      request(`/api/flags/${encodeURIComponent(id)}/reply`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    analyse: id =>
      request(`/api/flags/${encodeURIComponent(id)}/analyse`, { method: 'POST' }),
    chat: (id, messages) =>
      request(`/api/flags/${encodeURIComponent(id)}/chat`, {
        method: 'POST',
        body: JSON.stringify({ messages }),
      }),
  }
}
```

- [ ] **Step 2: Add CSS classes for chat UI**

Append to `emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.module.css`:

```css
/* AI Research chat */
.chatMessages {
  max-height: 300px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-2) 0;
}

.chatMessage {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.chatMessageUser {
  align-self: flex-end;
  max-width: 85%;
}

.chatMessageAssistant {
  align-self: flex-start;
  max-width: 85%;
}

.chatMessageRole {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.10em;
}

.chatMessageContent {
  font-family: var(--font-body);
  font-size: 13px;
  color: var(--fg-1);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--bg-sunken);
  border: 1px solid var(--border);
  padding: 10px 14px;
}

.chatMessageUser .chatMessageContent {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.chatMessageMeta {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.chatInputRow {
  display: flex;
  gap: var(--sp-2);
  align-items: flex-end;
}

.chatInput {
  flex: 1;
  min-height: 40px;
  max-height: 100px;
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  resize: vertical;
  box-sizing: border-box;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.chatInput:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

.chatThinking {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-3);
  padding: var(--sp-2) 0;
}
```

- [ ] **Step 3: Replace AI Analysis section with chat UI in Flags.jsx**

In `FlagDetailModal` in `Flags.jsx`, replace the state variables (lines 78–81):

Replace:
```js
  const [showAnalysis, setShowAnalysis] = useState(false)
  const [analysing, setAnalysing] = useState(false)
  const [analysisResult, setAnalysisResult] = useState(null)
  const [analysisCopied, setAnalysisCopied] = useState(false)
```

With:
```js
  const [showResearch, setShowResearch] = useState(false)
  const [chatMessages, setChatMessages] = useState([])
  const [chatInput, setChatInput] = useState('')
  const [chatLoading, setChatLoading] = useState(false)
  const [chatError, setChatError] = useState(null)
```

Replace the `handleAnalyse` and `copyAnalysis` functions (lines 135–154) with:

```js
  const buildFirstMessage = () => {
    const parts = [`Analyse this moderation flag:`]
    parts.push(`- Intent: ${flag.originalIntent || 'unknown'}`)
    parts.push(`- Decision: ${flag.decision || 'unknown'}`)
    parts.push(`- Confidence: ${flag.confidence != null ? (flag.confidence * 100).toFixed(1) + '%' : 'unknown'}`)
    parts.push(`- Reason: ${flag.reason || 'none'}`)
    if (meta.messageText) parts.push(`- Message: ${meta.messageText}`)
    parts.push('', 'Is the decision appropriate? Explain briefly and suggest any better action if relevant.')
    return parts.join('\n')
  }

  const sendChat = async (newMessages) => {
    setChatMessages(newMessages)
    setChatLoading(true)
    setChatError(null)
    try {
      const res = await api.chat(flag.id, newMessages)
      setChatMessages(prev => [...prev, { role: 'assistant', content: res.content, model: res.model }])
    } catch (e) {
      setChatError(e.message || 'Chat failed')
    } finally {
      setChatLoading(false)
    }
  }

  const handleAnalyse = () => {
    const userMsg = { role: 'user', content: buildFirstMessage() }
    sendChat([userMsg])
  }

  const handleChatSend = () => {
    if (!chatInput.trim()) return
    const userMsg = { role: 'user', content: chatInput.trim() }
    setChatInput('')
    sendChat([...chatMessages, userMsg])
  }

  const handleChatKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleChatSend()
    }
  }

  const copyMessage = (content) => {
    navigator.clipboard.writeText(content)
  }

  const clearChat = () => {
    setChatMessages([])
    setChatError(null)
    setChatInput('')
  }
```

Replace the AI Analysis section JSX (lines 285–313) with:

```jsx
      <div className={styles.replyHeader} onClick={() => setShowResearch(s => !s)}>
        <SectionLabel aside={showResearch ? '\u25BE' : '\u25B8'}>AI Research</SectionLabel>
      </div>

      {showResearch && (
        <div className={styles.replySection}>
          {chatMessages.length === 0 && (
            <div className={styles.replyActions}>
              <Button variant="secondary" onClick={handleAnalyse} disabled={chatLoading}>
                Analyse
              </Button>
            </div>
          )}

          {chatMessages.length > 0 && (
            <div className={styles.chatMessages}>
              {chatMessages.map((msg, i) => (
                <div
                  key={i}
                  className={`${styles.chatMessage} ${msg.role === 'user' ? styles.chatMessageUser : styles.chatMessageAssistant}`}
                >
                  <div className={styles.chatMessageMeta}>
                    <span className={styles.chatMessageRole}>{msg.role === 'user' ? 'You' : 'Assistant'}</span>
                    {msg.role === 'assistant' && msg.model && (
                      <span className={styles.analysisModel}>{msg.model}</span>
                    )}
                    {msg.role === 'assistant' && (
                      <button className={styles.copyAnalysisBtn} onClick={() => copyMessage(msg.content)}>
                        Copy
                      </button>
                    )}
                  </div>
                  <div className={styles.chatMessageContent}>{msg.content}</div>
                </div>
              ))}
              {chatLoading && <div className={styles.chatThinking}>Thinking{'\u2026'}</div>}
            </div>
          )}

          {chatError && <p role="alert" className={styles.alertBanner}>{chatError}</p>}

          {chatMessages.length > 0 && (
            <>
              <div className={styles.chatInputRow}>
                <textarea
                  className={styles.chatInput}
                  placeholder="Ask a follow-up question..."
                  value={chatInput}
                  onChange={e => setChatInput(e.target.value)}
                  onKeyDown={handleChatKeyDown}
                  disabled={chatLoading}
                  rows={2}
                />
                <Button onClick={handleChatSend} disabled={chatLoading || !chatInput.trim()}>
                  Send
                </Button>
              </div>
              <div className={styles.replyActions}>
                <Button variant="secondary" onClick={clearChat} disabled={chatLoading}>
                  Clear
                </Button>
              </div>
            </>
          )}
        </div>
      )}
```

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/api/flags.js emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.jsx emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.module.css
git commit -m "feat(admin-ui): replace AI Analysis with multi-turn AI Research chat"
```

---

### Task 5: Final verification + docs

**Files:**
- All modified files from Tasks 1-4
- `docs/superpowers/BACKLOG.md`

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

In `docs/superpowers/BACKLOG.md`, update the #23 entry in the open items table:

Replace:
```
| 23 | **Flag-detail: AI-research prompt interface (Phase 2)** | M | Phase 1 ✅ PR #89. Phase 2: chat-style UI backed by a configured LiteLLM model so the operator can research/draft a response with AI assistance before sending. |
```

With:
```
| 23 | **Flag-detail: AI-research prompt interface (Phase 2)** | M | ✅ Done. Multi-turn AI Research chat in Flag Detail modal. Spec: `docs/superpowers/specs/2026-06-15-ai-research-chat-design.md`. |
```

Add to §5 Completed:
```
| 23 | Flag-detail: AI Research chat (Phase 2) | ✅ 2026-06-15. Spec: `specs/2026-06-15-ai-research-chat-design.md` |
```

- [ ] **Step 5: Update architecture-guide.adoc**

In `documentation/architecture-guide.adoc`, update the admin-api section (around line 173) to mention the AI Research chat:

After the sentence about operator actions, add:
```
The admin-api also provides an *AI Research chat* — a multi-turn conversation endpoint (`POST /api/flags/{id}/chat`) that prepends the flag context as a system prompt and forwards the messages array to the LLM Orchestrator's `/api/chat` endpoint. This lets operators interactively investigate flagged messages before deciding on a response.
```

- [ ] **Step 6: Commit docs**

```bash
cd /home/ben/Development/ecip
git add docs/superpowers/BACKLOG.md documentation/architecture-guide.adoc
git commit -m "docs: update backlog and architecture guide for AI Research chat (#23)"
```
