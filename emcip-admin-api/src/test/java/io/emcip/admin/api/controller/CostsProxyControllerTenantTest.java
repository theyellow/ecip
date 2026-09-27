package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * TENANT-AUDIT C-1: a tenant-bound caller's cost views are scoped by llm-orchestrator, which needs
 * the tenant to do so. ADMIN admin mode sends none (all tenants).
 */
@ExtendWith(MockitoExtension.class)
class CostsProxyControllerTenantTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Mock private ExchangeFunction exchangeFunction;

    private CostsProxyController controller;

    @BeforeEach
    void setUp() {
        controller =
                new CostsProxyController(
                        WebClient.builder().exchangeFunction(exchangeFunction).build(),
                        CircuitBreakerRegistry.ofDefaults());
        when(exchangeFunction.exchange(any()))
                .thenAnswer(
                        inv ->
                                Mono.just(
                                        ClientResponse.create(HttpStatus.OK)
                                                .header("Content-Type", "application/json")
                                                .body("{}")
                                                .build()));
    }

    @Test
    void everyCostCallSendsTheBoundTenant() {
        String tenant = UUID.randomUUID().toString();
        List<Function<CostsProxyController, Mono<String>>> calls =
                List.of(
                        c -> c.getTotals("a", "b"),
                        c -> c.getCostsByModel("a", "b"),
                        c -> c.getCostsByDay("a", "b"));

        for (Function<CostsProxyController, Mono<String>> call : calls) {
            StepVerifier.create(
                            call.apply(controller)
                                    .contextWrite(
                                            ctx -> ReactorTenantContext.withTenant(ctx, tenant)))
                    .expectNext("{}")
                    .expectComplete()
                    .verify(TIMEOUT);
        }

        ArgumentCaptor<ClientRequest> sent = ArgumentCaptor.forClass(ClientRequest.class);
        verify(exchangeFunction, times(3)).exchange(sent.capture());
        assertThat(sent.getAllValues())
                .allSatisfy(
                        request ->
                                assertThat(request.headers().getFirst(TenantContext.HEADER_NAME))
                                        .isEqualTo(tenant));
    }

    @Test
    void adminModeSendsNoTenant() {
        StepVerifier.create(
                        controller
                                .getTotals("a", "b")
                                .contextWrite(ReactorTenantContext::withAdminMode))
                .expectNext("{}")
                .expectComplete()
                .verify(TIMEOUT);

        ArgumentCaptor<ClientRequest> sent = ArgumentCaptor.forClass(ClientRequest.class);
        verify(exchangeFunction).exchange(sent.capture());
        assertThat(sent.getValue().headers().getFirst(TenantContext.HEADER_NAME)).isNull();
    }
}
