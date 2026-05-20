package io.emcip.admin.api.service;

import io.emcip.admin.api.client.PolicyEngineClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class FlagService {

    private final PolicyEngineClient policyEngineClient;

    public Mono<JsonNode> listFlags(int page, int size, String decision) {
        return policyEngineClient.listDecisions(page, size, decision);
    }

    public Mono<Void> updateStatus(String id, String status) {
        return policyEngineClient.updateDecisionStatus(id, status);
    }
}
