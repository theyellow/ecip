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
class KnowledgeSearchProxyControllerTest {

    @Mock private ExchangeFunction exchangeFunction;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        WebClient knowledgeWebClient =
                WebClient.builder().exchangeFunction(exchangeFunction).build();
        KnowledgeSearchProxyController controller =
                new KnowledgeSearchProxyController(
                        knowledgeWebClient, CircuitBreakerRegistry.ofDefaults());
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

    private void stubError() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));
    }

    @Test
    void search_proxiesPostRequest() {
        stubOk("{\"graphResults\":[],\"documentResults\":[]}");

        webTestClient
                .post()
                .uri("/api/admin/knowledge/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"AI policy\",\"searchType\":\"HYBRID\",\"limit\":20}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("documentResults");
                        });
    }

    @Test
    void getTopics_proxiesGetRequest() {
        stubOk(
                "[{\"id\":\""
                        + UUID.randomUUID()
                        + "\",\"conceptType\":\"Topic\",\"label\":\"AI\"}]");

        webTestClient
                .get()
                .uri("/api/admin/knowledge/graph/topics?limit=10")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("Topic");
                        });
    }

    @Test
    void getPersons_proxiesGetRequest() {
        stubOk(
                "[{\"id\":\""
                        + UUID.randomUUID()
                        + "\",\"conceptType\":\"Person\",\"label\":\"Alice\"}]");

        webTestClient
                .get()
                .uri("/api/admin/knowledge/graph/persons?limit=10")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("Person");
                        });
    }

    @Test
    void getNeighbors_proxiesGetRequest() {
        UUID nodeId = UUID.randomUUID();
        stubOk("[]");

        webTestClient
                .get()
                .uri("/api/admin/knowledge/graph/node/" + nodeId + "/neighbors")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void search_circuitBreaker_returns503OnError() {
        stubError();

        webTestClient
                .post()
                .uri("/api/admin/knowledge/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"test\",\"searchType\":\"VECTOR\",\"limit\":10}")
                .exchange()
                .expectStatus()
                .isEqualTo(503);
    }
}
