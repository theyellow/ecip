package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AIProxyControllerWarmUpTest {

    @Mock private WebClient orchestratorClient;
    private AIProxyController controller;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        controller = new AIProxyController(orchestratorClient, CircuitBreakerRegistry.ofDefaults());
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    void warmUp_proxiesToOrchestrator() {
        var requestSpec = mock(WebClient.RequestBodyUriSpec.class);
        var requestBodySpec = mock(WebClient.RequestBodySpec.class);
        var responseSpec = mock(WebClient.ResponseSpec.class);

        when(orchestratorClient.post()).thenReturn(requestSpec);
        when(requestSpec.uri("/api/warm-up")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        var headersSpec = mock(WebClient.RequestHeadersSpec.class);
        when(requestBodySpec.bodyValue(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(
                        Mono.just(
                                "{\"results\":{\"EMBED\":{\"ready\":true,\"model\":\"bge-m3\",\"latencyMs\":100,\"error\":null}}}"));

        webTestClient
                .post()
                .uri("/api/ai/warm-up")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"taskTypes\":[\"EMBED\"]}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            org.assertj.core.api.Assertions.assertThat(body)
                                    .contains("\"ready\":true");
                            org.assertj.core.api.Assertions.assertThat(body).contains("bge-m3");
                        });
    }
}
