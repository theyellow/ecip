package io.emcip.admin.api.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Proxies AI configuration CRUD to the llm-orchestrator service. Admin-UI → admin-api →
 * llm-orchestrator (API Gateway pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@PreAuthorize("hasAuthority('AI_CONFIG_READ')")
@Tag(name = "AI Proxy", description = "Proxy to llm-orchestrator model and template management")
public class AIProxyController {

    private final WebClient orchestratorClient;
    private final CircuitBreaker circuitBreaker;

    public AIProxyController(
            @Qualifier("orchestratorWebClient") WebClient orchestratorClient,
            CircuitBreakerRegistry registry) {
        this.orchestratorClient = orchestratorClient;
        this.circuitBreaker = registry.circuitBreaker("orchestrator");
    }

    // ---- Models ----

    @Operation(summary = "List AI model configurations")
    @GetMapping("/models")
    public Mono<String> listModels() {
        return orchestratorClient
                .get()
                .uri("/api/models")
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                body ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(), body))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Create an AI model configuration")
    @PostMapping(value = "/models", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<String> createModel(@RequestBody String body) {
        return orchestratorClient
                .post()
                .uri("/api/models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Update an AI model configuration")
    @PutMapping(value = "/models/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> updateModel(@PathVariable String id, @RequestBody String body) {
        return orchestratorClient
                .put()
                .uri("/api/models/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Delete an AI model configuration")
    @DeleteMapping("/models/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteModel(@PathVariable String id) {
        return orchestratorClient
                .delete()
                .uri("/api/models/{id}", id)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    // ---- Templates ----

    @Operation(summary = "List prompt templates")
    @GetMapping("/templates")
    public Mono<String> listTemplates() {
        return orchestratorClient
                .get()
                .uri("/api/templates")
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                body ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(), body))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get a prompt template by name")
    @GetMapping("/templates/{name}")
    public Mono<String> getTemplateByName(@PathVariable String name) {
        return orchestratorClient
                .get()
                .uri("/api/templates/{name}", name)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                body ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(), body))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Create a prompt template")
    @PostMapping(value = "/templates", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<String> createTemplate(@RequestBody String body) {
        return orchestratorClient
                .post()
                .uri("/api/templates")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Update a prompt template")
    @PutMapping(value = "/templates/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> updateTemplate(@PathVariable String id, @RequestBody String body) {
        return orchestratorClient
                .put()
                .uri("/api/templates/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Delete a prompt template")
    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTemplate(@PathVariable String id) {
        return orchestratorClient
                .delete()
                .uri("/api/templates/{id}", id)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    // ---- Provider Config ----

    @GetMapping("/provider-config")
    public Mono<String> listProviderConfigs() {
        return orchestratorClient
                .get()
                .uri("/api/provider-config")
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                body ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(), body))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @PostMapping(value = "/provider-config", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<String> createProviderConfig(@RequestBody String body) {
        return orchestratorClient
                .post()
                .uri("/api/provider-config")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @PutMapping(value = "/provider-config/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> updateProviderConfig(@PathVariable String id, @RequestBody String body) {
        return orchestratorClient
                .put()
                .uri("/api/provider-config/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @DeleteMapping("/provider-config/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteProviderConfig(@PathVariable String id) {
        return orchestratorClient
                .delete()
                .uri("/api/provider-config/{id}", id)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                respBody ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(),
                                                                        respBody))))
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @GetMapping("/provider-config/models")
    public Mono<String> listProxyModels(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String baseUrl,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String apiKey) {
        return orchestratorClient
                .get()
                .uri(
                        uriBuilder -> {
                            uriBuilder.path("/api/provider-config/models");
                            if (baseUrl != null && !baseUrl.isBlank()) {
                                uriBuilder.queryParam("baseUrl", baseUrl);
                            }
                            if (apiKey != null && !apiKey.isBlank()) {
                                uriBuilder.queryParam("apiKey", apiKey);
                            }
                            return uriBuilder.build();
                        })
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        resp ->
                                resp.bodyToMono(String.class)
                                        .flatMap(
                                                body ->
                                                        Mono.error(
                                                                new ResponseStatusException(
                                                                        resp.statusCode(), body))))
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
