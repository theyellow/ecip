package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.IntentClassifierClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/intent-signal-config")
@RequiredArgsConstructor
@Tag(name = "Intent Signal Config", description = "Proxy to intent-classifier signal configuration")
public class IntentSignalConfigController {

    private final IntentClassifierClient intentClassifierClient;

    @Operation(summary = "Get intent signal configuration")
    @GetMapping
    @PreAuthorize("hasAuthority('POLICY_RULES_READ')")
    public Mono<JsonNode> get() {
        return intentClassifierClient.getSignalConfig();
    }

    @Operation(summary = "Upsert intent signal configuration")
    @PutMapping
    @PreAuthorize("hasAuthority('POLICY_RULES_WRITE')")
    public Mono<JsonNode> upsert(@RequestBody JsonNode body) {
        return intentClassifierClient.upsertSignalConfig(body);
    }
}
