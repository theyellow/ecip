package io.emcip.llm.orchestrator.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for the knowledge-engine search endpoint. Returns document and graph results that can
 * be used to enrich LLM prompts with relevant context passages.
 *
 * <p>All network and serialization failures are caught and return an empty {@link SearchResponse}
 * so that LLM calls are never blocked by knowledge-engine unavailability.
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeEngineClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    // ── Local DTOs ────────────────────────────────────────────────────────────

    public record KnowledgeDocument(UUID id, String content, String sourceRef, String sourceType) {}

    public record DocumentResult(KnowledgeDocument document, double similarity) {}

    public record GraphNodeResult(Map<String, Object> node, double score) {}

    public record SearchResponse(
            List<GraphNodeResult> graphResults, List<DocumentResult> documentResults) {

        public static SearchResponse empty() {
            return new SearchResponse(List.of(), List.of());
        }
    }

    private CircuitBreaker circuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker("knowledge-engine");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Calls POST /api/knowledge/search on the knowledge-engine.
     *
     * @param query natural-language query
     * @param searchType one of "VECTOR", "GRAPH", "HYBRID"
     * @param tenantId optional tenant scope (null = cross-tenant)
     * @param limit max results to return
     * @return search results, or {@link SearchResponse#empty()} on any failure
     */
    public SearchResponse search(String query, String searchType, UUID tenantId, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("searchType", searchType);
        if (tenantId != null) {
            body.put("tenantId", tenantId.toString());
        }
        body.put("limit", limit);

        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            return CircuitBreaker.decorateCheckedSupplier(
                            circuitBreaker(),
                            () -> {
                                String responseJson =
                                        restClient
                                                .post()
                                                .uri("/api/knowledge/search")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .body(bodyJson)
                                                .retrieve()
                                                .body(String.class);
                                if (responseJson == null) {
                                    return SearchResponse.empty();
                                }
                                return objectMapper.readValue(responseJson, SearchResponse.class);
                            })
                    .get();
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker open for knowledge-engine, returning empty context");
            return SearchResponse.empty();
        } catch (JacksonException e) {
            log.warn("Knowledge search serialization error: {}", e.getMessage());
            return SearchResponse.empty();
        } catch (RestClientException e) {
            log.warn("Knowledge engine unreachable — skipping enrichment: {}", e.getMessage());
            return SearchResponse.empty();
        } catch (Throwable e) {
            log.error("Knowledge engine search failed: {}", e.getMessage());
            return SearchResponse.empty();
        }
    }
}
