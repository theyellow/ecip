package io.emcip.admin.api.client;

import io.emcip.common.tenant.ReactorTenantContext;
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

    public ModerationServiceClient(
            @Value("${services.moderation-service.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
    }

    public Flux<JsonNode> listRules() {
        return Flux.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    var spec = webClient.get().uri("/api/moderation-rules");
                    return (tenantId != null ? spec.header("X-Tenant-Id", tenantId) : spec)
                            .retrieve()
                            .bodyToFlux(JsonNode.class);
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
                });
    }

    public Mono<JsonNode> updateRule(String id, JsonNode body) {
        return webClient
                .put()
                .uri("/api/moderation-rules/{id}", id)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<Void> deleteRule(String id) {
        return webClient
                .delete()
                .uri("/api/moderation-rules/{id}", id)
                .retrieve()
                .bodyToMono(Void.class);
    }
}
