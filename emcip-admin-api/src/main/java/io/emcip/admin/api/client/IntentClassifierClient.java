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
public class IntentClassifierClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public IntentClassifierClient(
            @Value("${services.intent-classifier.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
        this.circuitBreaker = cbRegistry.circuitBreaker("intent-classifier");
        this.retry = retryRegistry.retry("intent-classifier");
    }

    public Flux<JsonNode> listRules() {
        return Flux.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.get().uri("/api/intent-rules");
                            return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                                    .retrieve()
                                    .bodyToFlux(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "intent-classifier unavailable, returning empty list for"
                                            + " listRules: {}",
                                    e.getMessage());
                            return Flux.empty();
                        });
    }

    public Mono<JsonNode> createRule(JsonNode body) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.post().uri("/api/intent-rules");
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
                            var spec = webClient.put().uri("/api/intent-rules/{id}", id);
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
                            var spec = webClient.delete().uri("/api/intent-rules/{id}", id);
                            return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                                    .retrieve()
                                    .bodyToMono(Void.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> getSignalConfig() {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.get().uri("/api/intent-signal-config");
                            return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "intent-classifier unavailable for getSignalConfig: {}",
                                    e.getMessage());
                            return Mono.empty();
                        });
    }

    public Mono<JsonNode> upsertSignalConfig(JsonNode body) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec = webClient.put().uri("/api/intent-signal-config");
                            return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                                    .bodyValue(body)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
