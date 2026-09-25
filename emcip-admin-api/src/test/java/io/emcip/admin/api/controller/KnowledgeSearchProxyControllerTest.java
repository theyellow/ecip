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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * P3.8a: the tenant forwarded to knowledge-engine comes from the caller's identity, never from a
 * tenant-bound caller's request. Asserted on what actually reaches knowledge-engine.
 */
class KnowledgeSearchProxyControllerTest {

    private static final String TENANT_A = UUID.randomUUID().toString();
    private static final String TENANT_B = UUID.randomUUID().toString();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer knowledgeEngine;
    private KnowledgeSearchProxyController controller;

    @BeforeEach
    void setUp() throws Exception {
        knowledgeEngine = new MockWebServer();
        knowledgeEngine.start();
        knowledgeEngine.enqueue(
                new MockResponse.Builder()
                        .body("{\"graphResults\":[],\"documentResults\":[]}")
                        .addHeader("Content-Type", "application/json")
                        .build());
        controller =
                new KnowledgeSearchProxyController(
                        WebClient.create(knowledgeEngine.url("/").toString()),
                        CircuitBreakerRegistry.ofDefaults(),
                        objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        knowledgeEngine.close();
    }

    private String body(String tenantId) {
        return tenantId == null
                ? "{\"query\":\"q\",\"searchType\":\"HYBRID\",\"limit\":5}"
                : "{\"query\":\"q\",\"searchType\":\"HYBRID\",\"limit\":5,\"tenantId\":\""
                        + tenantId
                        + "\"}";
    }

    private JsonNode forwardedSearchBody(String requestBody, Context ctx) throws Exception {
        var response = controller.search(requestBody).contextWrite(ctx).block();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RecordedRequest recorded = knowledgeEngine.takeRequest(5, TimeUnit.SECONDS);
        return objectMapper.readTree(recorded.getBody().utf8());
    }

    @Test
    void tenantBoundCallerCannotChooseAnotherTenant() throws Exception {
        JsonNode forwarded =
                forwardedSearchBody(
                        body(TENANT_B), ReactorTenantContext.withTenant(Context.empty(), TENANT_A));

        assertThat(forwarded.path("tenantId").asString()).isEqualTo(TENANT_A);
    }

    @Test
    void tenantBoundCallerWithoutTenantInBodyStillGetsItsOwn() throws Exception {
        JsonNode forwarded =
                forwardedSearchBody(
                        body(null), ReactorTenantContext.withTenant(Context.empty(), TENANT_A));

        assertThat(forwarded.path("tenantId").asString()).isEqualTo(TENANT_A);
    }

    @Test
    void adminInAdminModeMayPickATenantInTheBody() throws Exception {
        JsonNode forwarded =
                forwardedSearchBody(
                        body(TENANT_B), ReactorTenantContext.withAdminMode(Context.empty()));

        assertThat(forwarded.path("tenantId").asString()).isEqualTo(TENANT_B);
    }

    @Test
    void adminWithoutAnyTenantSearchesGlobalOnly() throws Exception {
        JsonNode forwarded =
                forwardedSearchBody(
                        body(null), ReactorTenantContext.withAdminMode(Context.empty()));

        assertThat(forwarded.path("tenantId").isNull()).isTrue();
    }

    @Test
    void nonObjectBodyIsRejected() {
        var response =
                controller
                        .search("[1,2,3]")
                        .contextWrite(ReactorTenantContext.withTenant(Context.empty(), TENANT_A))
                        .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(knowledgeEngine.getRequestCount()).isZero();
    }

    @Test
    void topicsUseTheBoundTenantNotTheQueryParameter() throws Exception {
        controller
                .getTopics(UUID.fromString(TENANT_B), 10)
                .contextWrite(ReactorTenantContext.withTenant(Context.empty(), TENANT_A))
                .block();

        RecordedRequest recorded = knowledgeEngine.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded.getTarget()).contains("tenantId=" + TENANT_A).doesNotContain(TENANT_B);
    }

    @Test
    void personsUseTheBoundTenantNotTheQueryParameter() throws Exception {
        controller
                .getPersons(UUID.fromString(TENANT_B), 10)
                .contextWrite(ReactorTenantContext.withTenant(Context.empty(), TENANT_A))
                .block();

        RecordedRequest recorded = knowledgeEngine.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded.getTarget()).contains("tenantId=" + TENANT_A).doesNotContain(TENANT_B);
    }
}
