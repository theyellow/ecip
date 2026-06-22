# Deep Research Agent — Plan B: Web Search + Report Generation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the Deep Research Agent backend (built in Plan A) with web search via SearXNG/Brave and LLM-generated structured research reports stored as knowledge artifacts — covering US-27.3 and US-27.5.

**Architecture:** A `SearXngConnector` registers into the existing `EnrichmentConnectorRegistry`; `WebSearchService` orchestrates SearXNG → Brave fallback and is wired into `ResearchAgentService.runLoop()` as an optional second evidence source per sub-question. After the execution loop completes, `ResearchReportService` synthesises all collected evidence into a Markdown report via LLM and stores it in `ke_research_reports`. `ResearchController` gains two report sub-resource endpoints; the admin-api proxy picks them up.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, Liquibase, Jackson 3 (`tools.jackson.*`), Lombok, JUnit 5 + Mockito + AssertJ. Existing: `EnrichmentConnectorRegistry`, `KnowledgeConnector`, `EnrichmentRequest`/`ConnectorContext`, `LlmOrchestratorClient`, `ResearchSession`/`ResearchEvidence` (Plan A).

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/connector/impl/SearXngConnector.java` | KnowledgeConnector — GET SearXNG `/search?format=json` |
| Create | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/connector/impl/SearXngConnectorTest.java` | Unit tests |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/WebSearchProperties.java` | `@ConfigurationProperties("web.search")` |
| Modify | `emcip-knowledge-engine/src/main/resources/application.yml` | Add `web.search.*` env vars |
| Modify | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/KnowledgeEngineApplication.java` | Add `@EnableConfigurationProperties(WebSearchProperties.class)` |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/WebSearchService.java` | Orchestrates SearXNG → Brave fallback, looks up VendorApiKey |
| Create | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/WebSearchServiceTest.java` | Unit tests |
| Modify | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchRequest.java` | Add `webSearchEnabled`, `reportTemplate` fields |
| Modify | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchAgentService.java` | runLoop: call WebSearchService per sub-question; startResearch: auto-generate report |
| Modify | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchAgentServiceTest.java` | Update ResearchRequest calls; add web search test |
| Modify | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java` | Update ResearchRequest calls |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ReportTemplate.java` | Enum — TOPIC, PERSON, FACT_CHECK |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchReport.java` | JPA entity — `ke_research_reports` |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResearchReportRepository.java` | Spring Data JPA |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchReportDto.java` | Response DTO |
| Create | `emcip-knowledge-engine/src/main/resources/db/changelog/changes/017-research-reports.xml` | `ke_research_reports` table |
| Modify | `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml` | Include migration 017 |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchReportService.java` | LLM synthesis → ResearchReport storage |
| Create | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchReportServiceTest.java` | Unit tests |
| Modify | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchSessionDto.java` | Add `reportId` field |
| Modify | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResearchController.java` | Add report sub-resource endpoints; inject ResearchReportRepository |
| Modify | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java` | Tests for report endpoints |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResearchProxyController.java` | Add GET /{id}/report and GET /{id}/report/markdown proxy endpoints |
| Modify | `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/ResearchProxyControllerTest.java` | Tests for new proxy endpoints |

---

## Task 1: SearXngConnector

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/connector/impl/SearXngConnector.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/connector/impl/SearXngConnectorTest.java`

**Before coding:** Read `BraveConnector.java` to confirm:
- The `@Qualifier("connectorRestClient")` name
- How the RestClient is used (it appears to be a base client; the actual URL is appended per-call)

SearXNG JSON response format:
```json
{
  "results": [
    {"url": "https://example.com", "title": "Example", "content": "Snippet text", "engine": "google", "score": 0.85}
  ]
}
```

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.knowledge.engine.connector.impl;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.TriggerMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearXngConnectorTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec uriSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @Test
    void fetch_parsesJsonResults_intoEnrichmentResults() {
        String json = """
                {
                  "results": [
                    {"url": "https://example.com", "title": "Example", "content": "Some snippet", "engine": "google", "score": 0.9}
                  ]
                }
                """;

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(json);

        var connector = new SearXngConnector(restClient, "http://searxng.local");
        var request = new EnrichmentRequest(TriggerMode.MANUAL, "test query", null, Map.of());
        var ctx = new ConnectorContext(null, UUID.randomUUID(), Instant.EPOCH);

        List<EnrichmentResult> results = connector.fetch(request, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Example");
        assertThat(results.get(0).url()).isEqualTo("https://example.com");
        assertThat(results.get(0).content()).isEqualTo("Some snippet");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("searxng");
    }

    @Test
    void fetch_returnsEmpty_whenBaseUrlIsBlank() {
        var connector = new SearXngConnector(restClient, "");
        var request = new EnrichmentRequest(TriggerMode.MANUAL, "test query", null, Map.of());
        var ctx = new ConnectorContext(null, UUID.randomUUID(), Instant.EPOCH);

        List<EnrichmentResult> results = connector.fetch(request, ctx);

        assertThat(results).isEmpty();
    }

    @Test
    void fetch_returnsEmpty_whenResponseIsNull() {
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(null);

        var connector = new SearXngConnector(restClient, "http://searxng.local");
        var request = new EnrichmentRequest(TriggerMode.MANUAL, "test query", null, Map.of());
        var ctx = new ConnectorContext(null, UUID.randomUUID(), Instant.EPOCH);

        List<EnrichmentResult> results = connector.fetch(request, ctx);

        assertThat(results).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=SearXngConnectorTest -q 2>&1 | tail -10
```

Expected: compilation error — `SearXngConnector` does not exist.

- [ ] **Step 3: Create `SearXngConnector`**

```java
package io.emcip.knowledge.engine.connector.impl;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SearXngConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SearXngConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${web.search.searxng.base-url:}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "searxng";
    }

    @Override
    public String displayName() {
        return "SearXNG (self-hosted)";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.debug("SearXNG base URL not configured, skipping");
            return List.of();
        }

        String query = request.query();
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            String json = restClient
                    .get()
                    .uri(baseUrl + "/search?q={q}&format=json", query)
                    .retrieve()
                    .body(String.class);

            if (json == null) return List.of();

            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.path("results");
            if (results.isMissingNode() || !results.isArray()) return List.of();

            List<EnrichmentResult> output = new ArrayList<>();
            for (JsonNode r : results) {
                String url = r.path("url").asText("");
                output.add(new EnrichmentResult(
                        url,
                        r.path("title").asText(""),
                        r.path("content").asText(null),
                        url,
                        "searxng",
                        null,
                        Map.of("engine", r.path("engine").asText(""))));
            }
            log.debug("SearXNG returned {} results for query '{}'", output.size(), query);
            return output;
        } catch (Exception e) {
            log.warn("SearXNG search failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }
}
```

