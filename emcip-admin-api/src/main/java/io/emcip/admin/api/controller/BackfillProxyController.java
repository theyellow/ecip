package io.emcip.admin.api.controller;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Proxies bulk backfill requests to the knowledge-engine service. Admin-UI → admin-api →
 * knowledge-engine (API Gateway pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/groups")
@Tag(name = "Backfill", description = "Trigger and monitor bulk backfill for a watched group")
public class BackfillProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;

    public BackfillProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge");
    }

    @Operation(summary = "Trigger bulk backfill for a watched group")
    @PostMapping("/{chatId}/backfill")
    public Mono<ResponseEntity<String>> triggerBackfill(
            @PathVariable long chatId, @RequestBody BackfillTriggerRequest request) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantIdStr = ReactorTenantContext.getTenantId(ctx);

                            long fromEpoch = Instant.parse(request.fromDate()).getEpochSecond();

                            Map<String, Object> body = new HashMap<>();
                            body.put("accountId", request.accountId().toString());
                            body.put("chatId", chatId);
                            body.put("fromDate", fromEpoch);
                            if (tenantIdStr != null) {
                                body.put("tenantId", tenantIdStr);
                            }

                            return knowledgeWebClient
                                    .post()
                                    .uri("/api/knowledge/backfill")
                                    .bodyValue(body)
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .map(ResponseEntity::ok)
                                    .onErrorResume(
                                            e -> {
                                                log.error(
                                                        "Backfill proxy error chatId={}: {}",
                                                        chatId,
                                                        e.getMessage());
                                                return Mono.just(
                                                        ResponseEntity.status(
                                                                        HttpStatus
                                                                                .SERVICE_UNAVAILABLE)
                                                                .<String>build());
                                            });
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get backfill progress for a watched group")
    @GetMapping("/{chatId}/backfill/{backfillId}")
    public Mono<ResponseEntity<String>> getBackfillStatus(
            @PathVariable long chatId, @PathVariable String backfillId) {
        return knowledgeWebClient
                .get()
                .uri("/api/knowledge/backfill/status?backfillId={id}", backfillId)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Backfill status proxy error chatId={}, backfillId={}: {}",
                                    chatId,
                                    backfillId,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public record BackfillTriggerRequest(UUID accountId, String fromDate) {}
}
