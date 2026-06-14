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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
@Slf4j
public class PolicyEngineClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public PolicyEngineClient(
            @Value("${services.policy-engine.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
        this.circuitBreaker = cbRegistry.circuitBreaker("policy-engine");
        this.retry = retryRegistry.retry("policy-engine");
    }

    public Flux<JsonNode> listRules() {
        return webClient
                .get()
                .uri("/api/policy-rules")
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "policy-engine unavailable, returning empty list for listRules:"
                                            + " {}",
                                    e.getMessage());
                            return Flux.empty();
                        });
    }

    public Mono<JsonNode> createRule(JsonNode body) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            if (tenantId == null) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "A tenant must be selected before creating a"
                                                        + " policy rule"));
                            }
                            ObjectNode node = ((ObjectNode) body).deepCopy();
                            node.put("tenantId", tenantId);
                            return webClient
                                    .post()
                                    .uri("/api/policy-rules")
                                    .bodyValue(node)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> updateRule(String id, JsonNode body) {
        return webClient
                .put()
                .uri("/api/policy-rules/{id}", id)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<Void> deleteRule(String id) {
        return webClient
                .delete()
                .uri("/api/policy-rules/{id}", id)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> getDecision(String id) {
        return webClient
                .get()
                .uri("/api/policy-decisions/{id}", id)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<JsonNode> listDecisions(
            int page,
            int size,
            String decision,
            String intent,
            String from,
            String to,
            Double minConfidence) {
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
                                                        if (decision != null
                                                                && !decision.isBlank()) {
                                                            uriBuilder.queryParam(
                                                                    "decision", decision);
                                                        }
                                                        if (intent != null && !intent.isBlank()) {
                                                            uriBuilder.queryParam("intent", intent);
                                                        }
                                                        if (from != null && !from.isBlank()) {
                                                            uriBuilder.queryParam("from", from);
                                                        }
                                                        if (to != null && !to.isBlank()) {
                                                            uriBuilder.queryParam("to", to);
                                                        }
                                                        if (minConfidence != null) {
                                                            uriBuilder.queryParam(
                                                                    "minConfidence", minConfidence);
                                                        }
                                                        return uriBuilder.build();
                                                    });
                            return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                                    .retrieve()
                                    .bodyToMono(JsonNode.class);
                        })
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "policy-engine unavailable, returning empty page for"
                                            + " listDecisions: {}",
                                    e.getMessage());
                            return emptyPage();
                        });
    }

    public Mono<Void> updateDecision(String id, JsonNode body) {
        return webClient
                .put()
                .uri("/api/policy-decisions/{id}", id)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<Void> updateDecisionStatus(String id, String status) {
        return webClient
                .put()
                .uri("/api/policy-decisions/{id}", id)
                .bodyValue(java.util.Map.of("signalStatus", status))
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    private Mono<JsonNode> emptyPage() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.putArray("items");
        node.put("total", 0L);
        node.put("page", 0);
        node.put("size", 50);
        return Mono.just(node);
    }
}
