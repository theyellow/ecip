package io.emcip.admin.api.client;

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

    public PolicyEngineClient(
            @Value("${services.policy-engine.url}") String baseUrl,
            @Value("${admin.service-token}") String serviceToken) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
    }

    public Flux<JsonNode> listRules() {
        return webClient.get().uri("/api/policy-rules").retrieve().bodyToFlux(JsonNode.class);
    }

    public Mono<JsonNode> createRule(JsonNode body) {
        return webClient
                .post()
                .uri("/api/policy-rules")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> updateRule(String id, JsonNode body) {
        return webClient
                .put()
                .uri("/api/policy-rules/{id}", id)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<Void> deleteRule(String id) {
        return webClient
                .delete()
                .uri("/api/policy-rules/{id}", id)
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Flux<JsonNode> listDecisions() {
        return webClient.get().uri("/api/policy-decisions").retrieve().bodyToFlux(JsonNode.class);
    }

    public Flux<JsonNode> listFlags(int size) {
        return webClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/policy-decisions")
                                        .queryParam("size", size)
                                        .build())
                .retrieve()
                .bodyToFlux(JsonNode.class);
    }

    public Flux<JsonNode> listDecisionsByType(String decision, int size) {
        return webClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/policy-decisions")
                                        .queryParam("decision", decision)
                                        .queryParam("size", size)
                                        .build())
                .retrieve()
                .bodyToFlux(JsonNode.class);
    }

    public Mono<Void> updateDecision(String id, JsonNode body) {
        return webClient
                .put()
                .uri("/api/policy-decisions/{id}", id)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Mono<Void> updateDecisionStatus(String id, String status) {
        return webClient
                .put()
                .uri("/api/policy-decisions/{id}", id)
                .bodyValue(java.util.Map.of("signalStatus", status))
                .retrieve()
                .bodyToMono(Void.class);
    }
}
