package io.emcip.moderation.service.config;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

class ServiceTokenFilterTest {

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        ServiceTokenFilter filter = new ServiceTokenFilter();
        ReflectionTestUtils.setField(filter, "configuredToken", "internal-service-token");

        var router =
                route(GET("/api/moderation-rules"), req -> ok().build())
                        .andRoute(GET("/actuator/health"), req -> ok().build());

        client = WebTestClient.bindToRouterFunction(router).webFilter(filter).build();
    }

    @Test
    void apiPath_withoutToken_returns401() {
        client.get().uri("/api/moderation-rules").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void apiPath_withValidToken_returns200() {
        client.get()
                .uri("/api/moderation-rules")
                .header("X-Service-Token", "internal-service-token")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void apiPath_withInvalidToken_returns401() {
        client.get()
                .uri("/api/moderation-rules")
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
