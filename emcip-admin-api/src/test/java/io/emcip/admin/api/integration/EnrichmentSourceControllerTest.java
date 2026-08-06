package io.emcip.admin.api.integration;

import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.integration.dto.EnrichmentSourceResponse;
import io.emcip.admin.api.integration.dto.TriggerResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class EnrichmentSourceControllerTest {

    @Mock EnrichmentSourceService service;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client =
                WebTestClient.bindToController(new EnrichmentSourceController(service))
                        .controllerAdvice(
                                new GlobalExceptionHandler(
                                        org.mockito.Mockito.mock(
                                                io.emcip.admin.api.audit.AdminAuditPublisher
                                                        .class)))
                        .build();
    }

    @Test
    void listSources_returns200() {
        EnrichmentSourceResponse src =
                new EnrichmentSourceResponse(
                        UUID.randomUUID(), "wikipedia", null, true, "0 17 3 * * *", null, null, 0L);
        when(service.listAll()).thenReturn(Flux.just(src));

        client.get()
                .uri("/api/v1/admin/integrations/sources")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(EnrichmentSourceResponse.class)
                .hasSize(1);
    }

    @Test
    void trigger_returns202WithRunId() {
        UUID sourceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(service.triggerManual(sourceId)).thenReturn(Mono.just(new TriggerResponse(runId)));

        client.post()
                .uri("/api/v1/admin/integrations/sources/{id}/trigger", sourceId)
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(TriggerResponse.class)
                .value(r -> r.runId().equals(runId));
    }
}
