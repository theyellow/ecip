package io.emcip.admin.api.controller;

import static org.mockito.Mockito.when;

import io.emcip.admin.api.client.AuditServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {

    @Mock private AuditServiceClient client;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new AuditController(client)).build();
    }

    private JsonNode event() {
        return JsonNodeFactory.instance
                .objectNode()
                .put("id", "evt-1")
                .put("eventType", "MESSAGE_FLAGGED");
    }

    @Test
    void getEvents_defaultParams_returns200() {
        when(client.listEvents(50, null)).thenReturn(Flux.just(event()));
        webTestClient
                .get()
                .uri("/api/audit/events")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(JsonNode.class)
                .hasSize(1);
    }

    @Test
    void getEvents_withEventType_returnsFiltered() {
        when(client.listEvents(10, "MESSAGE_FLAGGED")).thenReturn(Flux.just(event()));
        webTestClient
                .get()
                .uri("/api/audit/events?size=10&eventType=MESSAGE_FLAGGED")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(JsonNode.class)
                .hasSize(1);
    }
}
