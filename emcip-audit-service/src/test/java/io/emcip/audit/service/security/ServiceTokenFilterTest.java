package io.emcip.audit.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ServiceTokenFilterTest {

    private static final String VALID_TOKEN = "internal-service-token";

    private ServiceTokenFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ServiceTokenFilter(VALID_TOKEN);
    }

    @Test
    void apiEndpoint_withoutToken_returns401AndChainNotInvoked() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/audit/events").build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainInvoked.get()).isFalse();
    }

    @Test
    void apiEndpoint_withInvalidToken_returns401AndChainNotInvoked() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/audit/events")
                                .header("X-Service-Token", "wrong-token")
                                .build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainInvoked.get()).isFalse();
    }

    @Test
    void apiEndpoint_withValidToken_chainIsInvoked() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/audit/events")
                                .header("X-Service-Token", VALID_TOKEN)
                                .build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainInvoked.get()).isTrue();
    }

    @Test
    void actuatorEndpoint_withoutToken_chainIsInvoked() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health").build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainInvoked.get()).isTrue();
    }
}
