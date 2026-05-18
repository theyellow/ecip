package io.emcip.admin.api.controller;

import static org.mockito.Mockito.when;

import io.emcip.admin.api.service.FlagService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@ExtendWith(MockitoExtension.class)
class FlagControllerTest {

    @Mock private FlagService flagService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new FlagController(flagService)).build();
    }

    private JsonNode flag() {
        return JsonNodeFactory.instance.objectNode().put("id", "flag-1").put("signalStatus", "NEW");
    }

    @Test
    void getFlags_withoutDecision_callsListFlags() {
        when(flagService.listFlags(50, null)).thenReturn(Flux.just(flag()));
        webTestClient
                .get()
                .uri("/api/flags")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(JsonNode.class)
                .hasSize(1);
    }

    @Test
    void getFlags_withDecision_callsListDecisionsByType() {
        when(flagService.listFlags(50, "SPAM")).thenReturn(Flux.just(flag()));
        webTestClient
                .get()
                .uri("/api/flags?decision=SPAM")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(JsonNode.class)
                .hasSize(1);
    }

    @Test
    void updateStatus_returns204() {
        when(flagService.updateStatus("flag-1", Map.of("status", "REVIEWED")))
                .thenReturn(Mono.empty());
        webTestClient
                .patch()
                .uri("/api/flags/flag-1/status")
                .bodyValue(Map.of("status", "REVIEWED"))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void updateStatus_missingStatus_returnsError() {
        when(flagService.updateStatus("flag-1", Map.of()))
                .thenReturn(Mono.error(new IllegalArgumentException("status is required")));
        webTestClient
                .patch()
                .uri("/api/flags/flag-1/status")
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .is5xxServerError();
    }
}
