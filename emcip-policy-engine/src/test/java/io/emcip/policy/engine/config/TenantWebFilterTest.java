package io.emcip.policy.engine.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.TenantContext;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** TENANT-AUDIT: the X-Tenant-Id header reaches the controllers via the Reactor context. */
class TenantWebFilterTest {

    private final TenantWebFilter filter = new TenantWebFilter();

    @Test
    void validHeaderPutsTheTenantIntoTheReactorContext() {
        UUID tenant = UUID.randomUUID();
        AtomicReference<UUID> seen = new AtomicReference<>();
        WebFilterChain chain =
                ex ->
                        Mono.deferContextual(
                                ctx -> {
                                    seen.set(RequestTenant.of(ctx));
                                    return Mono.empty();
                                });

        StepVerifier.create(filter.filter(exchange(tenant.toString()), chain)).verifyComplete();

        assertThat(seen.get()).isEqualTo(tenant);
    }

    @Test
    void noHeaderRunsTheChainWithoutATenant() {
        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<UUID> seen = new AtomicReference<>(UUID.randomUUID());
        WebFilterChain chain =
                ex ->
                        Mono.deferContextual(
                                ctx -> {
                                    ran.set(true);
                                    seen.set(RequestTenant.of(ctx));
                                    return Mono.empty();
                                });

        StepVerifier.create(filter.filter(exchange(null), chain)).verifyComplete();

        assertThat(ran).isTrue();
        assertThat(seen.get()).isNull();
    }

    @Test
    void malformedHeaderIsRejectedWith400AndTheChainNeverRuns() {
        AtomicBoolean ran = new AtomicBoolean();
        MockServerWebExchange exchange = exchange("not-a-uuid");

        StepVerifier.create(filter.filter(exchange, ex -> Mono.fromRunnable(() -> ran.set(true))))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ran).isFalse();
    }

    private static MockServerWebExchange exchange(String tenant) {
        MockServerHttpRequest.BaseBuilder<?> request =
                MockServerHttpRequest.get("/api/policy-rules");
        if (tenant != null) {
            request.header(TenantContext.HEADER_NAME, tenant);
        }
        return MockServerWebExchange.from(request);
    }
}
