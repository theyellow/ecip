package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.PolicyEngineClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
public class PolicyRuleController {

    private final PolicyEngineClient policyEngineClient;

    @GetMapping
    public Flux<JsonNode> listActiveRules() {
        return policyEngineClient.listRules();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<JsonNode> createRule(@RequestBody JsonNode body) {
        return policyEngineClient.createRule(body);
    }

    @PutMapping("/{id}")
    public Mono<JsonNode> updateRule(@PathVariable("id") String id, @RequestBody JsonNode body) {
        return policyEngineClient.updateRule(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteRule(@PathVariable("id") String id) {
        return policyEngineClient.deleteRule(id);
    }
}
