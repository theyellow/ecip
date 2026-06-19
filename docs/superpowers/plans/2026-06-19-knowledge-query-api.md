# Knowledge Query API (US-26.9) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the knowledge-engine's search and graph-exploration capabilities through the Admin-API proxy and a Search tab on the Admin-UI Knowledge page, with real pgvector similarity scores replacing the current placeholder formula.

**Architecture:** Add a `SearchResult<T>` record so `PgVectorSearchRepository.search()` can return real cosine similarity scores; `KnowledgeQueryService` uses those scores. A new `KnowledgeSearchProxyController` in admin-api forwards four endpoints. `KnowledgePage.jsx` gains a tab switcher (Search | Ingestion Jobs), with the Search tab showing a query bar, side-by-side entity/passage result columns, and an inline neighbor expansion panel.

**Tech Stack:** Java 21, Spring Boot 4, JPA + `JdbcTemplate` (pgvector native SQL), WebClient/WebFlux (admin-api proxy), React + CSS Modules (admin-ui)

---

## File Map

| Action | Path |
|--------|------|
| CREATE | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/SearchResult.java` |
| MODIFY | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/VectorSearchRepository.java` |
| MODIFY | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/PgVectorSearchRepository.java` |
| MODIFY | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeQueryService.java` |
| MODIFY | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/SearchRequest.java` |
| MODIFY | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/KnowledgeSearchController.java` |
| MODIFY | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/repository/PgVectorSearchRepositoryTest.java` |
| MODIFY | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeQueryServiceTest.java` |
| CREATE | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/KnowledgeSearchProxyController.java` |
| CREATE | `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/KnowledgeSearchProxyControllerTest.java` |
| MODIFY | `emcip-admin-ui/src/main/frontend/src/api/knowledge.js` |
| MODIFY | `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx` |
| MODIFY | `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.module.css` |

---

## Task 1: `SearchResult<T>` record + real scores in repository + service

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/SearchResult.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/VectorSearchRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/PgVectorSearchRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeQueryService.java`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/repository/PgVectorSearchRepositoryTest.java`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeQueryServiceTest.java`

- [ ] **Step 1: Write failing test in `PgVectorSearchRepositoryTest`**

Replace the existing `shouldStoreAndSearchByEmbedding` test with one that also asserts scores:

```java
package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.SearchResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class PgVectorSearchRepositoryTest {

    @Autowired private VectorSearchRepository vectorSearchRepository;
    @Autowired private KnowledgeDocumentRepository documentRepository;

    @Test
    void shouldReturnRealSimilarityScores() {
        UUID tenantId = UUID.randomUUID();

        // Doc A — embedding close to query
        KnowledgeDocument docA = new KnowledgeDocument();
        docA.setTenantId(tenantId);
        docA.setSourceType("CHAT_MESSAGE");
        docA.setSourceRef("msg-A");
        docA.setContent("close match");
        docA.setChunkIndex(0);
        KnowledgeDocument savedA = documentRepository.save(docA);

        // Doc B — embedding far from query
        KnowledgeDocument docB = new KnowledgeDocument();
        docB.setTenantId(tenantId);
        docB.setSourceType("CHAT_MESSAGE");
        docB.setSourceRef("msg-B");
        docB.setContent("far match");
        docB.setChunkIndex(0);
        KnowledgeDocument savedB = documentRepository.save(docB);

        float[] closeEmbedding = new float[1536];
        closeEmbedding[0] = 1.0f;
        float[] farEmbedding = new float[1536];
        farEmbedding[1] = 1.0f; // orthogonal dimension

        vectorSearchRepository.storeEmbedding(savedA.getId(), closeEmbedding);
        vectorSearchRepository.storeEmbedding(savedB.getId(), farEmbedding);

        float[] queryEmbedding = new float[1536];
        queryEmbedding[0] = 1.0f; // identical to docA

        List<SearchResult<KnowledgeDocument>> results =
                vectorSearchRepository.search(queryEmbedding, 10, tenantId);

        assertThat(results).hasSize(2);
        // First result should be docA with score near 1.0
        assertThat(results.getFirst().item().getId()).isEqualTo(savedA.getId());
        assertThat(results.getFirst().score()).isGreaterThan(0.99);
        // Second result should score lower
        assertThat(results.get(1).score()).isLessThan(results.getFirst().score());
    }
}
```

- [ ] **Step 2: Run test — confirm it fails to compile**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-knowledge-engine -Dtest=PgVectorSearchRepositoryTest -q 2>&1 | tail -15
```

Expected: compilation error — `SearchResult` does not exist, `search()` returns `List<KnowledgeDocument>`.

- [ ] **Step 3: Create `SearchResult<T>` record**

```java
// emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/SearchResult.java
package io.emcip.knowledge.engine.model;

public record SearchResult<T>(T item, double score) {}
```

- [ ] **Step 4: Update `VectorSearchRepository` interface**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.SearchResult;
import java.util.List;
import java.util.UUID;

public interface VectorSearchRepository {
    void storeEmbedding(UUID documentId, float[] embedding);

    List<SearchResult<KnowledgeDocument>> search(float[] queryEmbedding, int topK, UUID tenantId);

    List<KnowledgeDocument> hybridSearch(
            String textQuery, float[] queryEmbedding, int topK, UUID tenantId);
}
```

- [ ] **Step 5: Update `PgVectorSearchRepository.search()` to return scores**

