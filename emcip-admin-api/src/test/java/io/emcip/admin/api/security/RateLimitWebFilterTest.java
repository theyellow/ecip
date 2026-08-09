package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.util.ClientIp;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RateLimitWebFilterTest {

    private RateLimitWebFilter filter;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        ClientIp clientIp = new ClientIp(1, new SimpleMeterRegistry());
        // RateLimiterRegistry.ofDefaults() permits 50 requests per 500ns refresh period, which is
        // too permissive to exhaust cheaply and deterministically in a unit test. Use an explicit
        // tiny config instead: 2 permits per (long) window, zero-duration timeout so a denied
        // permit fails fast rather than parking.
        RateLimiterConfig tiny =
                RateLimiterConfig.custom()
                        .limitForPeriod(2)
                        .limitRefreshPeriod(Duration.ofMinutes(1))
                        .timeoutDuration(Duration.ZERO)
                        .build();
        RateLimiterRegistry registry = RateLimiterRegistry.of(tiny);
        RateLimitGroups groups = new RateLimitGroups(registry);
        filter = new RateLimitWebFilter(clientIp, groups, new SimpleMeterRegistry());
        chain = mock(WebFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange authRequestFrom(String xff) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/token").header("X-Forwarded-For", xff));
    }

    @Test
    void rejectsWith429NotAnException() {
        exhaust("203.0.113.7");

        MockServerWebExchange rejected = authRequestFrom("203.0.113.7");
        StepVerifier.create(filter.filter(rejected, chain)).verifyComplete();

        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    void spoofedLeftmostXffLandsInTheSameBucket() {
        exhaust("203.0.113.7");

        MockServerWebExchange spoofed = authRequestFrom("1.1.1.1, 203.0.113.7");
        StepVerifier.create(filter.filter(spoofed, chain)).verifyComplete();

        assertThat(spoofed.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void differentClientsHaveIndependentBuckets() {
        exhaust("203.0.113.7");

        MockServerWebExchange other = authRequestFrom("198.51.100.4");
        StepVerifier.create(filter.filter(other, chain)).verifyComplete();

        assertThat(other.getResponse().getStatusCode()).isNull(); // passed through to the chain
    }

    @Test
    void oneUserExhaustingAdminCrudDoesNotAffectAnother() {
        // Spec proof T2. Authenticated path, so the key is the JWT subject, not the IP. Both
        // users share the exact same client IP so the test only passes if the filter is actually
        // keying on the authenticated principal rather than falling back to the IP.
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(
                            filter.filter(crudRequestAs("alice"), chain)
                                    .contextWrite(authContext("alice")))
                    .verifyComplete();
        }

        MockServerWebExchange bob = crudRequestAs("bob");
        StepVerifier.create(filter.filter(bob, chain).contextWrite(authContext("bob")))
                .verifyComplete();

        assertThat(bob.getResponse().getStatusCode()).isNull(); // bob passed through
    }

    @Test
    void sameUserSharingIpWithAnotherStillGetsLimited() {
        // Proves the previous test isn't silently passing via the IP-fallback path: exhausting
        // alice's bucket while bob (same IP) is untouched shows the key is the JWT subject, not
        // the IP that both requests share.
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(
                            filter.filter(crudRequestAs("alice"), chain)
                                    .contextWrite(authContext("alice")))
                    .verifyComplete();
        }

        MockServerWebExchange aliceAgain = crudRequestAs("alice");
        StepVerifier.create(filter.filter(aliceAgain, chain).contextWrite(authContext("alice")))
                .verifyComplete();

        assertThat(aliceAgain.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * Builds an authenticated exchange for /api/tenants. Authentication is supplied via {@link
     * #authContext(String)} written onto the reactive context of the Mono under test —
     * MockServerWebExchange alone carries no authentication.
     */
    private MockServerWebExchange crudRequestAs(String username) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/tenants"));
    }

    private static reactor.util.context.Context authContext(String username) {
        return ReactiveSecurityContextHolder.withAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    @Test
    void exemptPathsAreNotLimited() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    private void exhaust(String xff) {
        for (int i = 0; i < 2; i++) {
            filter.filter(authRequestFrom(xff), chain).block();
        }
    }
}
