package io.emcip.admin.api.client;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
@Slf4j
public class PolicyEngineClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;

    public PolicyEngineClient(
            @Value("${services.policy-engine.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken,
            CircuitBreakerRegistry registry) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
        this.circuitBreaker = registry.circuitBreaker("policy-engine");
    }

    public Flux<JsonNode> listRules() {
        return webClient
                .get()
                .uri("/api/policy-rules")
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> createRule(JsonNode body) {
        return webClient
                .post()
                .uri("/api/policy-rules")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> updateRule(String id, JsonNode body) {
        return webClient
                .put()
                .uri("/api/policy-rules/{id}", id)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<Void> deleteRule(String id) {
        return webClient
                .delete()
                .uri("/api/policy-rules/{id}", id)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> listDecisions(int page, int size, String decision) {
        return Mono.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    var spec =
                            webClient
                                    .get()
                                    .uri(
                                            uriBuilder -> {
                                                uriBuilder
                                                        .path("/api/policy-decisions")
                                                        .queryParam("page", page)
                                                        .queryParam("size", size);
                                                if (decision != null && !decision.isBlank()) {
                                                    uriBuilder.queryParam("decision", decision);
                                                }
                                                return uriBuilder.build();
                                            });
                    return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
                });
    }

    public Mono<Void> updateDecision(String id, JsonNode body) {
        return webClient
                .put()
                .uri("/api/policy-decisions/{id}", id)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<Void> updateDecisionStatus(String id, String status) {
        return webClient
                .put()
                .uri("/api/policy-decisions/{id}", id)
                .bodyValue(java.util.Map.of("signalStatus", status))
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
