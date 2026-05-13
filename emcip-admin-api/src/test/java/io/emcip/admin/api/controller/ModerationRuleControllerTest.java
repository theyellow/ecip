package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.client.ModerationServiceClient;
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
class ModerationRuleControllerTest {

    @Mock private ModerationServiceClient client;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient =
                WebTestClient.bindToController(new ModerationRuleController(client)).build();
    }

    private JsonNode rule() {
        return JsonNodeFactory.instance.objectNode().put("id", "rule-1").put("name", "no spam");
    }

    @Test
    void list_returns200() {
        when(client.listRules()).thenReturn(Flux.just(rule()));
        webTestClient
                .get()
                .uri("/api/moderation-rules")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(JsonNode.class)
                .hasSize(1);
    }

    @Test
    void create_returns201() {
        when(client.createRule(any())).thenReturn(Mono.just(rule()));
        webTestClient
                .post()
                .uri("/api/moderation-rules")
                .bodyValue(rule())
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void update_returns200() {
        when(client.updateRule(eq("rule-1"), any())).thenReturn(Mono.just(rule()));
        webTestClient
                .put()
                .uri("/api/moderation-rules/rule-1")
                .bodyValue(rule())
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void delete_returns204() {
        when(client.deleteRule("rule-1")).thenReturn(Mono.empty());
        webTestClient
                .delete()
                .uri("/api/moderation-rules/rule-1")
                .exchange()
                .expectStatus()
                .isNoContent();
    }
}
