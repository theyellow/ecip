package io.emcip.policy.engine.config;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

class ServiceTokenFilterTest {

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        ServiceTokenFilter filter = new ServiceTokenFilter();
        ReflectionTestUtils.setField(filter, "configuredToken", "internal-service-token");

        var router =
                RouterFunctions.route(GET("/api/policy-rules"), req -> ServerResponse.ok().build())
                        .andRoute(GET("/actuator/health"), req -> ServerResponse.ok().build());

        client = WebTestClient.bindToRouterFunction(router).webFilter(filter).build();
    }

    @Test
    void apiPath_withoutToken_returns401() {
        client.get().uri("/api/policy-rules").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void apiPath_withValidToken_returns200() {
        client.get()
                .uri("/api/policy-rules")
                .header("X-Service-Token", "internal-service-token")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void apiPath_withInvalidToken_returns401() {
        client.get()
                .uri("/api/policy-rules")
                .header("X-Service-Token", "wrong-token")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void actuatorPath_withoutToken_isAllowed() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