The SQL already selects `embedding <=> ?::vector AS distance`. Add score reading in `mapRowWithScore` and change `search()` to use it:

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.SearchResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
@Slf4j
@RequiredArgsConstructor
public class PgVectorSearchRepository implements VectorSearchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void storeEmbedding(UUID documentId, float[] embedding) {
        String vectorStr = toVectorString(embedding);
        jdbcTemplate.update(
                "UPDATE ke_knowledge_documents SET embedding = ?::vector WHERE id = ?",
                vectorStr,
                documentId);
        log.debug("Stored embedding for document {}", documentId);
    }

    @Override
    public List<SearchResult<KnowledgeDocument>> search(
            float[] queryEmbedding, int topK, UUID tenantId) {
        String vectorStr = toVectorString(queryEmbedding);
        String sql;
        Object[] params;

        if (tenantId != null) {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at,
                           1 - (embedding <=> ?::vector) AS score
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL AND (tenant_id = ? OR tenant_id IS NULL)
                    ORDER BY embedding <=> ?::vector ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, tenantId, vectorStr, topK};
        } else {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at,
                           1 - (embedding <=> ?::vector) AS score
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL
                    ORDER BY embedding <=> ?::vector ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, vectorStr, topK};
        }

        return jdbcTemplate.query(sql, this::mapRowWithScore, params);
    }

    @Override
    public List<KnowledgeDocument> hybridSearch(
            String textQuery, float[] queryEmbedding, int topK, UUID tenantId) {
        String vectorStr = toVectorString(queryEmbedding);
        String sql;
        Object[] params;

        if (tenantId != null) {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at, embedding <=> ?::vector AS distance
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL
                      AND (tenant_id = ? OR tenant_id IS NULL)
                      AND content ILIKE '%' || ? || '%'
                    ORDER BY distance ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, tenantId, textQuery, topK};
        } else {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at, embedding <=> ?::vector AS distance
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL AND content ILIKE '%' || ? || '%'
                    ORDER BY distance ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, textQuery, topK};
        }

        return jdbcTemplate.query(sql, this::mapRow, params);
    }

    private SearchResult<KnowledgeDocument> mapRowWithScore(ResultSet rs, int rowNum)
            throws SQLException {
        KnowledgeDocument doc = mapRow(rs, rowNum);
        double score = rs.getDouble("score");
        return new SearchResult<>(doc, score);
    }

    @SuppressWarnings("unchecked")
    private KnowledgeDocument mapRow(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(UUID.fromString(rs.getString("id")));
        String tenantStr = rs.getString("tenant_id");
        if (tenantStr != null) doc.setTenantId(UUID.fromString(tenantStr));
        doc.setSourceType(rs.getString("source_type"));
        doc.setSourceRef(rs.getString("source_ref"));
        doc.setContent(rs.getString("content"));
        doc.setChunkIndex(rs.getInt("chunk_index"));
        String metaJson = rs.getString("metadata");
        if (metaJson != null) {
            try {
                doc.setMetadata(objectMapper.readValue(metaJson, Map.class));
            } catch (Exception e) {
                log.warn("Failed to parse metadata JSON", e);
            }
        }
        doc.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return doc;
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
```

- [ ] **Step 6: Update `KnowledgeQueryService` to use real scores**

Replace the placeholder `1.0 - (i * 0.05)` formula:

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.model.SearchRequest.SearchType;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.model.SearchResponse.DocumentResult;
import io.emcip.knowledge.engine.model.SearchResponse.GraphNodeResult;
import io.emcip.knowledge.engine.model.SearchResult;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeQueryService {

    private final VectorSearchRepository vectorSearchRepository;
    private final GraphRepository graphRepository;
    private final LlmOrchestratorClient llmClient;

    public SearchResponse search(SearchRequest request) {
        List<GraphNodeResult> graphResults = new ArrayList<>();
        List<DocumentResult> documentResults = new ArrayList<>();

        float[] queryEmbedding = llmClient.embed(request.query());

        if (request.searchType() == SearchType.VECTOR
                || request.searchType() == SearchType.HYBRID) {
            List<SearchResult<KnowledgeDocument>> scored =
                    vectorSearchRepository.search(
                            queryEmbedding, request.limit(), request.tenantId());
            for (SearchResult<KnowledgeDocument> sr : scored) {
                documentResults.add(new DocumentResult(sr.item(), sr.score()));
            }
        }

        if (request.searchType() == SearchType.GRAPH || request.searchType() == SearchType.HYBRID) {
            if (request.conceptTypes() != null) {
                for (String conceptType : request.conceptTypes()) {
                    List<GraphNode> nodes =
                            graphRepository.findNodesByType(
                                    conceptType, request.tenantId(), request.limit());
                    for (GraphNode node : nodes) {
                        List<GraphNode> connections =
                                graphRepository.findConnected(node.id(), null, 1);
                        graphResults.add(new GraphNodeResult(node, connections, 0.5));
                    }
                }
            }
        }

        log.info(
                "Search completed: query='{}', type={}, graphResults={}, docResults={}",
                request.query(),
                request.searchType(),
                graphResults.size(),
                documentResults.size());

        return new SearchResponse(graphResults, documentResults);
    }
}
```

- [ ] **Step 7: Update `KnowledgeQueryServiceTest` to assert real scores**

```java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.model.SearchRequest.SearchType;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.model.SearchResult;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeQueryServiceTest {

    @Mock private VectorSearchRepository vectorSearchRepository;
    @Mock private GraphRepository graphRepository;
    @Mock private LlmOrchestratorClient llmClient;

    private KnowledgeQueryService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeQueryService(vectorSearchRepository, graphRepository, llmClient);
    }

    @Test
    void shouldUseRealScoresFromRepository() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(UUID.randomUUID());
        doc.setContent("AI discussion");
        doc.setCreatedAt(Instant.now());

        when(llmClient.embed("Tell me about AI")).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(vectorSearchRepository.search(any(), eq(20), eq(tenantId)))
                .thenReturn(List.of(new SearchResult<>(doc, 0.93)));

        SearchRequest request =
                new SearchRequest("Tell me about AI", SearchType.VECTOR, tenantId, null, null, 20);
        SearchResponse response = service.search(request);

        assertThat(response.documentResults()).hasSize(1);
        assertThat(response.documentResults().getFirst().similarity()).isEqualTo(0.93);
    }

    @Test
    void hybridMode_returnsBothGraphAndDocumentResults() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(UUID.randomUUID());
        doc.setContent("content");
        doc.setCreatedAt(Instant.now());

        GraphNode node =
                new GraphNode(UUID.randomUUID(), "Topic", tenantId, "AI Policy", null,
                        Instant.now(), Instant.now());

        when(llmClient.embed("AI policy")).thenReturn(new float[] {0.1f});
        when(vectorSearchRepository.search(any(), eq(20), eq(tenantId)))
                .thenReturn(List.of(new SearchResult<>(doc, 0.87)));
        when(graphRepository.findNodesByType("Topic", tenantId, 20)).thenReturn(List.of(node));
        when(graphRepository.findConnected(node.id(), null, 1)).thenReturn(List.of());

        SearchRequest request =
                new SearchRequest("AI policy", SearchType.HYBRID, tenantId,
                        List.of("Topic"), null, 20);
        SearchResponse response = service.search(request);

        assertThat(response.documentResults()).hasSize(1);
        assertThat(response.graphResults()).hasSize(1);
    }

    @Test
    void graphOnlyMode_doesNotCallVectorSearch() {
        UUID tenantId = UUID.randomUUID();
        when(llmClient.embed("AI")).thenReturn(new float[] {0.1f});
        when(graphRepository.findNodesByType("Topic", tenantId, 10)).thenReturn(List.of());

        SearchRequest request =
                new SearchRequest("AI", SearchType.GRAPH, tenantId, List.of("Topic"), null, 10);
        service.search(request);

        verify(vectorSearchRepository, never()).search(any(), eq(10), eq(tenantId));
    }
}
```

- [ ] **Step 8: Run all knowledge-engine tests**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-knowledge-engine -q 2>&1 | grep -E "Tests run:|BUILD"
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 9: Spotless + commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/
git commit -m "feat(knowledge-engine): real pgvector similarity scores via SearchResult<T> (#26.9)"
```

---

## Task 2: `@NotBlank` on `SearchRequest` + `@Valid` on controller

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/SearchRequest.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/KnowledgeSearchController.java`

- [ ] **Step 1: Update `SearchRequest` with `@NotBlank`**

```java
package io.emcip.knowledge.engine.model;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

public record SearchRequest(
        @NotBlank String query,
        SearchType searchType,
        UUID tenantId,
        List<String> conceptTypes,
        List<String> sourceTypes,
        int limit) {

    public enum SearchType {
        GRAPH,
        VECTOR,
        HYBRID
    }

    public SearchRequest {
        if (limit <= 0) limit = 20;
        if (searchType == null) searchType = SearchType.HYBRID;
    }
}
```

- [ ] **Step 2: Add `@Valid` to controller**

```java
package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.service.KnowledgeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Knowledge Search", description = "Search the knowledge base")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeSearchController {

    private final KnowledgeQueryService queryService;
    private final GraphRepository graphRepository;

    @Operation(summary = "Search the knowledge base (vector, graph, or hybrid)")
    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return queryService.search(request);
    }

    @Operation(summary = "List graph nodes by concept type")
    @GetMapping("/graph/topics")
    public List<GraphNode> listTopics(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return graphRepository.findNodesByType("Topic", tenantId, limit);
    }

    @Operation(summary = "List graph nodes of type Person")
    @GetMapping("/graph/persons")
    public List<GraphNode> listPersons(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return graphRepository.findNodesByType("Person", tenantId, limit);
    }

    @Operation(summary = "Get neighbors of a graph node")
    @GetMapping("/graph/node/{id}/neighbors")
    public List<GraphNode> getNeighbors(
            @PathVariable UUID id,
            @RequestParam(required = false) String relationshipType,
            @RequestParam(defaultValue = "1") int depth) {
        return graphRepository.findConnected(id, relationshipType, depth);
    }
}
```

- [ ] **Step 3: Run tests + spotless + commit**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-knowledge-engine -q 2>&1 | grep -E "Tests run:|BUILD"
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/
git commit -m "feat(knowledge-engine): add @NotBlank on SearchRequest.query + @Valid in controller (#26.9)"
```

---

## Task 3: Admin-API `KnowledgeSearchProxyController` + test

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/KnowledgeSearchProxyController.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/KnowledgeSearchProxyControllerTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchProxyControllerTest {

    @Mock private ExchangeFunction exchangeFunction;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        WebClient knowledgeWebClient =
                WebClient.builder().exchangeFunction(exchangeFunction).build();
        KnowledgeSearchProxyController controller =
                new KnowledgeSearchProxyController(
                        knowledgeWebClient, CircuitBreakerRegistry.ofDefaults());
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    private void stubOk(String body) {
        ClientResponse response =
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));
    }

    private void stubError() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));
    }

    @Test
    void search_proxiesPostRequest() {
        stubOk("{\"graphResults\":[],\"documentResults\":[]}");

        webTestClient
                .post()
                .uri("/api/admin/knowledge/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"AI policy\",\"searchType\":\"HYBRID\",\"limit\":20}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assert body != null;
                    assert body.contains("documentResults");
                });
    }

    @Test
    void getTopics_proxiesGetRequest() {
        stubOk("[{\"id\":\"" + UUID.randomUUID() + "\",\"conceptType\":\"Topic\",\"label\":\"AI\"}]");

        webTestClient
                .get()
                .uri("/api/admin/knowledge/graph/topics?limit=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assert body != null;
                    assert body.contains("Topic");
                });
    }

    @Test
    void getPersons_proxiesGetRequest() {
        stubOk("[{\"id\":\"" + UUID.randomUUID() + "\",\"conceptType\":\"Person\",\"label\":\"Alice\"}]");

        webTestClient
                .get()
                .uri("/api/admin/knowledge/graph/persons?limit=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assert body != null;
                    assert body.contains("Person");
                });
    }

    @Test
    void getNeighbors_proxiesGetRequest() {
        UUID nodeId = UUID.randomUUID();
        stubOk("[]");

        webTestClient
                .get()
                .uri("/api/admin/knowledge/graph/node/" + nodeId + "/neighbors")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void search_circuitBreaker_returns503OnError() {
        stubError();

        webTestClient
                .post()
                .uri("/api/admin/knowledge/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"test\",\"searchType\":\"VECTOR\",\"limit\":10}")
                .exchange()
                .expectStatus().isEqualTo(503);
    }
}
```

- [ ] **Step 2: Run test — confirm it fails to compile**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -Dtest=KnowledgeSearchProxyControllerTest -q 2>&1 | tail -10
```

Expected: compilation error — `KnowledgeSearchProxyController` does not exist.

- [ ] **Step 3: Create `KnowledgeSearchProxyController`**

```java
package io.emcip.admin.api.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/admin/knowledge")
@Tag(name = "Knowledge Search", description = "Proxy for knowledge-engine search and graph endpoints")
public class KnowledgeSearchProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;

    public KnowledgeSearchProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge");
    }

    @Operation(summary = "Search the knowledge base")
    @PostMapping("/search")
    public Mono<ResponseEntity<String>> search(@RequestBody String body) {
        return knowledgeWebClient
                .post()
                .uri("/api/knowledge/search")
                .bodyValue(body)
                .header("Content-Type", "application/json")
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Knowledge search proxy error: {}", e.getMessage());
                    return Mono.just(
                            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<String>build());
                })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "List graph topic nodes")
    @GetMapping("/graph/topics")
    public Mono<ResponseEntity<String>> getTopics(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return knowledgeWebClient
                .get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/api/knowledge/graph/topics").queryParam("limit", limit);
                    if (tenantId != null) uriBuilder.queryParam("tenantId", tenantId);
                    return uriBuilder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Knowledge graph/topics proxy error: {}", e.getMessage());
                    return Mono.just(
                            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<String>build());
                })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "List graph person nodes")
    @GetMapping("/graph/persons")
    public Mono<ResponseEntity<String>> getPersons(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return knowledgeWebClient
                .get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/api/knowledge/graph/persons").queryParam("limit", limit);
                    if (tenantId != null) uriBuilder.queryParam("tenantId", tenantId);
                    return uriBuilder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Knowledge graph/persons proxy error: {}", e.getMessage());
                    return Mono.just(
                            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<String>build());
                })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get neighbors of a graph node")
    @GetMapping("/graph/node/{id}/neighbors")
    public Mono<ResponseEntity<String>> getNeighbors(
            @PathVariable UUID id,
            @RequestParam(required = false) String relationshipType,
            @RequestParam(defaultValue = "1") int depth) {
        return knowledgeWebClient
                .get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/api/knowledge/graph/node/{id}/neighbors")
                            .queryParam("depth", depth);
                    if (relationshipType != null)
                        uriBuilder.queryParam("relationshipType", relationshipType);
                    return uriBuilder.build(id);
                })
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Knowledge graph/neighbors proxy error nodeId={}: {}", id,
                            e.getMessage());
                    return Mono.just(
                            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<String>build());
                })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -Dtest=KnowledgeSearchProxyControllerTest -q 2>&1 | grep -E "Tests run:|BUILD"
