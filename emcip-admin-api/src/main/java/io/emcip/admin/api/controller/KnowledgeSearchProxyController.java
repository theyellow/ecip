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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Proxies knowledge search and graph requests to the knowledge-engine service. Admin-UI → admin-api
 * → knowledge-engine (API Gateway pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/knowledge")
@Tag(
        name = "Knowledge Search",
        description = "Proxy for knowledge-engine search and graph endpoints")
public class KnowledgeSearchProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;

    public KnowledgeSearchProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge-search");
    }

    @Operation(summary = "Search the knowledge base")
    @PostMapping("/search")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> search(@RequestBody String body) {
        return knowledgeWebClient
                .post()
                .uri("/api/knowledge/search")
                .bodyValue(body)
                .header("Content-Type", "application/json")
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error("Knowledge search proxy error: {}", e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "List graph topic nodes")
    @GetMapping("/graph/topics")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getTopics(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return knowledgeWebClient
                .get()
                .uri(
                        uriBuilder -> {
                            uriBuilder
                                    .path("/api/knowledge/graph/topics")
                                    .queryParam("limit", limit);
                            if (tenantId != null) uriBuilder.queryParam("tenantId", tenantId);
                            return uriBuilder.build();
                        })
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error("Knowledge graph/topics proxy error: {}", e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "List graph person nodes")
    @GetMapping("/graph/persons")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getPersons(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return knowledgeWebClient
                .get()
                .uri(
                        uriBuilder -> {
                            uriBuilder
                                    .path("/api/knowledge/graph/persons")
                                    .queryParam("limit", limit);
                            if (tenantId != null) uriBuilder.queryParam("tenantId", tenantId);
                            return uriBuilder.build();
                        })
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error("Knowledge graph/persons proxy error: {}", e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get neighbors of a graph node")
    @GetMapping("/graph/node/{id}/neighbors")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getNeighbors(
            @PathVariable UUID id,
            @RequestParam(required = false) String relationshipType,
            @RequestParam(defaultValue = "1") int depth) {
        return knowledgeWebClient
                .get()
                .uri(
                        uriBuilder -> {
                            uriBuilder
                                    .path("/api/knowledge/graph/node/{id}/neighbors")
                                    .queryParam("depth", depth);
                            if (relationshipType != null)
                                uriBuilder.queryParam("relationshipType", relationshipType);
                            return uriBuilder.build(id);
                        })
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Knowledge graph/neighbors proxy error nodeId={}: {}",
                                    id,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
