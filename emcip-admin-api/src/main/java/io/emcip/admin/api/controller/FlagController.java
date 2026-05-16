package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.PolicyEngineClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/flags")
@RequiredArgsConstructor
@Tag(name = "Flags", description = "View and action moderation flags from the policy engine")
public class FlagController {

    private final PolicyEngineClient policyEngineClient;

    @Operation(summary = "List recent policy flags")
    @GetMapping
    public Flux<JsonNode> getFlags(
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "decision", required = false) String decision) {
        if (decision != null && !decision.isBlank()) {
            return policyEngineClient.listDecisionsByType(decision, size);
        }
        return policyEngineClient.listFlags(size);
    }

    @Operation(summary = "Update the status of a policy flag")
    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> updateStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            return Mono.error(new IllegalArgumentException("status is required"));
        }
        return policyEngineClient.updateDecisionStatus(id, status);
    }
}