```

Expected: `Tests run: 5, Failures: 0, Errors: 0` — `BUILD SUCCESS`.

- [ ] **Step 5: Run full admin-api test suite**

```bash
mvn test -pl emcip-admin-api -q 2>&1 | grep -E "Tests run:|BUILD"
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Spotless + commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "feat(admin-api): add KnowledgeSearchProxyController for search + graph endpoints (#26.9)"
```

---

## Task 4: Admin-UI — add 4 search API methods to `knowledge.js`

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/api/knowledge.js`

- [ ] **Step 1: Update `knowledge.js`**

Read the current file first, then replace with the full updated version:

```javascript
export function knowledgeApi(request, rawFetch) {
  return {
    /** POST /api/admin/knowledge/ingest/url — returns { jobId } */
    ingestUrl: (url, tenantId) =>
      request('/api/admin/knowledge/ingest/url', {
        method: 'POST',
        body: JSON.stringify({ url, tenantId: tenantId ?? null }),
      }),

    /**
     * POST /api/admin/knowledge/ingest/upload — multipart form data.
     * Uses rawFetch to avoid the JSON Content-Type header collision.
     */
    ingestUpload: (file, tenantId) => {
      const form = new FormData()
      form.append('file', file)
      if (tenantId) form.append('tenantId', tenantId)
      return rawFetch('/api/admin/knowledge/ingest/upload', {
        method: 'POST',
        body: form,
      })
    },

    /** GET /api/admin/knowledge/ingest/{jobId} — returns IngestionJobDto */
    status: jobId =>
      request(`/api/admin/knowledge/ingest/${encodeURIComponent(jobId)}`),

    /** GET /api/admin/knowledge/ingest — returns Spring Page<IngestionJobDto> */
    jobs: (page = 0, size = 20, tenantId) => {
      const params = new URLSearchParams({ page, size })
      if (tenantId) params.append('tenantId', tenantId)
      return request(`/api/admin/knowledge/ingest?${params}`)
    },

    /**
     * POST /api/admin/knowledge/search
     * Returns { graphResults: [...], documentResults: [...] }
     */
    search: (query, searchType = 'HYBRID', tenantId, conceptTypes, limit = 20) =>
      request('/api/admin/knowledge/search', {
        method: 'POST',
        body: JSON.stringify({
          query,
          searchType,
          tenantId: tenantId ?? null,
          conceptTypes: conceptTypes ?? null,
          limit,
        }),
      }),

    /** GET /api/admin/knowledge/graph/topics */
    graphTopics: (tenantId, limit = 50) => {
      const params = new URLSearchParams({ limit })
      if (tenantId) params.append('tenantId', tenantId)
      return request(`/api/admin/knowledge/graph/topics?${params}`)
    },

    /** GET /api/admin/knowledge/graph/persons */
    graphPersons: (tenantId, limit = 50) => {
      const params = new URLSearchParams({ limit })
      if (tenantId) params.append('tenantId', tenantId)
      return request(`/api/admin/knowledge/graph/persons?${params}`)
    },

    /** GET /api/admin/knowledge/graph/node/{id}/neighbors */
    graphNeighbors: (nodeId, relationshipType, depth = 1) => {
      const params = new URLSearchParams({ depth })
      if (relationshipType) params.append('relationshipType', relationshipType)
      return request(
        `/api/admin/knowledge/graph/node/${encodeURIComponent(nodeId)}/neighbors?${params}`
      )
    },
  }
}
```