> **Note:** `tools.jackson.databind.ObjectMapper` and `tools.jackson.databind.JsonNode` — Spring Boot 4 uses Jackson 3. Match the import used in other files that use ObjectMapper (e.g., `ResearchStrategyService`).

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=SearXngConnectorTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 3 tests passing.

- [ ] **Step 5: Run full module tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/connector/impl/SearXngConnector.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/connector/impl/SearXngConnectorTest.java
git commit -m "feat(27b): add SearXngConnector — self-hosted web search"
```

---

## Task 2: WebSearchProperties + WebSearchService

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/WebSearchProperties.java`
- Modify: `emcip-knowledge-engine/src/main/resources/application.yml`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/KnowledgeEngineApplication.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/WebSearchService.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/WebSearchServiceTest.java`

`WebSearchService` tries SearXNG first (if configured), falls back to Brave using a tenant-specific (or global) API key from `VendorApiKey`.

- [ ] **Step 1: Create `WebSearchProperties`**

```java
package io.emcip.knowledge.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("web.search")
public record WebSearchProperties(boolean enabled, SearXngConfig searxng) {

    public record SearXngConfig(String baseUrl) {
        public SearXngConfig {
            if (baseUrl == null) baseUrl = "";
        }
    }

    public WebSearchProperties {
        if (searxng == null) searxng = new SearXngConfig("");
    }
}
```

- [ ] **Step 2: Add `@EnableConfigurationProperties` to the application class**

Read `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/KnowledgeEngineApplication.java`. Add the annotation:

```java
@EnableConfigurationProperties(WebSearchProperties.class)
```

The import is `org.springframework.boot.context.properties.EnableConfigurationProperties`.

- [ ] **Step 3: Add `web.search.*` to `application.yml`**

Open `emcip-knowledge-engine/src/main/resources/application.yml`. Find a good location (after the existing `knowledge:` block) and add:

```yaml
web:
  search:
    enabled: ${WEB_SEARCH_ENABLED:false}
    searxng:
      base-url: ${SEARXNG_BASE_URL:}
```

- [ ] **Step 4: Write the failing test**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.config.WebSearchProperties;
import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.EnrichmentConnectorRegistry;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import io.emcip.knowledge.engine.entity.VendorApiKey;
import io.emcip.knowledge.engine.repository.VendorApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSearchServiceTest {

    @Mock private EnrichmentConnectorRegistry registry;
    @Mock private VendorApiKeyRepository vendorApiKeyRepository;
    @Mock private KnowledgeConnector searxngConnector;
    @Mock private KnowledgeConnector braveConnector;

    private WebSearchService serviceWithSearXng;
    private WebSearchService serviceWithBraveOnly;
    private WebSearchService serviceDisabled;

    @BeforeEach
    void setUp() {
        var propsWithSearXng = new WebSearchProperties(true,
                new WebSearchProperties.SearXngConfig("http://searxng.local"));
        var propsWithBraveOnly = new WebSearchProperties(true,
                new WebSearchProperties.SearXngConfig(""));
        var propsDisabled = new WebSearchProperties(false,
                new WebSearchProperties.SearXngConfig(""));

        serviceWithSearXng = new WebSearchService(propsWithSearXng, registry, vendorApiKeyRepository);
        serviceWithBraveOnly = new WebSearchService(propsWithBraveOnly, registry, vendorApiKeyRepository);
        serviceDisabled = new WebSearchService(propsDisabled, registry, vendorApiKeyRepository);
    }

    @Test
    void search_returnsEmpty_whenDisabled() {
        List<EnrichmentResult> results = serviceDisabled.search("AI ethics", UUID.randomUUID());
        assertThat(results).isEmpty();
    }

    @Test
    void search_usesSearXng_whenConfigured() {
        UUID tenantId = UUID.randomUUID();
        EnrichmentResult result = new EnrichmentResult(
                "https://example.com", "AI Ethics", "Content", "https://example.com",
                "searxng", null, Map.of());
        when(registry.find("searxng")).thenReturn(Optional.of(searxngConnector));
        when(searxngConnector.fetch(any(EnrichmentRequest.class), any(ConnectorContext.class)))
                .thenReturn(List.of(result));

        List<EnrichmentResult> results = serviceWithSearXng.search("AI ethics", tenantId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("AI Ethics");
    }

    @Test
    void search_fallsBackToBrave_whenSearXngNotConfigured() {
        UUID tenantId = UUID.randomUUID();
        VendorApiKey braveKey = new VendorApiKey();
        braveKey.setApiKey("brave-key-123");
        braveKey.setEnabled(true);

        EnrichmentResult result = new EnrichmentResult(
                "https://brave.com/r/1", "Brave Result", "Brave content", "https://brave.com/r/1",
                "brave", null, Map.of());

        when(registry.find("brave")).thenReturn(Optional.of(braveConnector));
        when(vendorApiKeyRepository.findByVendorIdAndTenantId("brave", tenantId))
                .thenReturn(Optional.of(braveKey));
        when(braveConnector.fetch(any(EnrichmentRequest.class), any(ConnectorContext.class)))
                .thenReturn(List.of(result));

        List<EnrichmentResult> results = serviceWithBraveOnly.search("AI ethics", tenantId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).sourceVendorId()).isEqualTo("brave");
    }

    @Test
    void search_returnsEmpty_whenNoBraveKeyFound() {
        UUID tenantId = UUID.randomUUID();
        when(registry.find("brave")).thenReturn(Optional.of(braveConnector));
        when(vendorApiKeyRepository.findByVendorIdAndTenantId("brave", tenantId))
                .thenReturn(Optional.empty());
        when(vendorApiKeyRepository.findByVendorIdAndTenantIdIsNull("brave"))
                .thenReturn(Optional.empty());

        List<EnrichmentResult> results = serviceWithBraveOnly.search("AI ethics", tenantId);

        assertThat(results).isEmpty();
    }
}
```

