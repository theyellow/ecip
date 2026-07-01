package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.StatusUpdateRequest;
import io.emcip.admin.api.service.AccountSelectionException;
import io.emcip.admin.api.service.FlagService;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@RestController
@RequestMapping("/api/flags")
@RequiredArgsConstructor
@Tag(name = "Flags", description = "View and action moderation flags from the policy engine")
public class FlagController {

    private final FlagService flagService;
    private final RateLimiterRegistry rateLimiterRegistry;

    public record ReplyRequest(
            @NotBlank @Size(max = 4096, message = "text must be 4096 characters or fewer")
                    String text,
            @NotNull String target,
            boolean replyToOriginal,
            boolean prefixModerator,
            UUID accountId) {}

    public record ReplyResponse(long messageId, String target, boolean markedActioned) {}

    public record AccountOption(UUID id, String displayName, String phoneNumber) {}

    public record AccountSelectionRequired(List<AccountOption> accounts) {}

    public record AnalyseResponse(boolean success, String analysis, String model) {}

    @Operation(summary = "List recent policy flags")
    @GetMapping
    @PreAuthorize("hasAuthority('MODERATION_RULES_READ')")
    public Mono<JsonNode> getFlags(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "decision", required = false) String decision,
            @RequestParam(name = "intent", required = false) String intent,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "minConfidence", required = false) Double minConfidence) {
        return flagService.listFlags(
                page, Math.min(size, 200), decision, intent, from, to, minConfidence);
    }

    @Operation(summary = "Update the status of a policy flag")
    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MODERATION_RULES_WRITE')")
    public Mono<Void> updateStatus(
            @PathVariable String id, @Valid @RequestBody StatusUpdateRequest req) {
        return flagService.updateStatus(id, req.status());
    }

    @Operation(summary = "Reply to a flagged message via Telegram")
    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAuthority('MODERATION_RULES_WRITE')")
    public Mono<ResponseEntity<?>> reply(
            @PathVariable String id, @Valid @RequestBody ReplyRequest req) {
        return flagService
                .reply(
                        id,
                        req.text(),
                        req.target(),
                        req.replyToOriginal(),
                        req.prefixModerator(),
                        req.accountId())
                .<ResponseEntity<?>>map(
                        resp -> ResponseEntity.status(HttpStatus.CREATED).body(resp))
                .onErrorResume(
                        AccountSelectionException.class,
                        e ->
                                Mono.just(
                                        ResponseEntity.status(HttpStatus.CONFLICT)
                                                .body(
                                                        new AccountSelectionRequired(
                                                                e.getAccounts()))));
    }

    @Operation(summary = "Analyse a flag with AI")
    @PostMapping("/{id}/analyse")
    @PreAuthorize("hasAuthority('MODERATION_RULES_READ')")
    public Mono<ResponseEntity<AnalyseResponse>> analyse(@PathVariable String id) {
        return flagService
                .analyse(id)
                .map(ResponseEntity::ok)
                .onErrorReturn(
                        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(new AnalyseResponse(false, "Analysis unavailable", null)))
                .transformDeferred(
                        RateLimiterOperator.of(rateLimiterRegistry.rateLimiter("llm-trigger")));
    }

    @Operation(summary = "Multi-turn AI research chat about a flag")
    @PostMapping("/{id}/chat")
    @PreAuthorize("hasAuthority('MODERATION_RULES_READ')")
    public Mono<ResponseEntity<JsonNode>> chat(
            @PathVariable String id, @RequestBody JsonNode body) {
        return flagService
                .chat(id, body)
                .map(ResponseEntity::ok)
                .onErrorReturn(
                        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(
                                        (JsonNode)
                                                JsonNodeFactory.instance
                                                        .objectNode()
                                                        .put("success", false)
                                                        .put("content", "Chat unavailable")))
                .transformDeferred(
                        RateLimiterOperator.of(rateLimiterRegistry.rateLimiter("llm-trigger")));
    }
}
