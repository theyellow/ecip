# Knowledge Enrichment for LLM Responses (26.10) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before each LLM call in `llm-orchestrator`, query the knowledge-engine for relevant context and prepend it to the user prompt, with a configurable relevance threshold and a feature flag for safe rollout.

**Architecture:** `LlmCallService` delegates to a new `KnowledgeContextEnricherService`, which calls knowledge-engine via a new `KnowledgeEngineClient` (RestClient). Context is prepended inline to `userContent` before `OpenAiCompatibleLlmClient.call()` — no prompt template changes required. Enrichment is guarded by `knowledge.enrichment.enabled` (default `false`) and `knowledge.enrichment.relevance-threshold` (default `0.70`). TenantId is read from `TenantContext.getTenantId()` (thread-local already set by the tenant filter).

**Tech Stack:** Java 21, Spring Boot 4, `RestClient` (Spring 6), `@ConfigurationProperties`, JUnit 5 + Mockito

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/KnowledgeEnrichmentProperties.java` | `@ConfigurationProperties("knowledge.enrichment")` — enabled, threshold, maxResults, contextMaxChars |
| Create | `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/KnowledgeEngineClient.java` | RestClient wrapper — POST `/api/knowledge/search`, returns local DTOs |
| Create | `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/KnowledgeContextEnricherService.java` | Calls client, filters by threshold, formats context string |
| Modify | `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmCallService.java` | Inject enricher; call before LLM if enabled |
| Modify | `emcip-llm-orchestrator/src/main/resources/application.yml` | Add `knowledge.enrichment.*` and `knowledge.engine.base-url` |
| Create | `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/KnowledgeClientConfig.java` | `@Bean RestClient` for knowledge-engine |
| Create | `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/KnowledgeEngineClientTest.java` | Unit tests for client |
| Create | `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/KnowledgeContextEnricherServiceTest.java` | Unit tests for enricher |
| Modify | `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/LlmCallServiceTest.java` | Tests: enriched vs skipped path |

---

## Task 1: Config properties + application.yml

**Files:**
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/KnowledgeEnrichmentProperties.java`
- Modify: `emcip-llm-orchestrator/src/main/resources/application.yml`

- [ ] **Step 1: Add config to application.yml**

Open `emcip-llm-orchestrator/src/main/resources/application.yml`. Add at the end:

```yaml
knowledge:
  engine:
    base-url: ${KNOWLEDGE_ENGINE_URL:http://localhost:9088}
  enrichment:
    enabled: ${KNOWLEDGE_ENRICHMENT_ENABLED:false}
    relevance-threshold: ${KNOWLEDGE_RELEVANCE_THRESHOLD:0.70}
    max-results: ${KNOWLEDGE_MAX_RESULTS:5}
    context-max-chars: ${KNOWLEDGE_CONTEXT_MAX_CHARS:2000}
```

- [ ] **Step 2: Create `KnowledgeEnrichmentProperties`**

```java
package io.emcip.llm.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("knowledge.enrichment")
public record KnowledgeEnrichmentProperties(
        boolean enabled,
        double relevanceThreshold,
        int maxResults,
        int contextMaxChars) {

    public KnowledgeEnrichmentProperties {
        if (relevanceThreshold < 0.0 || relevanceThreshold > 1.0) {
            throw new IllegalArgumentException("relevanceThreshold must be between 0.0 and 1.0");
        }
    }
}
```

- [ ] **Step 3: Enable config properties scanning**

Open the llm-orchestrator main application class (find it with: `grep -r "@SpringBootApplication" emcip-llm-orchestrator/src/main/java --include="*.java" -l`).

Add `@EnableConfigurationProperties(KnowledgeEnrichmentProperties.class)` to the class. Import: `org.springframework.boot.context.properties.EnableConfigurationProperties`.

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator spotless:apply -q
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/KnowledgeEnrichmentProperties.java \
        emcip-llm-orchestrator/src/main/resources/application.yml \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/
