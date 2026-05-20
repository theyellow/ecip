package io.emcip.admin.api.client;

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

    public AuditServiceClient(
            @Value("${services.audit-service.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
    }

    public Mono<JsonNode> listEvents(int page, int size, String eventType) {
        return webClient
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
                            return uriBuilder.build();
                        })
                .retrieve()
                .bodyToMono(JsonNode.class);
    }
}
