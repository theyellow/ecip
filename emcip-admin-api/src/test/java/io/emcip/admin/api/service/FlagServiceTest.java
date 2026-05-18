package io.emcip.admin.api.service;

import static org.mockito.Mockito.when;

import io.emcip.admin.api.client.PolicyEngineClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

    @Mock private PolicyEngineClient policyEngineClient;

    @InjectMocks private FlagService flagService;

    private JsonNode flag() {
        return JsonNodeFactory.instance.objectNode().put("id", "flag-1").put("signalStatus", "NEW");
    }

    @Test
    void listFlags_withoutDecision_callsListFlags() {
        when(policyEngineClient.listFlags(25)).thenReturn(Flux.just(flag()));

        StepVerifier.create(flagService.listFlags(25, null)).expectNextCount(1).verifyComplete();
    }

    @Test
    void listFlags_withDecision_callsListDecisionsByType() {
        when(policyEngineClient.listDecisionsByType("SPAM", 10)).thenReturn(Flux.just(flag()));

        StepVerifier.create(flagService.listFlags(10, "SPAM")).expectNextCount(1).verifyComplete();
    }

    @Test
    void updateStatus_missingStatus_returnsError() {
        StepVerifier.create(flagService.updateStatus("flag-1", Map.of()))
                .expectErrorMatches(
                        e ->
                                e instanceof IllegalArgumentException
                                        && "status is required".equals(e.getMessage()))
                .verify();
    }

    @Test
    void updateStatus_validStatus_delegatesToClient() {
        when(policyEngineClient.updateDecisionStatus("flag-1", "REVIEWED"))
                .thenReturn(Mono.empty());

        StepVerifier.create(flagService.updateStatus("flag-1", Map.of("status", "REVIEWED")))
                .verifyComplete();
    }
}
