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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Proxies deep research session requests to the knowledge-engine service. Admin-UI → admin-api →
 * knowledge-engine (API Gateway pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/knowledge/research")
@Tag(
        name = "Deep Research",
        description = "Proxy for knowledge-engine deep research session endpoints")
public class ResearchProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;

    public ResearchProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry,
            ObjectMapper objectMapper) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge");
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Start a new deep research session")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<String>> startResearch(@RequestBody String body) {
        return Mono.deferContextual(
                        ctx -> {
                            String forwarded;
                            try {
                                forwarded =
                                        KnowledgeTenantResolver.rewriteBody(
                                                objectMapper, body, ctx);
                            } catch (IllegalArgumentException e) {
                                return Mono.just(ResponseEntity.badRequest().<String>build());
                            }
                            return knowledgeWebClient
                                    .post()
                                    .uri("/api/knowledge/research")
                                    .bodyValue(forwarded)
                                    .header("Content-Type", "application/json")
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .map(
                                            resp ->
                                                    ResponseEntity.status(HttpStatus.CREATED)
                                                            .body(resp))
                                    .onErrorResume(
                                            e -> {
                                                log.error(
                                                        "Research start proxy error: {}",
                                                        e.getMessage());
                                                return Mono.just(
                                                        ResponseEntity.status(
                                                                        HttpStatus
                                                                                .SERVICE_UNAVAILABLE)
                                                                .<String>build());
                                            });
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get a research session by ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getSession(@PathVariable UUID id) {
        return get("/api/knowledge/research/{id}", "getSession", id);
    }

    @Operation(summary = "List research sessions for a tenant")
    @GetMapping
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> listSessions(
            @RequestParam(required = false) UUID tenantId) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenant;
                            try {
                                tenant =
                                        KnowledgeTenantResolver.resolve(
                                                ctx, tenantId == null ? null : tenantId.toString());
                            } catch (IllegalArgumentException e) {
                                return Mono.just(ResponseEntity.badRequest().<String>build());
                            }
                            return knowledgeWebClient
                                    .get()
                                    .uri(
                                            b ->
                                                    KnowledgeTenantResolver.path(
                                                            b, "/api/knowledge/research", tenant))
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .map(ResponseEntity::ok)
                                    .onErrorResume(
                                            e -> {
                                                log.error(
                                                        "Research listSessions proxy error: {}",
                                                        e.getMessage());
                                                return Mono.just(
                                                        ResponseEntity.status(
                                                                        HttpStatus
                                                                                .SERVICE_UNAVAILABLE)
                                                                .<String>build());
                                            });
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Pause a research session")
    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<String>> pauseSession(@PathVariable UUID id) {
        return post("/api/knowledge/research/{id}/pause", "pause", id);
    }

    @Operation(summary = "Resume a paused research session")
    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<String>> resumeSession(@PathVariable UUID id) {
        return post("/api/knowledge/research/{id}/resume", "resume", id);
    }

    @Operation(summary = "Get the research report for a session")
    @GetMapping("/{id}/report")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getReport(@PathVariable UUID id) {
        return get("/api/knowledge/research/{id}/report", "getReport", id);
    }

    @Operation(summary = "Download the research report as Markdown")
    @GetMapping("/{id}/report/markdown")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getReportMarkdown(@PathVariable UUID id) {
        return Mono.deferContextual(
                        ctx ->
                                knowledgeWebClient
                                        .get()
                                        .uri(
                                                b ->
                                                        KnowledgeTenantResolver.path(
                                                                b,
                                                                "/api/knowledge/research/{id}/report/markdown",
                                                                KnowledgeTenantResolver.resolve(
                                                                        ctx, null),
                                                                id))
                                        .retrieve()
                                        .toEntity(String.class))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(
                        WebClientResponseException.NotFound.class,
                        e -> Mono.just(ResponseEntity.notFound().<String>build()))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "getReportMarkdown circuit breaker open for session {}: {}",
                                    id,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        });
    }

    /** GET on an id-addressed research resource, as the caller's tenant (KNOW-F1). */
    private Mono<ResponseEntity<String>> get(String path, String action, UUID id) {
        return Mono.deferContextual(
                        ctx ->
                                knowledgeWebClient
                                        .get()
                                        .uri(
                                                b ->
                                                        KnowledgeTenantResolver.path(
                                                                b,
                                                                path,
                                                                KnowledgeTenantResolver.resolve(
                                                                        ctx, null),
                                                                id))
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .map(ResponseEntity::ok)
                                        .onErrorResume(
                                                WebClientResponseException.NotFound.class,
                                                e ->
                                                        Mono.just(
                                                                ResponseEntity.notFound()
                                                                        .<String>build()))
                                        .onErrorResume(e -> unavailable(action, id, e)))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /** POST on an id-addressed research resource, as the caller's tenant (KNOW-F1). */
    private Mono<ResponseEntity<String>> post(String path, String action, UUID id) {
        return Mono.deferContextual(
                        ctx ->
                                knowledgeWebClient
                                        .post()
                                        .uri(
                                                b ->
                                                        KnowledgeTenantResolver.path(
                                                                b,
                                                                path,
                                                                KnowledgeTenantResolver.resolve(
                                                                        ctx, null),
                                                                id))
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .map(ResponseEntity::ok)
                                        .onErrorResume(
                                                WebClientResponseException.NotFound.class,
                                                e ->
                                                        Mono.just(
                                                                ResponseEntity.notFound()
                                                                        .<String>build()))
                                        .onErrorResume(e -> unavailable(action, id, e)))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    private Mono<ResponseEntity<String>> unavailable(String action, UUID id, Throwable e) {
        log.error("Research {} proxy error sessionId={}: {}", action, id, e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<String>build());
    }
}
