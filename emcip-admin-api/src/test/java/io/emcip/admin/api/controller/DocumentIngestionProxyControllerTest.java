package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.context.Context;
import tools.jackson.databind.ObjectMapper;

/** KNOW-F1: ingestion calls carry the caller's tenant; knowledge-engine 404 stays 404. */
class DocumentIngestionProxyControllerTest {

    private static final String TENANT_A = UUID.randomUUID().toString();
    private static final String TENANT_B = UUID.randomUUID().toString();
    private static final Context BOUND_A =
            ReactorTenantContext.withTenant(Context.empty(), TENANT_A);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer ke;
    private DocumentIngestionProxyController controller;

    @BeforeEach
    void setUp() throws Exception {
        ke = new MockWebServer();
        ke.start();
        controller =
                new DocumentIngestionProxyController(
                        WebClient.create(ke.url("/").toString()),
                        CircuitBreakerRegistry.ofDefaults(),
                        objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        ke.close();
    }

    private void respond(int code, String body) {
        ke.enqueue(
                new MockResponse.Builder()
                        .code(code)
                        .body(body)
                        .addHeader("Content-Type", "application/json")
                        .build());
    }

    private RecordedRequest taken() throws Exception {
        return ke.takeRequest(5, TimeUnit.SECONDS);
    }

    @Test
    void ingestUrlUsesTheBoundTenantNotTheBody() throws Exception {
        respond(202, "{\"jobId\":\"j\"}");
        controller
                .ingestUrl("{\"url\":\"https://x.example\",\"tenantId\":\"" + TENANT_B + "\"}")
                .contextWrite(BOUND_A)
                .block();
        assertThat(objectMapper.readTree(taken().getBody().utf8()).path("tenantId").asString())
                .isEqualTo(TENANT_A);
    }

    @Test
    void listUsesTheBoundTenant() throws Exception {
        respond(200, "{}");
        controller.listJobs(UUID.fromString(TENANT_B), 0, 20).contextWrite(BOUND_A).block();
        assertThat(taken().getTarget()).contains("tenantId=" + TENANT_A).doesNotContain(TENANT_B);
    }

    @Test
    void deleteCarriesTheBoundTenant() throws Exception {
        String id = UUID.randomUUID().toString();
        respond(204, "");
        controller.deleteJob(id).contextWrite(BOUND_A).block();
        assertThat(taken().getTarget()).contains(id).contains("tenantId=" + TENANT_A);
    }

    @Test
    void knowledgeEngine404StaysA404() {
        respond(404, "");
        var response =
                controller.getJobStatus(UUID.randomUUID().toString()).contextWrite(BOUND_A).block();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
