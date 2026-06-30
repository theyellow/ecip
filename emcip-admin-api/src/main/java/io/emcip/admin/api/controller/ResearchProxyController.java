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
import reactor.core.publisher.Mono;

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

    public ResearchProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge");
    }

    @Operation(summary = "Start a new deep research session")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<String>> startResearch(@RequestBody String body) {
        return knowledgeWebClient
                .post()
                .uri("/api/knowledge/research")
                .bodyValue(body)
                .header("Content-Type", "application/json")
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body(resp))
                .onErrorResume(
                        e -> {
                            log.error("Research start proxy error: {}", e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get a research session by ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getSession(@PathVariable UUID id) {
        return knowledgeWebClient
                .get()
                .uri("/api/knowledge/research/{id}", id)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Research getSession proxy error sessionId={}: {}",
                                    id,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "List research sessions for a tenant")
    @GetMapping
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> listSessions(
            @RequestParam(required = false) UUID tenantId) {
        return knowledgeWebClient
                .get()
                .uri(
                        uriBuilder -> {
                            uriBuilder.path("/api/knowledge/research");
                            if (tenantId != null) uriBuilder.queryParam("tenantId", tenantId);
                            return uriBuilder.build();
                        })
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error("Research listSessions proxy error: {}", e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Pause a research session")
    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<String>> pauseSession(@PathVariable UUID id) {
        return knowledgeWebClient
                .post()
                .uri("/api/knowledge/research/{id}/pause", id)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Research pause proxy error sessionId={}: {}",
                                    id,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Resume a paused research session")
    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<String>> resumeSession(@PathVariable UUID id) {
        return knowledgeWebClient
                .post()
                .uri("/api/knowledge/research/{id}/resume", id)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Research resume proxy error sessionId={}: {}",
                                    id,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get the research report for a session")
    @GetMapping("/{id}/report")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getReport(@PathVariable UUID id) {
        return knowledgeWebClient
                .get()
                .uri("/api/knowledge/research/{id}/report", id)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Research getReport proxy error sessionId={}: {}",
                                    id,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Download the research report as Markdown")
    @GetMapping("/{id}/report/markdown")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getReportMarkdown(@PathVariable UUID id) {
        return knowledgeWebClient
                .get()
                .uri("/api/knowledge/research/{id}/report/markdown", id)
                .retrieve()
                .toEntity(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
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
}
