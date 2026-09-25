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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
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
        return Mono.deferContextual(
                        ctx -> {
                            // KNOW-F1: a tenant-bound caller always lists as its own tenant.
                            String tenant =
                                    KnowledgeTenantResolver.resolve(
                                            ctx, tenantId == null ? null : tenantId.toString());
                            return knowledgeWebClient
                                    .get()
                                    .uri(
                                            b ->
                                                    KnowledgeTenantResolver.path(
                                                            b.queryParamIfPresent(
                                                                            "status",
                                                                            Optional.ofNullable(
                                                                                    status))
                                                                    .queryParamIfPresent(
                                                                            "conceptType",
                                                                            Optional.ofNullable(
                                                                                    conceptType))
                                                                    .queryParam("page", page)
                                                                    .queryParam("size", size),
                                                            "/api/resolution-review",
                                                            tenant))
                                    .retrieve()
                                    .bodyToMono(String.class);
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Merge candidate node into similar node")
    @PatchMapping("/{id}/merge")
    @PreAuthorize("hasAuthority('RESOLUTION_REVIEW_WRITE')")
    public Mono<ResponseEntity<Void>> merge(@PathVariable UUID id) {
        return Mono.deferContextual(
                        ctx ->
                                knowledgeWebClient
                                        .patch()
                                        .uri(
                                                b ->
                                                        KnowledgeTenantResolver.path(
                                                                b,
                                                                "/api/resolution-review/{id}/merge",
                                                                KnowledgeTenantResolver.resolve(
                                                                        ctx, null),
                                                                id))
                                        .retrieve()
                                        .toBodilessEntity()
                                        .map(r -> ResponseEntity.noContent().<Void>build())
                                        .onErrorResume(
                                                WebClientResponseException.NotFound.class,
                                                e ->
                                                        Mono.just(
                                                                ResponseEntity.notFound()
                                                                        .<Void>build())))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Dismiss flag without graph changes")
    @PatchMapping("/{id}/dismiss")
    @PreAuthorize("hasAuthority('RESOLUTION_REVIEW_WRITE')")
    public Mono<ResponseEntity<Void>> dismiss(@PathVariable UUID id) {
        return Mono.deferContextual(
                        ctx ->
                                knowledgeWebClient
                                        .patch()
                                        .uri(
                                                b ->
                                                        KnowledgeTenantResolver.path(
                                                                b,
                                                                "/api/resolution-review/{id}/dismiss",
                                                                KnowledgeTenantResolver.resolve(
                                                                        ctx, null),
                                                                id))
                                        .retrieve()
                                        .toBodilessEntity()
                                        .map(r -> ResponseEntity.noContent().<Void>build())
                                        .onErrorResume(
                                                WebClientResponseException.NotFound.class,
                                                e ->
                                                        Mono.just(
                                                                ResponseEntity.notFound()
                                                                        .<Void>build())))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
