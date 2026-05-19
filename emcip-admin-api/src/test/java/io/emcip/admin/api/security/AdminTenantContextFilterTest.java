package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AdminTenantContextFilterTest {

    private final AdminTenantContextFilter filter = new AdminTenantContextFilter();

    @Test
    void actuatorPath_bypassesFilter() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/actuator/health/liveness").build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainInvoked.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void authTokenPath_bypassesFilter() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/token").build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainInvoked.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void legacyAuthTokenPath_bypassesFilter() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/auth/token").build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainInvoked.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tenantHeader_propagatesTenantContext() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/groups")
                                .header(TenantContext.HEADER_NAME, "tenant-abc")
                                .build());

        AtomicReference<String> capturedTenant = new AtomicReference<>();
        WebFilterChain chain =
                ex ->
                        Mono.deferContextual(
                                ctx -> {
                                    capturedTenant.set(ReactorTenantContext.getTenantId(ctx));
                                    return Mono.empty();
                                });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(capturedTenant.get()).isEqualTo("tenant-abc");
    }

    @Test
    void adminRole_propagatesAdminMode() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/groups").build());

        AtomicReference<Boolean> capturedAdminMode = new AtomicReference<>();
        WebFilterChain chain =
                ex ->
                        Mono.deferContextual(
                                ctx -> {
                                    capturedAdminMode.set(ReactorTenantContext.isAdminMode(ctx));
                                    return Mono.empty();
                                });

        var auth =
                new UsernamePasswordAuthenticationToken(
                        "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        StepVerifier.create(
                        filter.filter(exchange, chain)
                                .contextWrite(
                                        ReactiveSecurityContextHolder.withAuthentication(auth)))
                .verifyComplete();

        assertThat(capturedAdminMode.get()).isTrue();
    }

    @Test
    void noTenantAndNoAdminRole_returns400() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/groups").build());

        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> chainInvoked.set(true));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(chainInvoked.get()).isFalse();
    }
}
