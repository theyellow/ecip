package io.emcip.admin.api.service;

import static org.mockito.Mockito.when;

import io.emcip.admin.api.client.PolicyEngineClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

    @Mock private PolicyEngineClient policyEngineClient;

    @InjectMocks private FlagService flagService;

    private JsonNode pageNode() {
        ObjectNode page = JsonNodeFactory.instance.objectNode();
        page.putArray("items").addObject().put("id", "flag-1");
        page.put("total", 1);
        page.put("page", 0);
        page.put("size", 25);
        return page;
    }

    @Test
    void listFlags_withoutDecision_delegatesToClient() {
        when(policyEngineClient.listDecisions(0, 25, null)).thenReturn(Mono.just(pageNode()));

        StepVerifier.create(flagService.listFlags(0, 25, null)).expectNextCount(1).verifyComplete();
    }

    @Test
    void listFlags_withDecision_passesDecisionThrough() {
        when(policyEngineClient.listDecisions(0, 10, "SPAM")).thenReturn(Mono.just(pageNode()));

        StepVerifier.create(flagService.listFlags(0, 10, "SPAM"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void updateStatus_validStatus_delegatesToClient() {
        when(policyEngineClient.updateDecisionStatus("flag-1", "REVIEWED"))
                .thenReturn(Mono.empty());

        StepVerifier.create(flagService.updateStatus("flag-1", "REVIEWED")).verifyComplete();
    }
}
