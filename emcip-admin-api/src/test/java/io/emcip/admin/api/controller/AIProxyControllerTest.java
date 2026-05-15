package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
class AIProxyControllerTest {

    @Mock private ExchangeFunction exchangeFunction;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        WebClient orchestratorClient =
                WebClient.builder().exchangeFunction(exchangeFunction).build();
        AIProxyController controller = new AIProxyController(orchestratorClient);
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

    private void stubCreated(String body) {
        ClientResponse response =
                ClientResponse.create(HttpStatus.CREATED)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));
    }

    private void stubNoContent() {
        ClientResponse response = ClientResponse.create(HttpStatus.NO_CONTENT).build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));
    }

    @Test
    void listModels_returns200() {
        stubOk("[{}]");
        webTestClient.get().uri("/api/ai/models").exchange().expectStatus().isOk();
    }

    @Test
    void createModel_returns201() {
        stubCreated("{\"id\":\"m1\"}");
        webTestClient
                .post()
                .uri("/api/ai/models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"gpt-4\"}")
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void updateModel_returns200() {
        stubOk("{\"id\":\"m1\"}");
        webTestClient
                .put()
                .uri("/api/ai/models/m1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"gpt-4-turbo\"}")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void deleteModel_returns204() {
        stubNoContent();
        webTestClient.delete().uri("/api/ai/models/m1").exchange().expectStatus().isNoContent();
    }

    @Test
    void listTemplates_returns200() {
        stubOk("[{}]");
        webTestClient.get().uri("/api/ai/templates").exchange().expectStatus().isOk();
    }

    @Test
    void createTemplate_returns201() {
        stubCreated("{\"id\":\"t1\"}");
        webTestClient
                .post()
                .uri("/api/ai/templates")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"default\"}")
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void updateTemplate_returns200() {
        stubOk("{\"id\":\"t1\"}");
        webTestClient
                .put()
                .uri("/api/ai/templates/t1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"updated\"}")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void deleteTemplate_returns204() {
        stubNoContent();
        webTestClient.delete().uri("/api/ai/templates/t1").exchange().expectStatus().isNoContent();
    }
}