- [ ] **Step 2: Build to verify no errors**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend && npm run build 2>&1 | tail -10
```

Expected: build succeeds, no errors.

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/api/knowledge.js
git commit -m "feat(admin-ui): add search + graph API methods to knowledgeApi (#26.9)"
```

---

## Task 5: Admin-UI — Search tab on KnowledgePage

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.module.css`

- [ ] **Step 1: Replace `KnowledgePage.jsx` with the full tab-switcher implementation**

Read the current file first, then write the complete replacement:

```jsx
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { knowledgeApi } from '../../api/knowledge'
import { tenantsApi } from '../../api/tenants'
import { IngestionModal } from './IngestionModal'
import styles from './KnowledgePage.module.css'

const STATUS_VARIANT = {
  COMPLETED: 'green',
  RUNNING: 'blue',
  QUEUED: 'gray',
  FAILED: 'red',
}

const JOB_COLUMNS = [
  { key: 'sourceType', label: 'Type', width: '80px' },
  { key: 'sourceRef', label: 'Source' },
  { key: 'tenantId', label: 'Tenant', width: '160px', mono: true },
  {
    key: 'status',
    label: 'Status',
    width: '110px',
    render: (_, row) => (
      <Badge variant={STATUS_VARIANT[row.status] ?? 'gray'}>{row.status}</Badge>
    ),
  },
  { key: 'chunkCount', label: 'Chunks', width: '80px', mono: true },
  { key: 'createdAt', label: 'Created', width: '180px', mono: true },
]

