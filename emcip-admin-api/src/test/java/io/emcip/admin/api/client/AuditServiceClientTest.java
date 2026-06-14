package io.emcip.admin.api.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class AuditServiceClientTest {

    private MockWebServer server;
    private AuditServiceClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client =
                new AuditServiceClient(
                        server.url("/").toString(),
                        "test-token",
                        CircuitBreakerRegistry.ofDefaults(),
                        RetryRegistry.ofDefaults());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void listEvents_returnsResponse() {
        server.enqueue(
                new MockResponse.Builder()
                        .body("{\"items\":[],\"total\":0,\"page\":0,\"size\":50}")
                        .addHeader("Content-Type", "application/json")
                        .build());

        StepVerifier.create(client.listEvents(0, 50, null, null, null))
                .assertNext(
                        node -> {
                            assertThat(node.get("total").asInt()).isEqualTo(0);
                            assertThat(node.get("items").size()).isEqualTo(0);
                        })
                .verifyComplete();
    }

    @Test
    void listEvents_retriesOnFailureThenSucceeds() {
        server.enqueue(new MockResponse.Builder().code(503).build());
        server.enqueue(
                new MockResponse.Builder()
                        .body("{\"items\":[],\"total\":5,\"page\":0,\"size\":50}")
                        .addHeader("Content-Type", "application/json")
                        .build());

        StepVerifier.create(client.listEvents(0, 50, null, null, null))
                .assertNext(node -> assertThat(node.get("total").asInt()).isEqualTo(5))
                .verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void listEvents_fallsBackToEmptyPageWhenAllRetriesFail() {
        server.enqueue(new MockResponse.Builder().code(503).build());
        server.enqueue(new MockResponse.Builder().code(503).build());
        server.enqueue(new MockResponse.Builder().code(503).build());

        StepVerifier.create(client.listEvents(0, 50, null, null, null))
                .assertNext(
                        node -> {
                            assertThat(node.get("total").asInt()).isEqualTo(0);
                            assertThat(node.get("items").size()).isEqualTo(0);
                        })
                .verifyComplete();
    }
}
