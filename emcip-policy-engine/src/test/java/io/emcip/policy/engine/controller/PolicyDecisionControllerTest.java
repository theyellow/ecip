package io.emcip.policy.engine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

class PolicyDecisionControllerTest {

    private PolicyDecisionRepository repository;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        repository = mock(PolicyDecisionRepository.class);
        PolicyDecisionController controller = new PolicyDecisionController(repository);
        client = WebTestClient.bindToController(controller).build();
    }

    private PolicyDecision decision(String id) {
        PolicyDecision d = new PolicyDecision();
        d.setId(id);
        d.setDecision("BLOCK");
        d.setTimestamp(Instant.now());
        d.setSignalStatus("PENDING");
        return d;
    }

    @Test
    void getFlags_returnsDecisions() {
        when(repository.findTopByDecisionNotOrderByTimestampDesc(anyString(), anyInt()))
                .thenReturn(List.of(decision("d1")));
        client.get()
                .uri("/api/policy-decisions?size=10")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(PolicyDecision.class)
                .hasSize(1);
    }

    @Test
    void updateStatus_returns204() {
        when(repository.updateSignalStatus(any(), any())).thenReturn(1);
        client.put()
                .uri("/api/policy-decisions/d1")
                .bodyValue(Map.of("signalStatus", "REVIEWED"))
                .exchange()
                .expectStatus()
                .isNoContent();
    }
}
