package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.ModerationServiceClient;
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
@RequestMapping("/api/moderation-rules")
@RequiredArgsConstructor
public class ModerationRuleController {

    private final ModerationServiceClient moderationServiceClient;

    @GetMapping
    public Flux<JsonNode> list() {
        return moderationServiceClient.listRules();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<JsonNode> create(@RequestBody JsonNode body) {
        return moderationServiceClient.createRule(body);
    }

    @PutMapping("/{id}")
    public Mono<JsonNode> update(@PathVariable("id") String id, @RequestBody JsonNode body) {
        return moderationServiceClient.updateRule(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable("id") String id) {
        return moderationServiceClient.deleteRule(id);
    }
}
