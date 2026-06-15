package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class CostsProxyControllerTest {

    @Mock private ExchangeFunction exchangeFunction;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        WebClient orchestratorClient =
                WebClient.builder().exchangeFunction(exchangeFunction).build();
        CostsProxyController controller =
                new CostsProxyController(orchestratorClient, CircuitBreakerRegistry.ofDefaults());
        webTestClient = WebTestClient.bindToController(controller).build();
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
    void totals_proxiesToOrchestrator() {
        stubOk("{\"total\":42.0}");
        webTestClient
                .get()
                .uri("/api/costs/totals?from=2026-01-01&to=2026-01-31")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("{\"total\":42.0}");
    }

    @Test
    void byModel_proxiesToOrchestrator() {
        stubOk("[{\"model\":\"gpt-4\",\"cost\":10.0}]");
        webTestClient
                .get()
                .uri("/api/costs/by-model?from=2026-01-01&to=2026-01-31")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("[{\"model\":\"gpt-4\",\"cost\":10.0}]");
    }

    @Test
    void byDay_proxiesToOrchestrator() {
        stubOk("[{\"date\":\"2026-01-01\",\"cost\":5.0}]");
        webTestClient
                .get()
                .uri("/api/costs/by-day?from=2026-01-01&to=2026-01-31")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("[{\"date\":\"2026-01-01\",\"cost\":5.0}]");
    }
}