- [ ] **Step 5: Run the test to confirm it fails**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=WebSearchServiceTest -q 2>&1 | tail -10
```

Expected: compilation error — `WebSearchService` does not exist.

- [ ] **Step 6: Create `WebSearchService`**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.config.WebSearchProperties;
import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.EnrichmentConnectorRegistry;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.repository.VendorApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchService {

    private final WebSearchProperties properties;
    private final EnrichmentConnectorRegistry registry;
    private final VendorApiKeyRepository vendorApiKeyRepository;

    /**
     * Searches the web for the given query.
     * Tries SearXNG first if configured; falls back to Brave using a stored API key.
     * Returns an empty list if web search is disabled or no connector is available.
     */
    public List<EnrichmentResult> search(String query, UUID tenantId) {
        if (!properties.enabled()) {
            log.debug("Web search is disabled");
            return List.of();
        }

        // Try SearXNG first when a base URL is configured
        if (!properties.searxng().baseUrl().isBlank()) {
            var searxng = registry.find("searxng");
            if (searxng.isPresent()) {
                try {
                    var request = new EnrichmentRequest(TriggerMode.MANUAL, query, null, Map.of());
                    var ctx = new ConnectorContext(null, tenantId, Instant.EPOCH);
                    List<EnrichmentResult> results = searxng.get().fetch(request, ctx);
                    if (!results.isEmpty()) {
                        return results;
                    }
                    log.debug("SearXNG returned no results, falling back to Brave");
                } catch (Exception e) {
                    log.warn("SearXNG search failed for '{}', falling back to Brave: {}", query, e.getMessage());
                }
            }
        }

        return searchWithBrave(query, tenantId);
    }

    private List<EnrichmentResult> searchWithBrave(String query, UUID tenantId) {
        var brave = registry.find("brave");
        if (brave.isEmpty()) {
            log.debug("Brave connector not available");
            return List.of();
        }

        String apiKey = vendorApiKeyRepository.findByVendorIdAndTenantId("brave", tenantId)
                .or(() -> vendorApiKeyRepository.findByVendorIdAndTenantIdIsNull("brave"))
                .filter(k -> k.isEnabled())
                .map(k -> k.getApiKey())
                .orElse(null);

        if (apiKey == null) {
            log.debug("No enabled Brave API key for tenant {}", tenantId);
            return List.of();
        }

        var request = new EnrichmentRequest(TriggerMode.MANUAL, query, null, Map.of());
        var ctx = new ConnectorContext(apiKey, tenantId, Instant.EPOCH);
        return brave.get().fetch(request, ctx);
    }
}
```

- [ ] **Step 7: Run tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=WebSearchServiceTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 4 tests passing.

- [ ] **Step 8: Run full module tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

- [ ] **Step 9: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/WebSearchProperties.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/KnowledgeEngineApplication.java \
        emcip-knowledge-engine/src/main/resources/application.yml \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/WebSearchService.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/WebSearchServiceTest.java
git commit -m "feat(27b): add WebSearchProperties and WebSearchService — SearXNG with Brave fallback"
```

---

## Task 3: Add web search to ResearchRequest and ResearchAgentService

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchRequest.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchAgentService.java`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchAgentServiceTest.java`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java`

`ResearchRequest` gains two optional fields. All existing tests that call `new ResearchRequest(...)` must be updated to pass 7 arguments. `ResearchAgentService.runLoop()` gains a `webSearchEnabled` parameter; if true, web search results are also collected as evidence after the KB search.

Web search evidence uses `sourceType = "WEB_SEARCH"` and `sourceRef = URL`.

- [ ] **Step 1: Update `ResearchRequest`**

Replace the entire file:

```java
package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.ReportTemplate;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record ResearchRequest(
        @NotBlank String question,
        UUID tenantId,
        @Min(0) @Max(50) int maxIterations,
        @Min(0) @Max(100) int maxLlmCalls,
        @DecimalMin("0.0") double costLimitUsd,
        boolean webSearchEnabled,
        ReportTemplate reportTemplate) {

    public ResearchRequest {
        if (maxIterations == 0) maxIterations = 10;
        if (maxLlmCalls == 0) maxLlmCalls = 20;
        if (costLimitUsd == 0.0) costLimitUsd = 1.00;
        if (reportTemplate == null) reportTemplate = ReportTemplate.TOPIC;
    }
}
```

> **Note:** `ReportTemplate` is defined in Task 4. This file will NOT compile until Task 4 is complete. That's fine — keep going; compilation is checked after Task 4.

- [ ] **Step 2: Update `ResearchAgentService` to add web search to `runLoop`**

Read the current `ResearchAgentService.java` first. Then make these changes:

1. Add `WebSearchService webSearchService` as a `final` field (constructor-injected via `@RequiredArgsConstructor`).

2. Change `runLoop(ResearchSession session)` → `runLoop(ResearchSession session, boolean webSearchEnabled)`.

3. Call sites: update `startResearch()` to pass `request.webSearchEnabled()`, and `resumeSession()` to pass `false` (safe default — web search cannot be re-enabled without a new request).

4. After `collectEvidence(session, subQ, response, iteration)` in the loop body, add web search:

```java
// Web search: additional evidence from the open web
if (webSearchEnabled && session.isWithinLimits()) {
    List<io.emcip.knowledge.engine.connector.EnrichmentResult> webResults =
            webSearchService.search(subQ.subQuestion(), session.getTenantId());
    collectWebEvidence(session, subQ, webResults, iteration);
}
```

5. Add the `collectWebEvidence` private method after `collectEvidence`:

```java
private void collectWebEvidence(
        ResearchSession session,
        ResearchStrategyService.SubQuestion subQ,
        List<io.emcip.knowledge.engine.connector.EnrichmentResult> webResults,
        int iteration) {

    for (io.emcip.knowledge.engine.connector.EnrichmentResult r : webResults) {
        if (r.content() == null || r.content().isBlank()) continue;

        ResearchEvidence evidence = new ResearchEvidence();
        evidence.setSession(session);
        evidence.setSubQuestion(subQ.subQuestion());
        evidence.setQueryStrategy(subQ.strategy());
        evidence.setFinding(r.title() + ": " + r.content());
        evidence.setSourceType("WEB_SEARCH");
        evidence.setSourceRef(r.url());
        evidence.setConfidenceScore(0.70); // web results: moderate default confidence
        evidence.setIteration(iteration);
        evidenceRepository.save(evidence);
    }
}
```

The full updated `runLoop` signature (show the method header only; keep existing body, add the web search block after `collectEvidence`):

```java
private void runLoop(ResearchSession session, boolean webSearchEnabled) {
    List<ResearchStrategyService.SubQuestion> subQuestions =
            strategyService.decompose(session.getQuestion());
    session.incrementLlmCalls(1);

    int iteration = 0;
    for (ResearchStrategyService.SubQuestion subQ : subQuestions) {
        if (!session.isWithinLimits()) {
            log.info(
                    "Session {} reached limits after {} iterations",
                    session.getId(),
                    session.getIterationsUsed());
            break;
        }

        SearchRequest searchRequest =
                new SearchRequest(
                        subQ.subQuestion(),
                        SearchRequest.SearchType.HYBRID,
                        session.getTenantId(),
                        null,
                        null,
                        10);

        SearchResponse response = queryService.search(searchRequest);

        collectEvidence(session, subQ, response, iteration);

        // Web search: additional evidence from the open web
        if (webSearchEnabled && session.isWithinLimits()) {
            List<io.emcip.knowledge.engine.connector.EnrichmentResult> webResults =
                    webSearchService.search(subQ.subQuestion(), session.getTenantId());
            collectWebEvidence(session, subQ, webResults, iteration);
        }

        session.incrementIterations(1);
        session.setCostUsedUsd(session.getCostUsedUsd() + COST_PER_ITERATION_USD);
        iteration++;

        sessionRepository.save(session);
    }
}
```