git commit -m "feat(26.10): add KnowledgeEnrichmentProperties config"
```

---

## Task 2: KnowledgeEngineClient + KnowledgeClientConfig

**Files:**
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/KnowledgeEngineClient.java`
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/KnowledgeClientConfig.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/KnowledgeEngineClientTest.java`

The client calls `POST /api/knowledge/search` on knowledge-engine. We define local DTOs (records) in the client file — no dependency on the knowledge-engine module.

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.llm.orchestrator.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeEngineClientTest {

    private RestClient.RequestBodyUriSpec uriSpec;
    private RestClient.RequestBodySpec bodySpec;
    private RestClient.ResponseSpec responseSpec;
    private RestClient restClient;
    private KnowledgeEngineClient client;

    @BeforeEach
    void setUp() {
        uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        bodySpec = mock(RestClient.RequestBodySpec.class);
        responseSpec = mock(RestClient.ResponseSpec.class);
        restClient = mock(RestClient.class);
        client = new KnowledgeEngineClient(restClient, new ObjectMapper());

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/knowledge/search")).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any())).thenReturn(responseSpec);
    }

    @Test
    void search_returnsDocumentResults() {
        String json = """
            {
              "graphResults": [],
              "documentResults": [
                {
                  "document": {
                    "id": "00000000-0000-0000-0000-000000000001",
                    "content": "Fact about climate change.",
                    "sourceRef": "https://example.com/article",
                    "sourceType": "WEBPAGE"
                  },
                  "similarity": 0.85
                }
              ]
            }
            """;
        when(responseSpec.body(String.class)).thenReturn(json);

        KnowledgeEngineClient.SearchResponse response = client.search(
                "climate change", "HYBRID", UUID.randomUUID(), 5);

        assertThat(response.documentResults()).hasSize(1);
        assertThat(response.documentResults().get(0).similarity()).isEqualTo(0.85);
        assertThat(response.documentResults().get(0).document().content())
                .isEqualTo("Fact about climate change.");
    }

    @Test
    void search_returnsEmptyOnNullResponse() {
        when(responseSpec.body(String.class)).thenReturn(null);

        KnowledgeEngineClient.SearchResponse response = client.search(
                "query", "HYBRID", null, 5);

        assertThat(response.documentResults()).isEmpty();
        assertThat(response.graphResults()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator test -Dtest=KnowledgeEngineClientTest -q 2>&1 | tail -20
```

Expected: compilation error — `KnowledgeEngineClient` does not exist yet.

- [ ] **Step 3: Create `KnowledgeEngineClient`**

```java
package io.emcip.llm.orchestrator.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class KnowledgeEngineClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // ── Local DTOs ────────────────────────────────────────────────────────────

    public record KnowledgeDocument(
            UUID id,
            String content,
            String sourceRef,
            String sourceType) {}

    public record DocumentResult(
            KnowledgeDocument document,
            double similarity) {}

    public record GraphNodeResult(
            Map<String, Object> node,
            double score) {}

    public record SearchResponse(
            List<GraphNodeResult> graphResults,
            List<DocumentResult> documentResults) {

        public static SearchResponse empty() {
            return new SearchResponse(List.of(), List.of());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Calls POST /api/knowledge/search on the knowledge-engine.
     *
     * @param query       natural-language query
     * @param searchType  one of "VECTOR", "GRAPH", "HYBRID"
     * @param tenantId    optional tenant scope (null = cross-tenant)
     * @param limit       max results to return
     */
    public SearchResponse search(String query, String searchType, UUID tenantId, int limit) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", query);
        body.put("searchType", searchType);
        if (tenantId != null) {
            body.put("tenantId", tenantId.toString());
        }
        body.put("limit", limit);

        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            String responseJson = restClient.post()
                    .uri("/api/knowledge/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyJson)
                    .retrieve()
                    .body(String.class);

            if (responseJson == null) {
                return SearchResponse.empty();
            }
            return objectMapper.readValue(responseJson, SearchResponse.class);

        } catch (JsonProcessingException e) {
            log.warn("Knowledge search serialization error: {}", e.getMessage());
            return SearchResponse.empty();
        } catch (RestClientException e) {
            log.warn("Knowledge engine unreachable — skipping enrichment: {}", e.getMessage());
            return SearchResponse.empty();
        }
    }
}
```

- [ ] **Step 4: Create `KnowledgeClientConfig`**

```java
package io.emcip.llm.orchestrator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.llm.orchestrator.client.KnowledgeEngineClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class KnowledgeClientConfig {

    @Bean
    public KnowledgeEngineClient knowledgeEngineClient(
            @Value("${knowledge.engine.base-url}") String baseUrl,
            ObjectMapper objectMapper) {
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
        return new KnowledgeEngineClient(restClient, objectMapper);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator test -Dtest=KnowledgeEngineClientTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, 2 tests passing.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator spotless:apply -q
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/client/KnowledgeEngineClient.java \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/KnowledgeClientConfig.java \
        emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/client/KnowledgeEngineClientTest.java
git commit -m "feat(26.10): add KnowledgeEngineClient with RestClient + local DTOs"
```

