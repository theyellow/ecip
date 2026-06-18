package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class BackfillProxyControllerTest {

    @Mock private ExchangeFunction exchangeFunction;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        WebClient knowledgeWebClient =
                WebClient.builder().exchangeFunction(exchangeFunction).build();
        BackfillProxyController controller =
                new BackfillProxyController(
                        knowledgeWebClient, CircuitBreakerRegistry.ofDefaults());
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    private void stubAccepted(String body) {
        ClientResponse response =
                ClientResponse.create(HttpStatus.ACCEPTED)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));
    }

    private void stubOk(String body) {
        ClientResponse response =
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));
    }

    @Test
    void triggerBackfill_proxiesRequestToKnowledgeEngine() {
        stubAccepted("{\"backfillId\":\"abc-123\",\"status\":\"RUNNING\"}");

        UUID accountId = UUID.randomUUID();
        webTestClient
                .post()
                .uri("/api/groups/-1001234567890/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        """
                        {"accountId":"%s","fromDate":"2026-01-01T00:00:00Z"}
                        """
                                .formatted(accountId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("abc-123");
                        });
    }

    @Test
    void getBackfillStatus_proxiesRequestToKnowledgeEngine() {
        stubOk("{\"backfillId\":\"abc-123\",\"status\":\"RUNNING\",\"processed\":42}");

        webTestClient
                .get()
                .uri("/api/groups/-1001234567890/backfill/abc-123")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("RUNNING");
                        });
    }
}
