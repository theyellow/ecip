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

/** KNOW-F1: research calls carry the caller's tenant; knowledge-engine 404 stays 404. */
class ResearchProxyControllerTenantTest {

    private static final String TENANT_A = UUID.randomUUID().toString();
    private static final String TENANT_B = UUID.randomUUID().toString();
    private static final Context BOUND_A =
            ReactorTenantContext.withTenant(Context.empty(), TENANT_A);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer ke;
    private ResearchProxyController controller;

    @BeforeEach
    void setUp() throws Exception {
        ke = new MockWebServer();
        ke.start();
        controller =
                new ResearchProxyController(
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
    void startUsesTheBoundTenantNotTheBody() throws Exception {
        respond(201, "{}");
        controller
                .startResearch("{\"question\":\"q\",\"tenantId\":\"" + TENANT_B + "\"}")
                .contextWrite(BOUND_A)
                .block();
        assertThat(objectMapper.readTree(taken().getBody().utf8()).path("tenantId").asString())
                .isEqualTo(TENANT_A);
    }

    @Test
    void listUsesTheBoundTenantNotTheQueryParameter() throws Exception {
        respond(200, "[]");
        controller.listSessions(UUID.fromString(TENANT_B)).contextWrite(BOUND_A).block();
        assertThat(taken().getTarget()).contains("tenantId=" + TENANT_A).doesNotContain(TENANT_B);
    }

    @Test
    void idAddressedCallsCarryTheBoundTenant() throws Exception {
        UUID id = UUID.randomUUID();
        respond(200, "{}");
        controller.getSession(id).contextWrite(BOUND_A).block();
        assertThat(taken().getTarget()).contains(id.toString()).contains("tenantId=" + TENANT_A);
        respond(200, "{}");
        controller.pauseSession(id).contextWrite(BOUND_A).block();
        assertThat(taken().getTarget()).contains("/pause").contains("tenantId=" + TENANT_A);
    }

    @Test
    void adminModeSendsNoTenantOnIdAddressedCalls() throws Exception {
        respond(200, "{}");
        controller
                .getSession(UUID.randomUUID())
                .contextWrite(ReactorTenantContext.withAdminMode(Context.empty()))
                .block();
        assertThat(taken().getTarget()).doesNotContain("tenantId");
    }

    @Test
    void knowledgeEngine404StaysA404() {
        respond(404, "");
        var response = controller.getSession(UUID.randomUUID()).contextWrite(BOUND_A).block();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