---

## Task 3: KnowledgeContextEnricherService

**Files:**
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/KnowledgeContextEnricherService.java`
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/KnowledgeContextEnricherServiceTest.java`

This service queries the knowledge engine, filters by relevance threshold, then formats the surviving passages into a context block that gets prepended to the user message.

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.llm.orchestrator.service;

import io.emcip.llm.orchestrator.client.KnowledgeEngineClient;
import io.emcip.llm.orchestrator.client.KnowledgeEngineClient.DocumentResult;
import io.emcip.llm.orchestrator.client.KnowledgeEngineClient.KnowledgeDocument;
import io.emcip.llm.orchestrator.client.KnowledgeEngineClient.SearchResponse;
import io.emcip.llm.orchestrator.config.KnowledgeEnrichmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeContextEnricherServiceTest {

    private KnowledgeEngineClient client;
    private KnowledgeContextEnricherService enricher;

    @BeforeEach
    void setUp() {
        client = mock(KnowledgeEngineClient.class);
        KnowledgeEnrichmentProperties props =
                new KnowledgeEnrichmentProperties(true, 0.70, 5, 2000);
        enricher = new KnowledgeContextEnricherService(client, props);
    }

    @Test
    void enrich_returnsFormattedContext_whenResultsAboveThreshold() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeDocument doc = new KnowledgeDocument(
                UUID.randomUUID(), "Climate change increases sea levels.", "https://ipcc.ch", "WEBPAGE");
        DocumentResult result = new DocumentResult(doc, 0.85);
        when(client.search(anyString(), eq("HYBRID"), eq(tenantId), eq(5)))
                .thenReturn(new SearchResponse(List.of(), List.of(result)));

        String context = enricher.buildContext("what is climate change?", tenantId);

        assertThat(context).contains("Climate change increases sea levels.");
        assertThat(context).contains("https://ipcc.ch");
    }

    @Test
    void enrich_excludesResultsBelowThreshold() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeDocument doc = new KnowledgeDocument(
                UUID.randomUUID(), "Vaguely related text.", "https://example.com", "WEBPAGE");
        DocumentResult lowScore = new DocumentResult(doc, 0.50); // below 0.70 threshold
        when(client.search(anyString(), eq("HYBRID"), eq(tenantId), eq(5)))
                .thenReturn(new SearchResponse(List.of(), List.of(lowScore)));

        String context = enricher.buildContext("what is climate change?", tenantId);

        assertThat(context).isEmpty();
    }

    @Test
    void enrich_returnsEmpty_whenClientReturnsEmpty() {
        when(client.search(any(), any(), any(), anyInt()))
                .thenReturn(SearchResponse.empty());

        String context = enricher.buildContext("some query", UUID.randomUUID());

        assertThat(context).isEmpty();
    }

    @Test
    void enrich_truncatesContextToMaxChars() {
        UUID tenantId = UUID.randomUUID();
        // contextMaxChars is 2000; create a 3000-char document
        String longContent = "A".repeat(3000);
        KnowledgeDocument doc = new KnowledgeDocument(
                UUID.randomUUID(), longContent, "https://example.com", "WEBPAGE");
        DocumentResult result = new DocumentResult(doc, 0.90);
        when(client.search(anyString(), eq("HYBRID"), eq(tenantId), eq(5)))
                .thenReturn(new SearchResponse(List.of(), List.of(result)));

        String context = enricher.buildContext("long query", tenantId);

        // Total context block (header + content + footer) must not exceed contextMaxChars
        assertThat(context.length()).isLessThanOrEqualTo(2000);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator test -Dtest=KnowledgeContextEnricherServiceTest -q 2>&1 | tail -20
```

Expected: compilation error — `KnowledgeContextEnricherService` does not exist yet.

- [ ] **Step 3: Create `KnowledgeContextEnricherService`**

```java
package io.emcip.llm.orchestrator.service;

import io.emcip.llm.orchestrator.client.KnowledgeEngineClient;
import io.emcip.llm.orchestrator.client.KnowledgeEngineClient.DocumentResult;
import io.emcip.llm.orchestrator.config.KnowledgeEnrichmentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeContextEnricherService {

    private final KnowledgeEngineClient knowledgeEngineClient;
    private final KnowledgeEnrichmentProperties props;

    /**
     * Queries the knowledge engine and returns a formatted context string.
     * Returns an empty string if enrichment is disabled, no results meet
     * the relevance threshold, or the knowledge engine is unreachable.
     *
     * @param userQuery natural-language user query
     * @param tenantId  current tenant (null = cross-tenant)
     * @return formatted context block, or "" if nothing relevant found
     */
    public String buildContext(String userQuery, UUID tenantId) {
        KnowledgeEngineClient.SearchResponse response =
                knowledgeEngineClient.search(userQuery, "HYBRID", tenantId, props.maxResults());

        List<DocumentResult> relevant = response.documentResults().stream()
                .filter(r -> r.similarity() >= props.relevanceThreshold())
                .toList();

        if (relevant.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (DocumentResult result : relevant) {
            String content = result.document().content();
            String sourceRef = result.document().sourceRef();
            sb.append("[Source: ").append(sourceRef).append("]\n");
            sb.append(content).append("\n\n");
            if (sb.length() >= props.contextMaxChars()) {
                break;
            }
        }

        String raw = sb.toString().stripTrailing();
        if (raw.length() > props.contextMaxChars()) {
            raw = raw.substring(0, props.contextMaxChars());
        }
        return raw;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator test -Dtest=KnowledgeContextEnricherServiceTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, 4 tests passing.

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator spotless:apply -q
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/KnowledgeContextEnricherService.java \
        emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/KnowledgeContextEnricherServiceTest.java
git commit -m "feat(26.10): add KnowledgeContextEnricherService — filter by threshold, format context"
```

---

## Task 4: Wire enrichment into LlmCallService

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmCallService.java`
- Modify: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/LlmCallServiceTest.java`

Before calling `OpenAiCompatibleLlmClient`, check if enrichment is enabled and if so, prepend the knowledge context to `userContent`.

- [ ] **Step 1: Read LlmCallService to understand the exact method**

```bash
cat -n /home/ben/Development/ecip/emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmCallService.java
```

Identify the exact signature of `call(...)` / `callForTask(...)` and where `openAiClient.call(...)` / `openAiClient.chat(...)` is invoked. The edit below uses the shape discovered from the codebase exploration, but verify line numbers before editing.

- [ ] **Step 2: Write failing tests first**

Open `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/LlmCallServiceTest.java` and add two tests (add to existing class, don't replace existing tests):

```java
// Add these imports at the top of the existing test class:
// import io.emcip.llm.orchestrator.config.KnowledgeEnrichmentProperties;
// import io.emcip.llm.orchestrator.service.KnowledgeContextEnricherService;
// import io.emcip.common.tenant.TenantContext;

@Test
void callForTask_prependsKnowledgeContext_whenEnrichmentEnabled() {
    // Arrange
    KnowledgeEnrichmentProperties enabledProps =
            new KnowledgeEnrichmentProperties(true, 0.70, 5, 2000);
    KnowledgeContextEnricherService enricher = mock(KnowledgeContextEnricherService.class);
    when(enricher.buildContext(anyString(), any())).thenReturn("Relevant fact: X is true.");

    // Re-construct the service under test with enrichment enabled
    // (assumes constructor injection — adjust to match actual constructor)
    LlmCallService serviceWithEnrichment = new LlmCallService(
            llmOrchestratorService, openAiClient, costTrackingService, enricher, enabledProps);

    TenantContext.setTenantId("tenant-123");
    try {
        // Act
        serviceWithEnrichment.callForTask(
                "ANALYSE", "default", "What is X?", Map.of(), null, null);

        // Assert: the user content passed to the LLM client contains the context block
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(openAiClient).call(any(), any(), contentCaptor.capture(), anyInt(), anyDouble());
        assertThat(contentCaptor.getValue()).contains("Relevant fact: X is true.");
        assertThat(contentCaptor.getValue()).contains("What is X?");
    } finally {
        TenantContext.clear();
    }
}

@Test
void callForTask_skipsEnrichment_whenDisabled() {
    // Arrange
    KnowledgeEnrichmentProperties disabledProps =
            new KnowledgeEnrichmentProperties(false, 0.70, 5, 2000);
    KnowledgeContextEnricherService enricher = mock(KnowledgeContextEnricherService.class);

    LlmCallService serviceWithEnrichment = new LlmCallService(
            llmOrchestratorService, openAiClient, costTrackingService, enricher, disabledProps);

    // Act
    serviceWithEnrichment.callForTask(
            "ANALYSE", "default", "What is X?", Map.of(), null, null);

    // Assert: enricher never called
    verify(enricher, never()).buildContext(any(), any());
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator test -Dtest=LlmCallServiceTest -q 2>&1 | tail -20
```

Expected: compilation error or test failure — `LlmCallService` does not yet accept enricher.

- [ ] **Step 4: Modify LlmCallService**

Add `KnowledgeContextEnricherService` and `KnowledgeEnrichmentProperties` as constructor-injected fields (Lombok `@RequiredArgsConstructor` handles this automatically once you add the fields).

Add private method `buildEnrichedContent`:

```java
private String buildEnrichedContent(String userContent, UUID tenantId) {
    String context = knowledgeContextEnricherService.buildContext(userContent, tenantId);
    if (context.isBlank()) {
        return userContent;
    }
    return "Relevant context from the knowledge base:\n"
            + context
            + "\n\n---\n\n"
            + userContent;
}
```

Then in the call path, before invoking `openAiCompatibleLlmClient.call(...)`:

```java
// Replace:
//   openAiCompatibleLlmClient.call(model, systemPrompt, userContent, maxTokens, temperature)
// With:
UUID tenantId = TenantContext.getTenantId() != null
        ? UUID.fromString(TenantContext.getTenantId())
        : null;
String enrichedContent = knowledgeEnrichmentProperties.enabled()
        ? buildEnrichedContent(userContent, tenantId)
        : userContent;
openAiCompatibleLlmClient.call(model, systemPrompt, enrichedContent, maxTokens, temperature)
```

Import: `import io.emcip.common.tenant.TenantContext;`

> **Note:** Read the actual `LlmCallService` source before editing. If it uses a `chat(...)` multi-turn path instead of a single `call(...)`, apply the same `enrichedContent` substitution there too, replacing only the first (user) message content. The variable naming here matches the exploration findings; adjust if actual names differ.

- [ ] **Step 5: Run all llm-orchestrator tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator test -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`, all tests passing including the two new ones.

- [ ] **Step 6: Apply Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator spotless:apply -q
git add emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/LlmCallService.java \
        emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/LlmCallServiceTest.java
git commit -m "feat(26.10): wire KnowledgeContextEnricherService into LlmCallService"
```

---

## Task 5: Full module build + Spotless clean pass

- [ ] **Step 1: Full build**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator clean verify -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Spotless check across all touched modules**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-llm-orchestrator spotless:check -q 2>&1 | tail -10
```

Expected: `0 were changed to be clean`.

If any files changed:
```bash
mvn -pl emcip-llm-orchestrator spotless:apply -q
git add emcip-llm-orchestrator/
git commit -m "style(26.10): apply spotless"
```

---

## Self-Review Checklist

### Spec coverage

| Requirement | Task |
|-------------|------|
| Enrich LLM prompt with knowledge context before call | Task 4 — `buildEnrichedContent` in `LlmCallService` |
| Configurable relevance threshold | Task 1 — `relevanceThreshold` in `KnowledgeEnrichmentProperties` |
| Feature flag for safe rollout | Task 1 — `enabled` in `KnowledgeEnrichmentProperties` |
| Call knowledge-engine search endpoint | Task 2 — `KnowledgeEngineClient.search()` |
| Filter by threshold | Task 3 — `KnowledgeContextEnricherService.buildContext()` |
| Context size bound | Task 3 — `contextMaxChars` truncation |
| Graceful degradation when knowledge-engine unreachable | Task 2 — catch `RestClientException` → return empty |

### No placeholders
All code blocks are complete. No TBD or TODO.

### Type consistency
- `KnowledgeEngineClient.SearchResponse.DocumentResult.similarity()` — used consistently in Task 3
- `KnowledgeContextEnricherService.buildContext(String, UUID)` — matches Task 4 call site
- `KnowledgeEnrichmentProperties` record fields (`enabled`, `relevanceThreshold`, `maxResults`, `contextMaxChars`) — consistent across Task 1, 3, 4
