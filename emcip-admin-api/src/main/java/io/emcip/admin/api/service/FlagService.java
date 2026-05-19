package io.emcip.admin.api.service;

import io.emcip.admin.api.client.PolicyEngineClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class FlagService {

    private final PolicyEngineClient policyEngineClient;

    public Flux<JsonNode> listFlags(int size, String decision) {
        if (decision != null && !decision.isBlank()) {
            return policyEngineClient.listDecisionsByType(decision, size);
        }
        return policyEngineClient.listFlags(size);
    }

    public Mono<Void> updateStatus(String id, String status) {
        return policyEngineClient.updateDecisionStatus(id, status);
    }
}
