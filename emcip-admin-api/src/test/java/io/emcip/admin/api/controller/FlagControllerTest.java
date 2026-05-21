package io.emcip.admin.api.controller;

import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.service.FlagService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class FlagControllerTest {

    @Mock private FlagService flagService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient =
                WebTestClient.bindToController(new FlagController(flagService))
                        .controllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private JsonNode pageNode() {
        ObjectNode page = JsonNodeFactory.instance.objectNode();
        page.putArray("items").addObject().put("id", "flag-1");
        page.put("total", 1);
        page.put("page", 0);
        page.put("size", 50);
        return page;
    }

    @Test
    void getFlags_returnsPageResponse() {
        when(flagService.listFlags(0, 50, null)).thenReturn(Mono.just(pageNode()));
        webTestClient
                .get()
                .uri("/api/flags")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);
    }

    @Test
    void getFlags_sizeCapAt200() {
        when(flagService.listFlags(0, 200, null)).thenReturn(Mono.just(pageNode()));
        webTestClient.get().uri("/api/flags?size=999").exchange().expectStatus().isOk();
    }

    @Test
    void updateStatus_returns204() {
        when(flagService.updateStatus("flag-1", "REVIEWED")).thenReturn(Mono.empty());
        webTestClient
                .patch()
                .uri("/api/flags/flag-1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", "REVIEWED"))
                .exchange()
                .expectStatus()
                .isNoContent();
    }
}
