package io.emcip.admin.api.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Proxies resolution review requests to the knowledge-engine service. Admin-UI → admin-api →
 * knowledge-engine (API Gateway pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/resolution-review")
@PreAuthorize("hasAuthority('RESOLUTION_REVIEW_READ')")
@Tag(name = "Resolution Review", description = "Proxy to knowledge-engine resolution review API")
public class ResolutionReviewProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;

    public ResolutionReviewProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge");
    }

    @Operation(summary = "List resolution flags")
    @GetMapping
    public Mono<String> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String conceptType,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return knowledgeWebClient
                .get()
                .uri(
                        b ->
                                b.path("/api/resolution-review")
                                        .queryParamIfPresent("status", Optional.ofNullable(status))
                                        .queryParamIfPresent(
                                                "conceptType", Optional.ofNullable(conceptType))
                                        .queryParamIfPresent(
                                                "tenantId", Optional.ofNullable(tenantId))
                                        .queryParam("page", page)
                                        .queryParam("size", size)
                                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Merge candidate node into similar node")
    @PatchMapping("/{id}/merge")
    @PreAuthorize("hasAuthority('RESOLUTION_REVIEW_WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> merge(@PathVariable UUID id) {
        return knowledgeWebClient
                .patch()
                .uri("/api/resolution-review/{id}/merge", id)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Dismiss flag without graph changes")
    @PatchMapping("/{id}/dismiss")
    @PreAuthorize("hasAuthority('RESOLUTION_REVIEW_WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> dismiss(@PathVariable UUID id) {
        return knowledgeWebClient
                .patch()
                .uri("/api/resolution-review/{id}/dismiss", id)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
