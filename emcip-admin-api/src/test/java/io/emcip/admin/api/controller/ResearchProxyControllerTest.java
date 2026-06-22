package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
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
class ResearchProxyControllerTest {

    @Mock private ExchangeFunction exchangeFunction;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        WebClient knowledgeWebClient =
                WebClient.builder().exchangeFunction(exchangeFunction).build();
        ResearchProxyController controller =
                new ResearchProxyController(
                        knowledgeWebClient, CircuitBreakerRegistry.ofDefaults());
        webTestClient =
                WebTestClient.bindToController(controller)
                        .controllerAdvice(new GlobalExceptionHandler())
                        .build();
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

    private void stubError() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));
    }

    @Test
    void startResearch_proxiesPostRequestAndReturns201() {
        stubCreated("{\"sessionId\":\"" + UUID.randomUUID() + "\",\"status\":\"RUNNING\"}");

        webTestClient
                .post()
                .uri("/api/admin/knowledge/research")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"query\":\"AI policy trends\",\"tenantId\":\""
                                + UUID.randomUUID()
                                + "\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("sessionId");
                        });
    }

    @Test
    void getSession_proxiesGetRequestAndReturns200() {
        UUID sessionId = UUID.randomUUID();
        stubOk("{\"sessionId\":\"" + sessionId + "\",\"status\":\"COMPLETE\"}");

        webTestClient
                .get()
                .uri("/api/admin/knowledge/research/" + sessionId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("sessionId");
                        });
    }

    @Test
    void listSessions_proxiesGetRequestWithTenantId() {
        UUID tenantId = UUID.randomUUID();
        stubOk("[{\"sessionId\":\"" + UUID.randomUUID() + "\",\"status\":\"COMPLETE\"}]");

        webTestClient
                .get()
                .uri("/api/admin/knowledge/research?tenantId=" + tenantId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("sessionId");
                        });
    }

    @Test
    void pauseSession_proxiesPostRequest() {
        UUID sessionId = UUID.randomUUID();
        stubOk("{\"sessionId\":\"" + sessionId + "\",\"status\":\"PAUSED\"}");

        webTestClient
                .post()
                .uri("/api/admin/knowledge/research/" + sessionId + "/pause")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void resumeSession_proxiesPostRequest() {
        UUID sessionId = UUID.randomUUID();
        stubOk("{\"sessionId\":\"" + sessionId + "\",\"status\":\"RUNNING\"}");

        webTestClient
                .post()
                .uri("/api/admin/knowledge/research/" + sessionId + "/resume")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void startResearch_circuitBreaker_returns503OnError() {
        stubError();

        webTestClient
                .post()
                .uri("/api/admin/knowledge/research")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"test\",\"tenantId\":\"" + UUID.randomUUID() + "\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(503);
    }

    @Test
    void getReport_proxiesGetToKnowledgeEngine() {
        UUID sessionId = UUID.randomUUID();
        stubOk("{\"id\":\"" + UUID.randomUUID() + "\",\"template\":\"TOPIC\"}");

        webTestClient
                .get()
                .uri("/api/admin/knowledge/research/" + sessionId + "/report")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("template");
                        });
    }

    @Test
    void getReportMarkdown_proxiesGetToKnowledgeEngine() {
        UUID sessionId = UUID.randomUUID();
        stubOk("## Executive Summary\nTest content.");

        webTestClient
                .get()
                .uri("/api/admin/knowledge/research/" + sessionId + "/report/markdown")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("Executive Summary");
                        });
    }
}