Updated `startResearch` call site (just the line that changed):
```java
runLoop(session, request.webSearchEnabled());
```

Updated `resumeSession` call site (just the line that changed):
```java
runLoop(session, false); // webSearchEnabled defaults to false on resume
```

- [ ] **Step 3: Update `ResearchAgentServiceTest` — fix existing ResearchRequest calls**

Read `ResearchAgentServiceTest.java`. Every call like:
```java
new ResearchRequest("...", tenantId, 10, 20, 1.00)
```
must become:
```java
new ResearchRequest("...", tenantId, 10, 20, 1.00, false, ReportTemplate.TOPIC)
```

Also add `@Mock WebSearchService webSearchService` and include it in `setUp()`:
```java
service = new ResearchAgentService(
        sessionRepository, evidenceRepository,
        strategyService, queryService, eventPublisher, webSearchService);
```

Add one new test for web search evidence collection:

```java
@Test
void startResearch_collectsWebEvidence_whenWebSearchEnabled() {
    UUID tenantId = UUID.randomUUID();
    ResearchRequest request = new ResearchRequest(
            "AI ethics in social media", tenantId, 10, 20, 1.00, true, ReportTemplate.TOPIC);

    ResearchStrategyService.SubQuestion subQ = new ResearchStrategyService.SubQuestion(
            "What are AI ethics concerns?", QueryStrategy.TOPIC_EXPLORATION);
    when(strategyService.decompose(anyString())).thenReturn(List.of(subQ));
    when(queryService.search(any())).thenReturn(new SearchResponse(List.of(), List.of()));

    io.emcip.knowledge.engine.connector.EnrichmentResult webResult =
            new io.emcip.knowledge.engine.connector.EnrichmentResult(
                    "https://example.com/ai",
                    "AI Ethics Overview",
                    "A discussion of AI ethics principles",
                    "https://example.com/ai",
                    "searxng",
                    null,
                    java.util.Map.of());
    when(webSearchService.search(anyString(), any(UUID.class))).thenReturn(List.of(webResult));

    when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(evidenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ResearchSession result = service.startResearch(request);

    assertThat(result.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
    ArgumentCaptor<ResearchEvidence> captor = ArgumentCaptor.forClass(ResearchEvidence.class);
    verify(evidenceRepository, atLeast(1)).save(captor.capture());

    boolean hasWebEvidence = captor.getAllValues().stream()
            .anyMatch(e -> "WEB_SEARCH".equals(e.getSourceType()));
    assertThat(hasWebEvidence).isTrue();
}
```

- [ ] **Step 4: Update `ResearchControllerTest` — fix existing ResearchRequest calls**

Read `ResearchControllerTest.java`. Update all `new ResearchRequest(...)` calls the same way:
```java
new ResearchRequest("Test question", tenantId, 10, 20, 1.00, false, ReportTemplate.TOPIC)
```

Add import for `ReportTemplate` at the top.

- [ ] **Step 5: Compile check (will fail until Task 4 creates ReportTemplate)**

If you are doing tasks sequentially, skip this until after Task 4. If you want an early check on everything except the missing enum, proceed to Task 4 first, then come back.

Actually: **do Tasks 4 and then run compile after Task 4.** The compile check is in Task 4, Step 5.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchRequest.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchAgentService.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchAgentServiceTest.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java
git commit -m "feat(27b): add webSearchEnabled + web evidence collection to ResearchAgentService"
```

---

## Task 4: ReportTemplate enum, ResearchReport entity, migration, repository, DTO

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ReportTemplate.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchReport.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResearchReportRepository.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchReportDto.java`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/017-research-reports.xml`
- Modify: `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Create `ReportTemplate` enum**

```java
package io.emcip.knowledge.engine.entity;

public enum ReportTemplate {
    /** General topic / community analysis report */
    TOPIC,
    /** Individual person analysis report */
    PERSON,
    /** Claim verification / fact-check report */
    FACT_CHECK
}
```

- [ ] **Step 2: Create `ResearchReport` entity**

```java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ke_research_reports")
@Getter
@Setter
@NoArgsConstructor
public class ResearchReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ResearchSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportTemplate template;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

- [ ] **Step 3: Create `ResearchReportRepository`**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ResearchReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ResearchReportRepository extends JpaRepository<ResearchReport, UUID> {

    Optional<ResearchReport> findBySessionId(UUID sessionId);
}
```

- [ ] **Step 4: Create `ResearchReportDto`**

```java
package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.ReportTemplate;
import io.emcip.knowledge.engine.entity.ResearchReport;

import java.time.Instant;
import java.util.UUID;

public record ResearchReportDto(
        UUID id,
        UUID tenantId,
        UUID sessionId,
        ReportTemplate template,
        String title,
        String content,
        int version,
        Instant createdAt) {

    public static ResearchReportDto from(ResearchReport r) {
        return new ResearchReportDto(
                r.getId(),
                r.getTenantId(),
                r.getSession().getId(),
                r.getTemplate(),
                r.getTitle(),
                r.getContent(),
                r.getVersion(),
                r.getCreatedAt());
    }
}
```

- [ ] **Step 5: Create migration `017-research-reports.xml`**

List the `changes/` directory first to confirm 016 is the last file:
```bash
ls /home/ben/Development/ecip/emcip-knowledge-engine/src/main/resources/db/changelog/changes/
```

Create `changes/017-research-reports.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-17" author="knowledge-engine">
        <createTable tableName="ke_research_reports"
                     remarks="LLM-generated research reports linked to a research session">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID">
                <constraints nullable="false"/>
            </column>
            <column name="session_id" type="UUID">
                <constraints nullable="false"
                             foreignKeyName="fk_ke_report_session"
                             references="ke_research_sessions(id)"/>
            </column>
            <column name="template" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="title" type="VARCHAR(500)">
                <constraints nullable="false"/>
            </column>
            <column name="content" type="TEXT">
                <constraints nullable="false"/>
            </column>
            <column name="version" type="INTEGER" defaultValueNumeric="1">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP WITH TIME ZONE">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_ke_research_reports_session_id"
                     tableName="ke_research_reports">
            <column name="session_id"/>
        </createIndex>

        <createIndex indexName="idx_ke_research_reports_tenant_id"
                     tableName="ke_research_reports">
            <column name="tenant_id"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 6: Add migration to master changelog**

Open `db.changelog-master.xml`. After the `016-research-evidence.xml` include, add:

```xml
    <include file="classpath:db/changelog/changes/017-research-reports.xml"/>
