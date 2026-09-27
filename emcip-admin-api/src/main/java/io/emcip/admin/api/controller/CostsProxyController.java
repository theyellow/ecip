package io.emcip.admin.api.controller;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Proxies cost analytics requests to the llm-orchestrator service. Admin-UI → admin-api →
 * llm-orchestrator (API Gateway pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/costs")
@PreAuthorize("hasAuthority('COSTS_READ')")
@Tag(name = "Costs", description = "Proxy to llm-orchestrator cost analytics")
public class CostsProxyController {

    private final WebClient orchestratorClient;
    private final CircuitBreaker circuitBreaker;

    public CostsProxyController(
            @Qualifier("orchestratorWebClient") WebClient orchestratorClient,
            CircuitBreakerRegistry registry) {
        this.orchestratorClient = orchestratorClient;
        this.circuitBreaker = registry.circuitBreaker("orchestrator");
    }

    @Operation(summary = "Get total costs for a date range")
    @GetMapping("/totals")
    public Mono<String> getTotals(@RequestParam String from, @RequestParam String to) {
        return forward("/api/costs/totals", from, to);
    }

    @Operation(summary = "Get costs broken down by model for a date range")
    @GetMapping("/by-model")
    public Mono<String> getCostsByModel(@RequestParam String from, @RequestParam String to) {
        return forward("/api/costs/by-model", from, to);
    }

    @Operation(summary = "Get costs broken down by day for a date range")
    @GetMapping("/by-day")
    public Mono<String> getCostsByDay(@RequestParam String from, @RequestParam String to) {
        return forward("/api/costs/by-day", from, to);
    }

    /**
     * A tenant-bound caller sees its own costs only: llm-orchestrator filters on the {@code
     * X-Tenant-Id} sent here (ADR-009 rule 8, TENANT-AUDIT C-1). ADMIN admin mode sends none = all
     * tenants.
     */
    private Mono<String> forward(String path, String from, String to) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec =
                                    orchestratorClient
                                            .get()
                                            .uri(
                                                    uriBuilder ->
                                                            uriBuilder
                                                                    .path(path)
                                                                    .queryParam("from", from)
                                                                    .queryParam("to", to)
                                                                    .build());
                            if (tenantId != null) {
                                spec.header(TenantContext.HEADER_NAME, tenantId);
                            }
                            return spec.retrieve().bodyToMono(String.class);
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
