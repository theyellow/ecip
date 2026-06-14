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
import tools.jackson.databind.node.JsonNodeFactory;

class ModerationServiceClientTest {

    private MockWebServer server;
    private ModerationServiceClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client =
                new ModerationServiceClient(
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
    void listRules_returnsResponse() {
        server.enqueue(
                new MockResponse.Builder()
                        .body("[{\"id\":\"1\",\"name\":\"rule1\"}]")
                        .addHeader("Content-Type", "application/json")
                        .build());

        StepVerifier.create(client.listRules().collectList())
                .assertNext(list -> assertThat(list).hasSize(1))
                .verifyComplete();
    }

    @Test
    void listRules_fallsBackToEmptyListWhenAllRetriesFail() {
        server.enqueue(new MockResponse.Builder().code(503).build());
        server.enqueue(new MockResponse.Builder().code(503).build());
        server.enqueue(new MockResponse.Builder().code(503).build());

        StepVerifier.create(client.listRules().collectList())
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
    }

    @Test
    void createRule_doesNotFallback_propagatesError() {
        server.enqueue(new MockResponse.Builder().code(503).build());
        server.enqueue(new MockResponse.Builder().code(503).build());
        server.enqueue(new MockResponse.Builder().code(503).build());

        StepVerifier.create(
                        client.createRule(
                                JsonNodeFactory.instance.objectNode().put("name", "test")))
                .verifyError();
    }
}
