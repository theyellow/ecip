package io.emcip.admin.api.client;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
@Slf4j
public class AuditServiceClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;

    public AuditServiceClient(
            @Value("${services.audit-service.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken,
            CircuitBreakerRegistry registry) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
        this.circuitBreaker = registry.circuitBreaker("audit-service");
    }

    public Mono<JsonNode> listEvents(
            int page, int size, String eventType, Instant from, Instant to) {
        return Mono.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    var spec =
                            webClient
                                    .get()
                                    .uri(
                                            uriBuilder -> {
                                                uriBuilder
                                                        .path("/api/audit/events")
                                                        .queryParam("page", page)
                                                        .queryParam("size", size);
                                                if (eventType != null && !eventType.isBlank()) {
                                                    uriBuilder.queryParam("eventType", eventType);
                                                }
                                                if (from != null) {
                                                    uriBuilder.queryParam("from", from.toString());
                                                }
                                                if (to != null) {
                                                    uriBuilder.queryParam("to", to.toString());
                                                }
                                                return uriBuilder.build();
                                            });
                    return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
                });
    }
}
