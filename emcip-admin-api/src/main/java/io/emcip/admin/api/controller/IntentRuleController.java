package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.IntentClassifierClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/intent-rules")
@RequiredArgsConstructor
@Tag(name = "Intent Rules", description = "Proxy to intent-classifier rule management")
public class IntentRuleController {

    private final IntentClassifierClient intentClassifierClient;

    @Operation(summary = "List all intent rules")
    @GetMapping
    @PreAuthorize("hasAuthority('POLICY_RULES_READ')")
    public Flux<JsonNode> list() {
        return intentClassifierClient.listRules();
    }

    @Operation(summary = "Create an intent rule")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('POLICY_RULES_WRITE')")
    public Mono<JsonNode> create(@RequestBody JsonNode body) {
        return intentClassifierClient.createRule(body);
    }

    @Operation(summary = "Update an intent rule")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('POLICY_RULES_WRITE')")
    public Mono<JsonNode> update(@PathVariable("id") String id, @RequestBody JsonNode body) {
        return intentClassifierClient.updateRule(id, body);
    }

    @Operation(summary = "Delete an intent rule")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('POLICY_RULES_WRITE')")
    public Mono<Void> delete(@PathVariable("id") String id) {
        return intentClassifierClient.deleteRule(id);
    }
}
