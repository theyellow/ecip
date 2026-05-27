package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.StatusUpdateRequest;
import io.emcip.admin.api.service.AccountSelectionException;
import io.emcip.admin.api.service.FlagService;
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

@RestController
@RequestMapping("/api/flags")
@RequiredArgsConstructor
@Tag(name = "Flags", description = "View and action moderation flags from the policy engine")
public class FlagController {

    private final FlagService flagService;

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

    @Operation(summary = "List recent policy flags")
    @GetMapping
    public Mono<JsonNode> getFlags(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "decision", required = false) String decision) {
        return flagService.listFlags(page, Math.min(size, 200), decision);
    }

    @Operation(summary = "Update the status of a policy flag")
    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> updateStatus(
            @PathVariable String id, @Valid @RequestBody StatusUpdateRequest req) {
        return flagService.updateStatus(id, req.status());
    }

    @Operation(summary = "Reply to a flagged message via Telegram")
    @PostMapping("/{id}/reply")
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
}
