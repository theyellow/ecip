package io.emcip.admin.api.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * TENANT-AUDIT P-1 / F-2: policy-engine can only enforce a tenant it is told about. Before this,
 * only createRule and listDecisions sent one, so every by-id call acted on any tenant's data.
 */
class PolicyEngineClientTenantTest {

    private static final String TENANT = UUID.randomUUID().toString();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private MockWebServer server;
    private PolicyEngineClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client =
                new PolicyEngineClient(
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
    void everyCallSendsTheBoundTenant() throws Exception {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("name", "r");
        Map<String, Supplier<Mono<?>>> calls = new LinkedHashMap<>();
        calls.put("listRules", () -> client.listRules().collectList());
        calls.put("createRule", () -> client.createRule(body));
        calls.put("updateRule", () -> client.updateRule("r1", body, "me"));
        calls.put("deleteRule", () -> client.deleteRule("r1"));
        calls.put("dryRun", () -> client.dryRun(body));
        calls.put("getRuleHistory", () -> client.getRuleHistory("r1").collectList());
        calls.put("getDecision", () -> client.getDecision("d1"));
        calls.put("listDecisions", () -> client.listDecisions(0, 50, null, null, null, null, null));
        calls.put("updateDecision", () -> client.updateDecision("d1", body));
        calls.put("updateDecisionStatus", () -> client.updateDecisionStatus("d1", "RESOLVED"));

        for (Map.Entry<String, Supplier<Mono<?>>> call : calls.entrySet()) {
            server.enqueue(
                    new MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/json")
                            .body("{}")
                            .build());

            StepVerifier.create(
                            call.getValue()
                                    .get()
                                    .contextWrite(
                                            ctx -> ReactorTenantContext.withTenant(ctx, TENANT)))
                    .thenConsumeWhile(x -> true)
                    .expectComplete()
                    .verify(TIMEOUT);

            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request).as(call.getKey()).isNotNull();
            assertThat(request.getHeaders().get(TenantContext.HEADER_NAME))
                    .as(call.getKey())
                    .isEqualTo(TENANT);
        }
    }

    @Test
    void noBoundTenantSendsNoHeader() throws Exception {
        server.enqueue(
                new MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("{}")
                        .build());

        StepVerifier.create(client.getDecision("d1"))
                .expectNextCount(1)
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(
                        server.takeRequest(5, TimeUnit.SECONDS)
                                .getHeaders()
                                .get(TenantContext.HEADER_NAME))
                .isNull();
    }

    @Test
    void policyEngine404IsA404() {
        // RetryRegistry.ofDefaults() retries every error (production ignores 404), so give each
        // of its three attempts a response.
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse.Builder().code(404).build());
        }

        StepVerifier.create(
                        client.getDecision("d1")
                                .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, TENANT)))
                .expectErrorSatisfies(
                        e ->
                                assertThat(e)
                                        .isInstanceOfSatisfying(
                                                ResponseStatusException.class,
                                                r ->
                                                        assertThat(r.getStatusCode())
                                                                .isEqualTo(HttpStatus.NOT_FOUND)))
                .verify(TIMEOUT);
    }
}
