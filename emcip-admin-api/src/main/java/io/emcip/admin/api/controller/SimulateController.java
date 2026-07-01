package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.SimulateMessageRequest;
import io.emcip.admin.api.service.SimulationService;
import io.emcip.admin.api.service.SimulationService.SimulateTraceResult;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/simulate")
@Tag(name = "Simulation", description = "Inject test messages through the full pipeline")
@RequiredArgsConstructor
public class SimulateController {

    private final SimulationService simulationService;
    private final RateLimiterRegistry rateLimiterRegistry;

    @Operation(summary = "Simulate a Telegram message through the processing pipeline")
    @PostMapping("/message")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('SIMULATE_WRITE')")
    public Mono<SimulateTraceResult> simulateMessage(
            @Valid @RequestBody SimulateMessageRequest req) {
        return simulationService
                .simulate(req)
                .transformDeferred(
                        RateLimiterOperator.of(rateLimiterRegistry.rateLimiter("llm-trigger")));
    }
}
