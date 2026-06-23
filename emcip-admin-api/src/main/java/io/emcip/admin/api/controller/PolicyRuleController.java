package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.PolicyEngineClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
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
@RequestMapping("/api/policy-rules")
@RequiredArgsConstructor
@Tag(name = "Policy Rules", description = "Proxy to policy-engine rule management")
public class PolicyRuleController {

    private final PolicyEngineClient policyEngineClient;

    @Operation(summary = "List active policy rules")
    @GetMapping
    public Flux<JsonNode> listActiveRules() {
        return policyEngineClient.listRules();
    }

    @Operation(summary = "Create a policy rule")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<JsonNode> createRule(@RequestBody JsonNode body) {
        return policyEngineClient.createRule(body);
    }

    @Operation(summary = "Update a policy rule; passes caller identity for history snapshot")
    @PutMapping("/{id}")
    public Mono<JsonNode> updateRule(@PathVariable("id") String id, @RequestBody JsonNode body) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .defaultIfEmpty("unknown")
                .flatMap(editedBy -> policyEngineClient.updateRule(id, body, editedBy));
    }

    @Operation(summary = "Delete a policy rule")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteRule(@PathVariable("id") String id) {
        return policyEngineClient.deleteRule(id);
    }

    @Operation(summary = "Evaluate an unsaved rule against a test context — no side effects")
    @PostMapping("/dry-run")
    public Mono<JsonNode> dryRun(@RequestBody JsonNode body) {
        return policyEngineClient.dryRun(body);
    }

    @Operation(summary = "Get version history snapshots for a rule")
    @GetMapping("/{id}/history")
    public Flux<JsonNode> getRuleHistory(@PathVariable("id") String id) {
        return policyEngineClient.getRuleHistory(id);
    }
}