const SEARCH_TYPES = ['VECTOR', 'GRAPH', 'HYBRID']

export function Knowledge() {
  const { token } = useAuth()
  const request = useAuthRequest()
  const [activeTab, setActiveTab] = useState('search')

  // — Tenants (shared) —
  const [tenants, setTenants] = useState([])

  // — Ingestion Jobs tab state —
  const [jobs, setJobs] = useState([])
  const [totalPages, setTotalPages] = useState(0)
  const [page, setPage] = useState(0)
  const [showModal, setShowModal] = useState(false)
  const [jobsLoading, setJobsLoading] = useState(true)

  // — Search tab state —
  const [query, setQuery] = useState('')
  const [searchType, setSearchType] = useState('HYBRID')
  const [searchTenantId, setSearchTenantId] = useState('')
  const [results, setResults] = useState(null) // null = not searched yet
  const [searchLoading, setSearchLoading] = useState(false)
  const [searchError, setSearchError] = useState('')
  const [expandedNodeId, setExpandedNodeId] = useState(null)
  const [neighbors, setNeighbors] = useState([])

  const rawFetch = useCallback(
    (path, options = {}) => {
      const API_BASE = import.meta.env.VITE_API_BASE ?? ''
      return fetch(`${API_BASE}${path}`, {
        ...options,
        headers: { Authorization: `Bearer ${token}`, ...options.headers },
      }).then(res => {
        if (!res.ok) return Promise.reject(new Error(`${res.status} ${res.statusText}`))
        return res.json()
      })
    },
    [token]
  )

  const api = useMemo(() => knowledgeApi(request, rawFetch), [request, rawFetch])

  useEffect(() => {
    tenantsApi(request).list().then(setTenants).catch(() => {})
  }, [request])

  // Load ingestion jobs
  const loadJobs = useCallback(async () => {
    setJobsLoading(true)
    try {
      const data = await api.jobs(page, 20)
      setJobs(
        (data?.content ?? []).map(j => ({
          ...j,
          tenantId: j.tenantId
            ? (tenants.find(t => t.id === j.tenantId)?.name ?? j.tenantId)
            : 'Global',
          createdAt: j.createdAt ? new Date(j.createdAt).toLocaleString() : '\u2014',
        }))
      )
      setTotalPages(data?.totalPages ?? 0)
    } catch {
      setJobs([])
    } finally {
      setJobsLoading(false)
    }
  }, [page, tenants, api])

  useEffect(() => {
    if (activeTab === 'jobs') loadJobs()
  }, [activeTab, loadJobs])

  // Search
  async function handleSearch() {
    if (!query.trim()) return
    setSearchLoading(true)
    setSearchError('')
    setResults(null)
    setExpandedNodeId(null)
    setNeighbors([])
    try {
      const data = await api.search(
        query.trim(),
        searchType,
        searchTenantId || null,
        null,
        20
      )
      setResults(data)
    } catch (e) {
      setSearchError(e.message || 'Search failed.')
    } finally {
      setSearchLoading(false)
    }
  }

  // Entity click — expand/collapse neighbors
  async function handleEntityClick(node) {
    if (expandedNodeId === node.id) {
      setExpandedNodeId(null)
      setNeighbors([])
      return
    }
    setExpandedNodeId(node.id)
    setNeighbors([])
    try {
      const data = await api.graphNeighbors(node.id, null, 1)
      setNeighbors(Array.isArray(data) ? data : [])
    } catch {
      setNeighbors([])
    }
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter') handleSearch()
  }

  const graphResults = results?.graphResults ?? []
  const documentResults = results?.documentResults ?? []

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h2 className={styles.title}>KNOWLEDGE BASE</h2>
          <div className={styles.subtitle}>◆ knowledge-engine · port 9088</div>
        </div>
        {activeTab === 'jobs' && (
          <Button variant="primary" onClick={() => setShowModal(true)}>
            Add Document
          </Button>
        )}
      </div>

      {/* Tab switcher */}
      <div className={styles.tabRow}>
        <button
          type="button"
          className={`${styles.tab}${activeTab === 'search' ? ` ${styles.tabActive}` : ''}`}
          onClick={() => setActiveTab('search')}
        >
          Search
        </button>
        <button
          type="button"
          className={`${styles.tab}${activeTab === 'jobs' ? ` ${styles.tabActive}` : ''}`}
          onClick={() => setActiveTab('jobs')}
        >
          Ingestion Jobs
        </button>
      </div>

      {/* ── Search tab ── */}
      {activeTab === 'search' && (
        <div>
          {/* Search bar */}
          <div className={styles.searchBar}>
            <input
              className={styles.searchInput}
              type="text"
              placeholder="Search the knowledge base…"
              value={query}
              onChange={e => setQuery(e.target.value)}
              onKeyDown={handleKeyDown}
            />
            <div className={styles.typeSelector}>
              {SEARCH_TYPES.map(t => (
                <button
                  key={t}
                  type="button"
                  className={`${styles.typeSeg}${searchType === t ? ` ${styles.typeSegActive}` : ''}`}
                  onClick={() => setSearchType(t)}
                >
                  {t}
                </button>
              ))}
            </div>
            <select
              className={styles.tenantFilter}
              value={searchTenantId}
              onChange={e => setSearchTenantId(e.target.value)}
            >
              <option value="">All tenants</option>
              {tenants.map(t => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </select>
            <Button variant="primary" onClick={handleSearch} disabled={!query.trim() || searchLoading}>
              Search
            </Button>
          </div>

          {/* States */}
          {!results && !searchLoading && !searchError && (
            <p className={styles.emptyState}>Enter a query to search the knowledge base.</p>
          )}
          {searchLoading && (
            <p className={styles.emptyState}>Searching…</p>
          )}
          {searchError && (
            <p className={styles.searchError}>{searchError}</p>
          )}
          {results && !searchLoading && (graphResults.length === 0 && documentResults.length === 0) && (
            <p className={styles.emptyState}>No results found. Try a different query or search type.</p>
          )}

          {/* Results grid */}
          {results && (graphResults.length > 0 || documentResults.length > 0) && (
            <>
              <div className={styles.resultsGrid}>
                {/* Entities column */}
                <div>
                  <div className={styles.colLabel}>— ENTITIES ({graphResults.length}) —</div>
                  {graphResults.length === 0 && (
                    <p className={styles.emptyCol}>No entity results.</p>
                  )}
                  {graphResults.map(r => (
                    <div
                      key={r.node.id}
                      className={`${styles.entityCard}${expandedNodeId === r.node.id ? ` ${styles.entityCardActive}` : ''}`}
                      onClick={() => handleEntityClick(r.node)}
                      role="button"
                      tabIndex={0}
                      onKeyDown={e => e.key === 'Enter' && handleEntityClick(r.node)}
                    >
                      <div className={styles.cardMeta}>
                        <span className={styles.conceptBadge}>{r.node.conceptType}</span>
                        <span className={`${styles.scoreTag}${r.score >= 0.85 ? ` ${styles.scoreHigh}` : ''}`}>
                          {r.score.toFixed(2)}
                        </span>
                      </div>
                      <div className={styles.entityLabel}>{r.node.label}</div>
                      {r.connections.length > 0 && (
                        <div className={styles.entityConnections}>
                          {r.connections.slice(0, 3).map(c => (
                            <div key={c.id} className={styles.connectionLine}>
                              → {c.conceptType} · {c.label}
                            </div>
                          ))}
                          {r.connections.length > 3 && (
                            <div className={styles.connectionLine}>
                              +{r.connections.length - 3} more
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  ))}
                </div>

                {/* Passages column */}
                <div>
                  <div className={styles.colLabel}>— PASSAGES ({documentResults.length}) —</div>
                  {documentResults.length === 0 && (
                    <p className={styles.emptyCol}>No passage results.</p>
                  )}
                  {documentResults.map((r, i) => (
                    <div key={r.document?.id ?? i} className={styles.passageCard}>
                      <div className={styles.cardMeta}>
                        <span className={styles.passageSource}>
                          {r.document?.sourceType ?? ''} · {r.document?.sourceRef ?? ''}
                        </span>
                        <span className={`${styles.scoreTag}${r.similarity >= 0.85 ? ` ${styles.scoreHigh}` : ''}`}>
                          {r.similarity.toFixed(2)}
                        </span>
                      </div>
                      <div className={styles.passageContent}>{r.document?.content ?? ''}</div>
                      {r.document?.createdAt && (
                        <div className={styles.passageDate}>
                          {new Date(r.document.createdAt).toLocaleDateString()}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </div>

              {/* Neighbor expansion panel */}
              {expandedNodeId && (
                <div className={styles.neighborPanel}>
                  <div className={styles.neighborLabel}>
                    — {graphResults.find(r => r.node.id === expandedNodeId)?.node.label ?? ''} · NEIGHBORS —
                  </div>
                  {neighbors.length === 0 ? (
                    <span className={styles.emptyCol}>No neighbors found.</span>
                  ) : (
                    <div className={styles.neighborChips}>
                      {neighbors.map(n => (
                        <span key={n.id} className={styles.neighborChip}>
                          {n.label} <span className={styles.neighborType}>{n.conceptType}</span>
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* ── Ingestion Jobs tab ── */}
      {activeTab === 'jobs' && (
        <>
          <DataTable
            columns={JOB_COLUMNS}
            rows={jobs}
            emptyText={jobsLoading ? 'Loading…' : 'No ingestion jobs yet. Submit a URL or file.'}
          />

          {totalPages > 1 && (
            <div className={styles.pagination}>
              <button
                className={styles.pageBtn}
                disabled={page === 0}
                onClick={() => setPage(p => p - 1)}
              >
                ◂ Prev
              </button>
              <span className={styles.pageInfo}>
                {page + 1} / {totalPages}
              </span>
              <button
                className={styles.pageBtn}
                disabled={page >= totalPages - 1}
                onClick={() => setPage(p => p + 1)}
              >
                Next ▸
              </button>
            </div>
          )}

          {showModal && (
            <IngestionModal
              api={api}
              tenants={tenants}
              onClose={() => setShowModal(false)}
              onJobCreated={loadJobs}
            />
          )}
        </>
      )}
    </div>
  )
}
```

- [ ] **Step 2: Add new CSS classes to `KnowledgePage.module.css`**

Append the following to the existing file (keep all existing rules):

```css
/* ── Tab switcher ── */
.tabRow {
  display: flex;
  gap: 0;
  margin-bottom: var(--sp-5);
  border: 1px solid var(--border);
}

.tab {
  flex: 1;
  padding: 7px 0;
  background: transparent;
  border: none;
  color: var(--fg-2);
  font-family: var(--font-display);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  cursor: pointer;
  transition: background 150ms;
}

.tab:not(:last-child) {
  border-right: 1px solid var(--border);
}

.tab:hover {
  background: var(--accent-soft);
  color: var(--accent);
}

.tabActive {
  background: var(--accent-soft);
  color: var(--accent);
  box-shadow: inset 0 -2px 0 var(--accent);
}

/* ── Search bar ── */
.searchBar {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  margin-bottom: var(--sp-4);
  flex-wrap: wrap;
}

.searchInput {
  flex: 1;
  min-width: 200px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 0;
  color: var(--fg-1);
  font-family: var(--font-body);
  font-size: 13px;
  padding: 8px 10px;
}

.searchInput:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--orb-glow);
}

.typeSelector {
  display: flex;
  gap: 0;
  border: 1px solid var(--border);
}

.typeSeg {
  padding: 7px 10px;
  background: transparent;
  border: none;
  color: var(--fg-2);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.08em;
  cursor: pointer;
  transition: background 150ms;
}

.typeSeg:not(:last-child) {
  border-right: 1px solid var(--border);
}

.typeSeg:hover {
  background: var(--accent-soft);
  color: var(--accent);
}

.typeSegActive {
  background: var(--accent-soft);
  color: var(--accent);
  box-shadow: inset 0 -2px 0 var(--accent);
}

.tenantFilter {
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 0;
  color: var(--fg-2);
  font-family: var(--font-body);
  font-size: 12px;
  padding: 7px 8px;
}

/* ── Results ── */
.resultsGrid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}

.colLabel {
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--accent);
  margin-bottom: var(--sp-3);
}

.emptyCol {
  font-family: var(--font-body);
  font-size: 12px;
  color: var(--fg-3);
  font-style: italic;
}

.emptyState {
  font-family: var(--font-body);
  font-size: 13px;
  color: var(--fg-3);
  font-style: italic;
  margin: var(--sp-5) 0;
}

.searchError {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--signal-stop-fg);
  margin: var(--sp-4) 0;
}

/* ── Entity cards ── */
.entityCard {
  border: 1px solid var(--border);
  padding: var(--sp-3) var(--sp-3);
  margin-bottom: var(--sp-2);
  cursor: pointer;
  transition: border-color 150ms, background 150ms;
}

.entityCard:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.entityCardActive {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.cardMeta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--sp-1);
}

.conceptBadge {
  font-family: var(--font-mono);
  font-size: 9px;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--fg-3);
  background: var(--bg-card);
  border: 1px solid var(--border);
  padding: 1px 5px;
  border-radius: var(--r-pill);
}

.scoreTag {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
}

.scoreHigh {
  color: var(--signal-ok-fg);
}

.entityLabel {
  font-family: var(--font-body);
  font-size: 13px;
  color: var(--fg-1);
  margin-bottom: var(--sp-1);
}

.entityConnections {
  margin-top: var(--sp-1);
}

.connectionLine {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
  line-height: 1.6;
}

/* ── Passage cards ── */
.passageCard {
  border: 1px solid var(--border);
  padding: var(--sp-3);
  margin-bottom: var(--sp-2);
}

.passageSource {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
}

.passageContent {
  font-family: var(--font-body);
  font-size: 12px;
  color: var(--fg-2);
  line-height: 1.5;
  margin-top: var(--sp-2);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.passageDate {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
  margin-top: var(--sp-2);
}

/* ── Neighbor expansion panel ── */
.neighborPanel {
  border: 1px solid var(--accent);
  padding: var(--sp-3) var(--sp-4);
  background: var(--accent-soft);
  margin-bottom: var(--sp-4);
}

.neighborLabel {
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--accent);
  margin-bottom: var(--sp-2);
}

.neighborChips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.neighborChip {
  border: 1px solid var(--border);
  padding: 3px 8px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
  background: var(--bg-card);
  border-radius: 0;
}

.neighborType {
  color: var(--fg-3);
  margin-left: 4px;
}
```

- [ ] **Step 3: Build frontend**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend && npm run build 2>&1 | tail -15
```

Expected: build completes, 0 errors.

- [ ] **Step 4: Spotless (Java unchanged, just check) + commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Knowledge/
git commit -m "feat(admin-ui): add Search tab to KnowledgePage with entity/passage results and neighbor expansion (#26.9)"
```

---

## Self-Review

### Spec coverage

| Spec requirement | Task |
|---|---|
| `SearchResult<T>` record | Task 1 |
| `VectorSearchRepository.search()` returns real scores | Task 1 |
| `KnowledgeQueryService` uses real scores | Task 1 |
| `@NotBlank` on `SearchRequest.query` | Task 2 |
| `@Valid` on controller | Task 2 |
| `KnowledgeSearchProxyController` — POST /search | Task 3 |
| `KnowledgeSearchProxyController` — GET /graph/topics | Task 3 |
| `KnowledgeSearchProxyController` — GET /graph/persons | Task 3 |
| `KnowledgeSearchProxyController` — GET /graph/node/{id}/neighbors | Task 3 |
| Circuit breaker → 503 | Task 3 |
| `api.search()` in knowledge.js | Task 4 |
| `api.graphTopics()` in knowledge.js | Task 4 |
| `api.graphPersons()` in knowledge.js | Task 4 |
| `api.graphNeighbors()` in knowledge.js | Task 4 |
| Search tab with query bar + type selector + tenant filter | Task 5 |
| Side-by-side entities/passages columns | Task 5 |
| Entity click → inline neighbor expansion | Task 5 |
| Score rendering (high scores highlighted) | Task 5 |
| Ingestion Jobs tab unchanged | Task 5 |
| Empty/loading/error states | Task 5 |

All spec requirements covered.

### Type consistency

- `SearchResult<KnowledgeDocument>` defined in Task 1, returned by `VectorSearchRepository.search()`, consumed in `KnowledgeQueryService`
- `SearchResponse.DocumentResult(KnowledgeDocument document, double similarity)` — existing record, `similarity` field used in Task 5 as `r.similarity`
- `SearchResponse.GraphNodeResult(GraphNode node, List<GraphNode> connections, double score)` — existing record, `score` field used in Task 5 as `r.score`
- `GraphNode.id`, `GraphNode.label`, `GraphNode.conceptType` — all from existing record, used consistently in Task 5
- `api.search(query, searchType, tenantId, conceptTypes, limit)` — defined in Task 4, called in Task 5
- `api.graphNeighbors(nodeId, relationshipType, depth)` — defined in Task 4, called in Task 5

### Placeholder scan

No TBDs, TODOs, or vague steps. All code blocks are complete.
