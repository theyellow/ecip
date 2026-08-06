package io.emcip.admin.api.controller;

import static org.mockito.Mockito.when;

import io.emcip.admin.api.client.AuditServiceClient;
import io.emcip.admin.api.config.GlobalExceptionHandler;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {

    @Mock private AuditServiceClient auditServiceClient;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient =
                WebTestClient.bindToController(new AuditController(auditServiceClient))
                        .controllerAdvice(
                                new GlobalExceptionHandler(
                                        org.mockito.Mockito.mock(
                                                io.emcip.admin.api.audit.AdminAuditPublisher
                                                        .class)))
                        .build();
    }

    @Test
    void getEvents_returnsPageResponse() {
        ObjectNode page = JsonNodeFactory.instance.objectNode();
        page.putArray("items");
        page.put("total", 0L);
        page.put("page", 0);
        page.put("size", 50);
        when(auditServiceClient.listEvents(0, 50, null, null, null)).thenReturn(Mono.just(page));

        webTestClient
                .get()
                .uri("/api/audit/events")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(0);
    }

    @Test
    void getEvents_forwardsFromAndToParams() {
        ObjectNode page = JsonNodeFactory.instance.objectNode();
        page.putArray("items");
        page.put("total", 0L);
        page.put("page", 0);
        page.put("size", 50);
        Instant from = Instant.parse("2026-06-14T00:00:00Z");
        Instant to = Instant.parse("2026-06-14T23:59:59Z");
        when(auditServiceClient.listEvents(0, 50, null, from, to)).thenReturn(Mono.just(page));

        webTestClient
                .get()
                .uri("/api/audit/events?from=2026-06-14T00:00:00Z&to=2026-06-14T23:59:59Z")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(0);
    }
}