```

- [ ] **Step 7: Compile check (resolves the ReportTemplate gap from Task 3)**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine compile -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 8: Run full tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

Expected: BUILD SUCCESS, no failures. The new test in ResearchAgentServiceTest for web search should now also pass.

- [ ] **Step 9: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ReportTemplate.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchReport.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResearchReportRepository.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchReportDto.java \
        emcip-knowledge-engine/src/main/resources/db/changelog/changes/017-research-reports.xml \
        emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml
git commit -m "feat(27b): add ReportTemplate enum, ResearchReport entity, repository, DTO, migration 017"
```

---

## Task 5: ResearchReportService — LLM synthesis

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchReportService.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchReportServiceTest.java`

`ResearchReportService` takes a completed `ResearchSession` + its evidence list + a template, builds a structured prompt, calls `LlmOrchestratorClient.analyse(prompt, "REPORT")`, and stores the result as a `ResearchReport`.

**Read before implementing:**
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java` — verify `analyse(String prompt, String taskType)` method signature (added in Plan A).

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.*;
import io.emcip.knowledge.engine.repository.ResearchReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

@ExtendWith(MockitoExtension.class)
class ResearchReportServiceTest {

    @Mock private LlmOrchestratorClient llmClient;
    @Mock private ResearchReportRepository reportRepository;

    private ResearchReportService service;

    @BeforeEach
    void setUp() {
        service = new ResearchReportService(llmClient, reportRepository);
    }

    private ResearchSession buildSession(String question) {
        ResearchSession s = new ResearchSession();
        s.setTenantId(UUID.randomUUID());
        s.setQuestion(question);
        s.setStatus(ResearchStatus.COMPLETED);
        return s;
    }

    private ResearchEvidence buildEvidence(ResearchSession session, String subQ, String finding, String sourceRef) {
        ResearchEvidence e = new ResearchEvidence();
        e.setSession(session);
        e.setSubQuestion(subQ);
        e.setQueryStrategy(QueryStrategy.TOPIC_EXPLORATION);
        e.setFinding(finding);
        e.setSourceType("CHAT_MESSAGE");
        e.setSourceRef(sourceRef);
        e.setConfidenceScore(0.85);
        e.setIteration(0);
        return e;
    }

