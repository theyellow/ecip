package io.emcip.admin.api.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Proxies AI configuration CRUD to the llm-orchestrator service. Admin-UI → admin-api →
 * llm-orchestrator (API Gateway pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AIProxyController {

    private final WebClient orchestratorClient;

    public AIProxyController(@Qualifier("orchestratorWebClient") WebClient orchestratorClient) {
        this.orchestratorClient = orchestratorClient;
    }

    // ---- Models ----

    @GetMapping("/models")
    public Mono<String> listModels() {
        return orchestratorClient.get().uri("/api/models").retrieve().bodyToMono(String.class);
    }

    @PostMapping(value = "/models", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<String> createModel(@RequestBody String body) {
        return orchestratorClient
                .post()
                .uri("/api/models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    @PutMapping(value = "/models/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> updateModel(@PathVariable String id, @RequestBody String body) {
        return orchestratorClient
                .put()
                .uri("/api/models/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    @DeleteMapping("/models/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteModel(@PathVariable String id) {
        return orchestratorClient
                .delete()
                .uri("/api/models/{id}", id)
                .retrieve()
                .bodyToMono(Void.class);
    }

    // ---- Templates ----

    @GetMapping("/templates")
    public Mono<String> listTemplates() {
        return orchestratorClient.get().uri("/api/templates").retrieve().bodyToMono(String.class);
    }

    @PostMapping(value = "/templates", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<String> createTemplate(@RequestBody String body) {
        return orchestratorClient
                .post()
                .uri("/api/templates")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    @PutMapping(value = "/templates/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> updateTemplate(@PathVariable String id, @RequestBody String body) {
        return orchestratorClient
                .put()
                .uri("/api/templates/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTemplate(@PathVariable String id) {
        return orchestratorClient
                .delete()
                .uri("/api/templates/{id}", id)
                .retrieve()
                .bodyToMono(Void.class);
    }
}
