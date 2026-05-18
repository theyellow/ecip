package io.emcip.tdlib.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ServiceTokenFilterTest {

    private static final String VALID_TOKEN = "test-secret-token";
    private static final String HEADER = "X-Service-Token";

    private ServiceTokenFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new ServiceTokenFilter();
        // Inject the configured token via reflection (field injection from @Value)
        var field = ServiceTokenFilter.class.getDeclaredField("configuredToken");
        field.setAccessible(true);
        field.set(filter, VALID_TOKEN);
    }

    @Test
    void apiRequest_withoutToken_returns401() {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get(
                                        "/api/auth/initialize/" + java.util.UUID.randomUUID())
                                .build());
        WebFilterChain chain = ex -> Mono.error(new AssertionError("chain must not be called"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void apiRequest_withCorrectToken_proceedsToChain() {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get(
                                        "/api/auth/initialize/" + java.util.UUID.randomUUID())
                                .header(HEADER, VALID_TOKEN)
                                .build());
        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // 200 OK (default) — chain was reached, so no UNAUTHORIZED was set
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalRequest_withoutToken_returns401() {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/internal/chats/" + java.util.UUID.randomUUID())
                                .build());
        WebFilterChain chain = ex -> Mono.error(new AssertionError("chain must not be called"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void actuatorRequest_withoutToken_proceedsToChain() {
        var exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health").build());
        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
