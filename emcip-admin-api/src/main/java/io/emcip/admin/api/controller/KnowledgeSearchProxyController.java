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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
    private final ObjectMapper objectMapper;

    public KnowledgeSearchProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry,
            ObjectMapper objectMapper) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge-search");
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Search the knowledge base")
    @PostMapping("/search")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> search(@RequestBody String body) {
        ObjectNode request;
        try {
            if (!(objectMapper.readTree(body) instanceof ObjectNode node)) {
                return Mono.just(ResponseEntity.badRequest().<String>build());
            }
            request = node;
        } catch (JacksonException e) {
            return Mono.just(ResponseEntity.badRequest().<String>build());
        }
        return Mono.deferContextual(
                ctx -> {
                    String tenant;
                    try {
                        JsonNode requested = request.get("tenantId");
                        tenant =
                                KnowledgeTenantResolver.resolve(
                                        ctx,
                                        requested == null || requested.isNull()
                                                ? null
                                                : requested.asString());
                    } catch (IllegalArgumentException e) {
                        return Mono.just(ResponseEntity.badRequest().<String>build());
                    }
                    if (tenant == null) {
                        request.putNull("tenantId");
                    } else {
                        request.put("tenantId", tenant);
                    }
                    return knowledgeWebClient
                            .post()
                            .uri("/api/knowledge/search")
                            .bodyValue(objectMapper.writeValueAsString(request))
                            .header("Content-Type", "application/json")
                            .retrieve()
                            .bodyToMono(String.class)
                            .map(ResponseEntity::ok)
                            .onErrorResume(
                                    e -> {
                                        log.error(
                                                "Knowledge search proxy error: {}", e.getMessage());
                                        return Mono.just(
                                                ResponseEntity.status(
                                                                HttpStatus.SERVICE_UNAVAILABLE)
                                                        .<String>build());
                                    })
                            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
                });
    }

    @Operation(summary = "List graph topic nodes")
    @GetMapping("/graph/topics")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getTopics(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return nodesByType("/api/knowledge/graph/topics", "topics", tenantId, limit);
    }

    @Operation(summary = "List graph person nodes")
    @GetMapping("/graph/persons")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getPersons(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return nodesByType("/api/knowledge/graph/persons", "persons", tenantId, limit);
    }

    @Operation(summary = "Get neighbors of a graph node")
    @GetMapping("/graph/node/{id}/neighbors")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getNeighbors(
            @PathVariable UUID id,
            @RequestParam(required = false) String relationshipType,
            @RequestParam(defaultValue = "1") int depth) {
        return Mono.deferContextual(
                        ctx -> {
                            // KNOW-F1: neighbours are read as the caller's tenant.
                            String tenant = KnowledgeTenantResolver.resolve(ctx, null);
                            return knowledgeWebClient
                                    .get()
                                    .uri(
                                            uriBuilder -> {
                                                uriBuilder.queryParam("depth", depth);
                                                if (relationshipType != null)
                                                    uriBuilder.queryParam(
                                                            "relationshipType", relationshipType);
                                                return KnowledgeTenantResolver.path(
                                                        uriBuilder,
                                                        "/api/knowledge/graph/node/{id}/neighbors",
                                                        tenant,
                                                        id);
                                            })
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .map(ResponseEntity::ok)
                                    .onErrorResume(
                                            WebClientResponseException.NotFound.class,
                                            e ->
                                                    Mono.just(
                                                            ResponseEntity.notFound()
                                                                    .<String>build()));
                        })
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

    /**
     * Graph node listings (topics, persons) run as the caller's tenant (P3.8a); a tenant-bound
     * caller's {@code tenantId} parameter is ignored.
     */
    private Mono<ResponseEntity<String>> nodesByType(
            String path, String label, UUID requestedTenant, int limit) {
        return Mono.deferContextual(
                ctx -> {
                    String tenant;
                    try {
                        tenant =
                                KnowledgeTenantResolver.resolve(
                                        ctx,
                                        requestedTenant == null
                                                ? null
                                                : requestedTenant.toString());
                    } catch (IllegalArgumentException e) {
                        return Mono.just(ResponseEntity.badRequest().<String>build());
                    }
                    return knowledgeWebClient
                            .get()
                            .uri(
                                    uriBuilder -> {
                                        uriBuilder.path(path).queryParam("limit", limit);
                                        if (tenant != null) {
                                            uriBuilder.queryParam("tenantId", tenant);
                                        }
                                        return uriBuilder.build();
                                    })
                            .retrieve()
                            .bodyToMono(String.class)
                            .map(ResponseEntity::ok)
                            .onErrorResume(
                                    e -> {
                                        log.error(
                                                "Knowledge graph/{} proxy error: {}",
                                                label,
                                                e.getMessage());
                                        return Mono.just(
                                                ResponseEntity.status(
                                                                HttpStatus.SERVICE_UNAVAILABLE)
                                                        .<String>build());
                                    })
                            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
                });
    }
}
