package io.emcip.admin.api.client;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
@Slf4j
public class ModerationServiceClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ModerationServiceClient(
            @Value("${services.moderation-service.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
        this.circuitBreaker = cbRegistry.circuitBreaker("moderation-service");
        this.retry = retryRegistry.retry("moderation-service");
    }

    public Flux<JsonNode> listRules() {
        return Flux.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.get().uri("/api/moderation-rules");
                            return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                                    .retrieve()
                                    .bodyToFlux(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "moderation-service unavailable, returning empty list for"
                                            + " listRules: {}",
                                    e.getMessage());
                            return Flux.empty();
                        });
    }

    public Mono<JsonNode> createRule(JsonNode body) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.post().uri("/api/moderation-rules");
                            return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                                    .bodyValue(body)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> updateRule(String id, JsonNode body) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.put().uri("/api/moderation-rules/{id}", id);
                            return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                                    .bodyValue(body)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<Void> deleteRule(String id) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.delete().uri("/api/moderation-rules/{id}", id);
                            return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                                    .retrieve()
                                    .bodyToMono(Void.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