    @Test
    void generateReport_callsLlmWithEvidencePrompt_andStoresReport() {
        ResearchSession session = buildSession("What are the risks of AI in moderation?");
        ResearchEvidence evidence = buildEvidence(
                session,
                "What concerns do users raise?",
                "Users worry about bias in automated decisions",
                "msg-001");

        String llmResponse = "## Executive Summary\nAI moderation poses several risks.\n\n## Key Findings\n- Bias risk\n";
        when(llmClient.analyse(anyString(), eq("REPORT"))).thenReturn(llmResponse);
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchReport report = service.generateReport(session, List.of(evidence), ReportTemplate.TOPIC);

        assertThat(report).isNotNull();
        assertThat(report.getContent()).isEqualTo(llmResponse);
        assertThat(report.getTemplate()).isEqualTo(ReportTemplate.TOPIC);
        assertThat(report.getTitle()).contains("AI in moderation");
        assertThat(report.getTenantId()).isEqualTo(session.getTenantId());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).analyse(promptCaptor.capture(), eq("REPORT"));
        assertThat(promptCaptor.getValue()).contains("What are the risks of AI in moderation?");
        assertThat(promptCaptor.getValue()).contains("Users worry about bias");
    }

    @Test
    void generateReport_usesPersonTemplate_whenReportTemplateIsPerson() {
        ResearchSession session = buildSession("Who is Alice Smith?");
        when(llmClient.analyse(anyString(), eq("REPORT"))).thenReturn("## Executive Summary\nAlice is...");
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchReport report = service.generateReport(session, List.of(), ReportTemplate.PERSON);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).analyse(promptCaptor.capture(), eq("REPORT"));
        assertThat(promptCaptor.getValue()).contains("profiling an individual");
    }

    @Test
    void generateReport_storesFallbackContent_whenLlmReturnsNull() {
        ResearchSession session = buildSession("Is claim X true?");
        when(llmClient.analyse(anyString(), eq("REPORT"))).thenReturn(null);
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchReport report = service.generateReport(session, List.of(), ReportTemplate.FACT_CHECK);

        assertThat(report.getContent()).contains("Report Generation Failed");
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=ResearchReportServiceTest -q 2>&1 | tail -10
```

Expected: compilation error.

- [ ] **Step 3: Create `ResearchReportService`**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.*;
import io.emcip.knowledge.engine.repository.ResearchReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchReportService {

    private final LlmOrchestratorClient llmClient;
    private final ResearchReportRepository reportRepository;

    private static final String TOPIC_PROMPT_TEMPLATE = """
            You are a research analyst synthesizing community intelligence.
            Based on the evidence below about "%s", write a structured research report.

            Format your response as Markdown with exactly these sections:
            ## Executive Summary
            (2–3 sentences summarizing key findings)

            ## Key Findings
            (3–5 bullet points of the most important discoveries)

            ## Community Perspective
            (What community members discuss, believe, or are concerned about)

            ## Factual Context
            (Verified facts from external sources that provide context)

            ## Contradictions & Open Questions
            (Areas of disagreement, unverified claims, or open questions)

            ## Sources
            (List each source as: - [source_type] source_ref)

            Evidence collected:
            %s
            """;

    private static final String PERSON_PROMPT_TEMPLATE = """
            You are a research analyst profiling an individual based on community intelligence.
            Based on the evidence below about "%s", write a structured person analysis report.

            Format your response as Markdown with exactly these sections:
            ## Executive Summary
            (2–3 sentences about this person's role and significance)

            ## Key Findings
            (3–5 bullet points about this person's notable activities or statements)

            ## Community Perspective
            (How community members perceive and discuss this person)

            ## Factual Context
            (Verified facts about this person from external sources)

            ## Contradictions & Open Questions
            (Inconsistencies in reporting or open questions)

            ## Sources
            (List each source as: - [source_type] source_ref)

            Evidence collected:
            %s
            """;

    private static final String FACT_CHECK_PROMPT_TEMPLATE = """
            You are a fact-checking researcher.
            Based on the evidence below about "%s", write a structured fact-check report.

            Format your response as Markdown with exactly these sections:
            ## Executive Summary
            (Verdict: Supported / Unsupported / Partially Supported / Insufficient Evidence)

            ## Key Findings
            (3–5 bullet points of evidence for or against the claim)

            ## Community Perspective
            (What community members say about this claim)

            ## Factual Context
            (Verified facts from external sources)

            ## Contradictions & Open Questions
            (Conflicting evidence or remaining uncertainty)

            ## Sources
            (List each source as: - [source_type] source_ref)

            Evidence collected:
            %s
            """;

    /**
     * Synthesises all collected evidence into a Markdown research report using the LLM.
     * Stores and returns the report. Non-fatal: if LLM fails, stores a placeholder report.
     */
    @Transactional
    public ResearchReport generateReport(
            ResearchSession session,
            List<ResearchEvidence> evidence,
            ReportTemplate template) {

        String promptTemplate = selectPromptTemplate(template);
        String evidenceSummary = buildEvidenceSummary(evidence);
        String prompt = promptTemplate.formatted(session.getQuestion(), evidenceSummary);

        String content = llmClient.analyse(prompt, "REPORT");
        if (content == null || content.isBlank()) {
            log.warn("LLM returned no content for report on session {}", session.getId());
            content = "# Report Generation Failed\n\nThe LLM could not generate a report for this session.";
        }

        ResearchReport report = new ResearchReport();
        report.setTenantId(session.getTenantId());
        report.setSession(session);
        report.setTemplate(template);
        report.setTitle(buildTitle(session.getQuestion(), template));
        report.setContent(content);
        report.setVersion(1);

        return reportRepository.save(report);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private String selectPromptTemplate(ReportTemplate template) {
        return switch (template) {
            case TOPIC -> TOPIC_PROMPT_TEMPLATE;
            case PERSON -> PERSON_PROMPT_TEMPLATE;
            case FACT_CHECK -> FACT_CHECK_PROMPT_TEMPLATE;
        };
    }

    private String buildEvidenceSummary(List<ResearchEvidence> evidence) {
        if (evidence.isEmpty()) return "(No evidence collected)";

        Map<String, List<ResearchEvidence>> bySubQuestion =
                evidence.stream().collect(Collectors.groupingBy(ResearchEvidence::getSubQuestion));

        StringBuilder sb = new StringBuilder();
        bySubQuestion.forEach((subQ, items) -> {
            sb.append("### ").append(subQ).append("\n");
            for (ResearchEvidence e : items) {
                sb.append("- [").append(e.getSourceType()).append("] ")
                        .append(e.getFinding())
                        .append(" (confidence: ").append(String.format("%.2f", e.getConfidenceScore())).append(")\n");
                sb.append("  Source: ").append(e.getSourceRef()).append("\n");
            }
            sb.append("\n");
        });
        return sb.toString();
    }

    private String buildTitle(String question, ReportTemplate template) {
        String prefix = switch (template) {
            case TOPIC -> "Research Report: ";
            case PERSON -> "Person Analysis: ";
            case FACT_CHECK -> "Fact Check: ";
        };
        String truncated = question.length() <= 80 ? question : question.substring(0, 77) + "...";
        return prefix + truncated;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=ResearchReportServiceTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 3 tests passing.

- [ ] **Step 5: Run full module tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchReportService.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchReportServiceTest.java
git commit -m "feat(27b): add ResearchReportService — LLM evidence synthesis into structured Markdown report"
```

---

## Task 6: Wire report generation into session completion + add reportId to ResearchSessionDto

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchSessionDto.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchAgentService.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResearchController.java`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchAgentServiceTest.java`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java`

After the loop completes with COMPLETED status, `startResearch` automatically generates a report. `ResearchSessionDto` gains a nullable `reportId` field. `ResearchController.toDto()` loads the report id from the repository.

- [ ] **Step 1: Update `ResearchSessionDto` — add `reportId`**

Add `UUID reportId` as the last field and update the `from()` factory to accept a `UUID reportId` third parameter:

```java
package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResearchSessionDto(
        UUID id,
        UUID tenantId,
        String question,
        ResearchStatus status,
        int maxIterations,
        int maxLlmCalls,
        double costLimitUsd,
        int iterationsUsed,
        int llmCallsUsed,
        double costUsedUsd,
        String errorMessage,
        List<ResearchEvidenceDto> evidence,
        UUID reportId,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchSessionDto from(
            ResearchSession s, List<ResearchEvidenceDto> evidence, UUID reportId) {
        return new ResearchSessionDto(
                s.getId(),
                s.getTenantId(),
                s.getQuestion(),
                s.getStatus(),
                s.getMaxIterations(),
                s.getMaxLlmCalls(),
                s.getCostLimitUsd(),
                s.getIterationsUsed(),
                s.getLlmCallsUsed(),
                s.getCostUsedUsd(),
                s.getErrorMessage(),
                evidence,
                reportId,
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}
```

- [ ] **Step 2: Update `ResearchAgentService` — add report generation to `startResearch`**

Read the current `ResearchAgentService.java`.

1. Add `ResearchReportService reportService` and `ResearchEvidenceRepository evidenceRepository` fields. (Note: `evidenceRepository` is already a field — confirm it exists. If so, don't duplicate. Only add `reportService`.)

2. Update `startResearch()` — after setting `COMPLETED`, load evidence and call report service:

```java
@Transactional
public ResearchSession startResearch(ResearchRequest request) {
    ResearchSession session = new ResearchSession();
    session.setTenantId(request.tenantId());
    session.setQuestion(request.question());
    session.setMaxIterations(request.maxIterations());
    session.setMaxLlmCalls(request.maxLlmCalls());
    session.setCostLimitUsd(request.costLimitUsd());
    session.setStatus(ResearchStatus.CREATED);
    sessionRepository.save(session);

    session.setStatus(ResearchStatus.RUNNING);
    sessionRepository.save(session);

    try {
        runLoop(session, request.webSearchEnabled());
        session.setStatus(ResearchStatus.COMPLETED);
    } catch (Exception e) {
        log.error("Research session {} failed: {}", session.getId(), e.getMessage(), e);
        session.setStatus(ResearchStatus.FAILED);
        session.setErrorMessage(e.getMessage());
    }

    sessionRepository.save(session);
    publishCompletionEvent(session);

    // Auto-generate report for completed sessions
    if (session.getStatus() == ResearchStatus.COMPLETED) {
        generateReportSafely(session, request.reportTemplate());
    }

    return session;
}
```

3. Add the `generateReportSafely` private method:

```java
private void generateReportSafely(ResearchSession session, ReportTemplate template) {
    try {
        List<ResearchEvidence> evidence =
                evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(session.getId());
        reportService.generateReport(session, evidence, template);
        log.info("Generated {} report for session {}", template, session.getId());
    } catch (Exception e) {
        log.warn(
                "Report generation failed for session {} (non-fatal): {}",
                session.getId(),
                e.getMessage());
    }
}
```

4. Import `io.emcip.knowledge.engine.entity.ReportTemplate` at the top.

- [ ] **Step 3: Update `ResearchController` — inject `ResearchReportRepository`, update `toDto`**

Read `ResearchController.java`. Make two changes:

1. Add `ResearchReportRepository reportRepository` as a constructor-injected field.

2. Update `toDto(session)`:

```java
private ResearchSessionDto toDto(ResearchSession session) {
    List<ResearchEvidenceDto> evidence = evidenceRepository
            .findBySessionIdOrderByIterationAscCreatedAtAsc(session.getId())
            .stream()
            .map(e -> new ResearchEvidenceDto(
                    e.getId(), e.getSubQuestion(), e.getQueryStrategy(),
                    e.getFinding(), e.getSourceType(), e.getSourceRef(),
                    e.getConfidenceScore(), e.getIteration(), e.getCreatedAt()))
            .toList();
    UUID reportId = reportRepository.findBySessionId(session.getId())
            .map(io.emcip.knowledge.engine.entity.ResearchReport::getId)
            .orElse(null);
    return ResearchSessionDto.from(session, evidence, reportId);
}
```

- [ ] **Step 4: Update `ResearchAgentServiceTest` — add ResearchReportService mock**

Read `ResearchAgentServiceTest.java`. Add:

```java
@Mock private ResearchReportService reportService;
```

And update `setUp()`:
```java
service = new ResearchAgentService(
        sessionRepository, evidenceRepository,
        strategyService, queryService, eventPublisher,
        webSearchService, reportService);
```

Add one new test to verify report generation is triggered after completion:

```java
@Test
void startResearch_triggersReportGeneration_whenCompleted() {
    UUID tenantId = UUID.randomUUID();
    ResearchRequest request = new ResearchRequest(
            "Research question", tenantId, 10, 20, 1.00, false, ReportTemplate.TOPIC);

    when(strategyService.decompose(anyString()))
            .thenReturn(List.of(new ResearchStrategyService.SubQuestion("Q1", QueryStrategy.TOPIC_EXPLORATION)));
    when(queryService.search(any())).thenReturn(new SearchResponse(List.of(), List.of()));
    when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(any()))
            .thenReturn(List.of());

    service.startResearch(request);

    verify(reportService).generateReport(any(), any(), eq(ReportTemplate.TOPIC));
}
```

- [ ] **Step 5: Update `ResearchControllerTest` — add ResearchReportRepository mock**

Read `ResearchControllerTest.java`. Add:

```java
@Mock private ResearchReportRepository reportRepository;
```

Update `setUp()`:
```java
controller = new ResearchController(agentService, sessionRepository, evidenceRepository, reportRepository);
```

Update all calls to `evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(...)` stubs — also add a stub for `reportRepository.findBySessionId(...)` returning `Optional.empty()`:
```java
when(reportRepository.findBySessionId(any())).thenReturn(Optional.empty());
```

Add this stub to each test that calls `controller.startResearch(...)` or `controller.listSessions(...)` or `controller.getSession(...)`.

- [ ] **Step 6: Run all tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -q 2>&1 | grep -E "Tests run:|BUILD|FAIL" | tail -5
```

Expected: BUILD SUCCESS, no failures.

- [ ] **Step 7: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchSessionDto.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchAgentService.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResearchController.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchAgentServiceTest.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java
git commit -m "feat(27b): auto-generate report on session completion; add reportId to ResearchSessionDto"
```

---

## Task 7: Report endpoints in ResearchController + admin-api proxy

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResearchController.java`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResearchProxyController.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/ResearchProxyControllerTest.java`

Two new endpoints on `ResearchController`:
- `GET /api/knowledge/research/{id}/report` → `ResearchReportDto` (200) or 404
- `GET /api/knowledge/research/{id}/report/markdown` → `String` (200) or 404

And two new proxy endpoints on `ResearchProxyController`:
- `GET /api/admin/knowledge/research/{id}/report` → proxied to knowledge-engine
- `GET /api/admin/knowledge/research/{id}/report/markdown` → proxied

- [ ] **Step 1: Add report endpoints to `ResearchController`**

Read the current `ResearchController.java`. Add two methods after `resumeSession`:

```java
@GetMapping("/{id}/report")
public ResponseEntity<ResearchReportDto> getReport(@PathVariable UUID id) {
    return reportRepository.findBySessionId(id)
            .map(r -> ResponseEntity.ok(ResearchReportDto.from(r)))
            .orElse(ResponseEntity.notFound().build());
}

@GetMapping("/{id}/report/markdown")
public ResponseEntity<String> getReportMarkdown(@PathVariable UUID id) {
    return reportRepository.findBySessionId(id)
            .map(r -> ResponseEntity.ok()
                    .header("Content-Type", "text/markdown; charset=UTF-8")
                    .header("Content-Disposition",
                            "attachment; filename=\"report-" + id + ".md\"")
                    .body(r.getContent()))
            .orElse(ResponseEntity.notFound().build());
}
```

- [ ] **Step 2: Add tests for the new report endpoints to `ResearchControllerTest`**

Read `ResearchControllerTest.java`. Add after the existing tests:

```java
@Test
void getReport_returns200_withReportDto() {
    UUID sessionId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    ResearchSession session = buildSession(sessionId, tenantId, ResearchStatus.COMPLETED);

    ResearchReport report = new ResearchReport();
    report.setId(UUID.randomUUID());
    report.setTenantId(tenantId);
    report.setSession(session);
    report.setTemplate(ReportTemplate.TOPIC);
    report.setTitle("Research Report: Test");
    report.setContent("## Executive Summary\nTest report content.");
    report.setVersion(1);

    when(reportRepository.findBySessionId(sessionId)).thenReturn(Optional.of(report));

    ResponseEntity<ResearchReportDto> response = controller.getReport(sessionId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().template()).isEqualTo(ReportTemplate.TOPIC);
    assertThat(response.getBody().content()).contains("Executive Summary");
}

@Test
void getReport_returns404_whenNoReport() {
    UUID sessionId = UUID.randomUUID();
    when(reportRepository.findBySessionId(sessionId)).thenReturn(Optional.empty());

    ResponseEntity<ResearchReportDto> response = controller.getReport(sessionId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
}

@Test
void getReportMarkdown_returns200_withMarkdownContent() {
    UUID sessionId = UUID.randomUUID();
    ResearchSession session = buildSession(sessionId, UUID.randomUUID(), ResearchStatus.COMPLETED);

    ResearchReport report = new ResearchReport();
    report.setId(UUID.randomUUID());
    report.setTenantId(UUID.randomUUID());
    report.setSession(session);
    report.setTemplate(ReportTemplate.TOPIC);
    report.setTitle("Test");
    report.setContent("## Executive Summary\nContent here.");
    report.setVersion(1);

    when(reportRepository.findBySessionId(sessionId)).thenReturn(Optional.of(report));

    ResponseEntity<String> response = controller.getReportMarkdown(sessionId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("Executive Summary");
    assertThat(response.getHeaders().getFirst("Content-Type")).contains("text/markdown");
}
```

You will need to add these imports to the test file:
```java
import io.emcip.knowledge.engine.entity.ReportTemplate;
import io.emcip.knowledge.engine.entity.ResearchReport;
import io.emcip.knowledge.engine.model.ResearchReportDto;
```

- [ ] **Step 3: Run knowledge-engine tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Add report proxy endpoints to `ResearchProxyController`**

Read `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResearchProxyController.java`. Follow the exact same pattern used for `getSession`. Add these two methods at the end of the class (before the closing `}`):

```java
@GetMapping("/{id}/report")
public Mono<ResponseEntity<String>> getReport(@PathVariable UUID id) {
    return knowledgeWebClient
            .get()
            .uri("/api/knowledge/research/" + id + "/report")
            .retrieve()
            .toEntity(String.class)
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .onErrorResume(e -> {
                log.warn("getReport circuit breaker open for session {}: {}", id, e.getMessage());
                return Mono.just(ResponseEntity.status(503).<String>build());
            });
}

@GetMapping("/{id}/report/markdown")
public Mono<ResponseEntity<String>> getReportMarkdown(@PathVariable UUID id) {
    return knowledgeWebClient
            .get()
            .uri("/api/knowledge/research/" + id + "/report/markdown")
            .retrieve()
            .toEntity(String.class)
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .onErrorResume(e -> {
                log.warn("getReportMarkdown circuit breaker open for session {}: {}", id, e.getMessage());
                return Mono.just(ResponseEntity.status(503).<String>build());
            });
}
```

> **Adjust the pattern to match what is already in `ResearchProxyController`** — if the existing proxy methods use a different import for `CircuitBreakerOperator` or a different WebClient call chain, match it exactly.

- [ ] **Step 5: Add tests for the new proxy endpoints to `ResearchProxyControllerTest`**

Read `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/ResearchProxyControllerTest.java`. Add two tests after the existing ones following the exact same test pattern used in that file:

```java
@Test
void getReport_proxiesGetToKnowledgeEngine() {
    UUID sessionId = UUID.randomUUID();
    // Follow the EXACT same mock chain pattern used in other GET tests in this file
    // The mock chain for GET endpoints should be: exchange function intercepts the request,
    // returns a mocked response with 200 OK.
    // See getSession test for exact pattern.
    // Verify: response status == 200
}

@Test
void getReportMarkdown_proxiesGetToKnowledgeEngine() {
    UUID sessionId = UUID.randomUUID();
    // Same pattern as getReport test.
    // Verify: response status == 200
}
```

> **Fill in the test bodies by copying and adapting `getSession_proxiesGetRequestAndReturns200()` exactly.** Only change the URI path to match the new endpoints.

- [ ] **Step 6: Run admin-api tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
mvn -pl emcip-admin-api spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResearchController.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResearchProxyController.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/controller/ResearchProxyControllerTest.java
git commit -m "feat(27b): add GET /{id}/report and /{id}/report/markdown endpoints with admin-api proxy"
```

---

## Task 8: Full build + Spotless

- [ ] **Step 1: Full clean build of both modules**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine,emcip-admin-api clean verify -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS for both. If it fails, check:
```bash
mvn -pl emcip-knowledge-engine,emcip-admin-api clean verify 2>&1 | grep -E "ERROR|FAILURE" | head -20
```

- [ ] **Step 2: Spotless check**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine,emcip-admin-api spotless:check 2>&1 | grep -E "changed|clean|ERROR"
```

Expected: `0 were changed to be clean` for both.

If formatting is needed:
```bash
mvn -pl emcip-knowledge-engine,emcip-admin-api spotless:apply -q
git add emcip-knowledge-engine/ emcip-admin-api/
git commit -m "style(27b): apply spotless"
```

---

## Self-Review

### Spec Coverage

| Requirement | Task |
|---|---|
| US-27.3: Web search API integration (SearXNG preferred, Brave fallback) | Tasks 1, 2 |
| US-27.3: Search result fetching + content extraction | Task 1 (SearXngConnector) |
| US-27.3: Relevance scoring | Brave/SearXNG connectors return scored results; stored as evidence with `confidenceScore=0.70` default |
| US-27.3: Results optionally stored as factual knowledge documents | Task 3 — web results stored as `ResearchEvidence` (sourceType=WEB_SEARCH); full KnowledgeDocument persistence deferred to Plan C (requires operator approval UI) |
| US-27.3: Rate limiting | Handled by existing connector infrastructure (connectors return empty on 429) |
| US-27.3: Cost tracking for external API calls | WebSearchService uses existing VendorApiKey; session `costUsedUsd` already incremented per iteration |
| US-27.5: LLM-generated report from evidence | Task 5 (ResearchReportService.generateReport) |
| US-27.5: Six standard report sections | Tasks 5 (three prompt templates each define all six sections) |
| US-27.5: Report templates (topic, person, fact-check) | Task 4 (ReportTemplate enum), Task 5 (template selection) |
| US-27.5: Reports stored as knowledge artifacts | Task 4 (ke_research_reports table), Task 5 (stored via ResearchReportRepository) |
| US-27.5: Export as Markdown | Task 7 (GET /{id}/report/markdown endpoint with Content-Disposition header) |
| US-27.5: Auto-trigger on session completion | Task 6 (ResearchAgentService.startResearch calls generateReportSafely after COMPLETED) |
| Admin-API proxy for new endpoints | Tasks 7 (GET /{id}/report, GET /{id}/report/markdown) |

### Gaps

1. **"Results optionally stored as factual knowledge documents (operator approval)"** — Plan B stores web search results as `ResearchEvidence` (already a form of knowledge artifact). Full persistence as `KnowledgeDocument` through the extraction pipeline, with operator approval flow, is a UI concern deferred to Plan C.

2. **"Rendered in admin-ui"** — Plan C only; Plan B provides the REST endpoints.

3. **`webSearchEnabled` not persisted on session** — when resuming a paused session, `webSearchEnabled` defaults to `false`. Callers that need web search on resume must start a new session. This is documented in `ResearchAgentService`.

### Placeholder Scan

No TBD, TODO, or vague instructions. Task 7, Step 5 defers test body details to the implementer with a clear "copy from `getSession_proxiesGetRequestAndReturns200()`" instruction — this is intentional since the exact mock chain is codebase-specific.

### Type Consistency

- `ReportTemplate` — defined Task 4, used in Task 3 (ResearchRequest), Task 5 (ResearchReportService), Task 6 (ResearchAgentService + SessionDto) ✅
- `ResearchReport` — defined Task 4, used in Task 5 (service return), Task 6 (wired into controller), Task 7 (endpoints) ✅
- `ResearchReportDto.from(ResearchReport)` — defined Task 4, used in Task 7 ✅
- `ResearchSessionDto.from(session, evidence, reportId)` — updated Task 6 with 3-arg signature, used in ResearchController ✅
- `WebSearchService.search(String query, UUID tenantId)` — defined Task 2, used in Task 3 ✅
