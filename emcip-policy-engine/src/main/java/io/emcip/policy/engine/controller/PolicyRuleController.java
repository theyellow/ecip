package io.emcip.policy.engine.controller;

import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Tag(name = "Policy Rules", description = "Manage active policy rules and view rule history")
@RestController
@RequestMapping("/api/policy-rules")
@RequiredArgsConstructor
public class PolicyRuleController {

    private final PolicyRuleConfigRepository repository;

    @Operation(summary = "List active policy rules")
    @GetMapping
    public Flux<PolicyRuleConfig> listActive() {
        return Mono.fromCallable(repository::findByActiveTrueOrderByPriorityAsc)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .take(200);
    }

    @Operation(summary = "Create a new policy rule")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PolicyRuleConfig> create(@RequestBody PolicyRuleConfig rule) {
        if (rule.getTenantId() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId is required"));
        }
        rule.setId(UUID.randomUUID().toString());
        if (rule.getTargetIntent() == null || rule.getTargetIntent().isBlank()) {
            rule.setTargetIntent("*");
        }
        if (rule.getMinConfidence() == null) rule.setMinConfidence(0.0);
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getActive() == null) rule.setActive(true);
        if (rule.getRuleVersion() == null) rule.setRuleVersion(1);
        return Mono.fromCallable(() -> repository.save(rule))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Update an existing policy rule")
    @PutMapping("/{id}")
    public Mono<PolicyRuleConfig> update(
            @PathVariable String id, @RequestBody PolicyRuleConfig rule) {
        return Mono.fromCallable(() -> repository.findById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        opt ->
                                opt.map(
                                                existing -> {
                                                    existing.setName(rule.getName());
                                                    existing.setTargetIntent(
                                                            rule.getTargetIntent() != null
                                                                    ? rule.getTargetIntent()
                                                                    : existing.getTargetIntent());
                                                    existing.setAction(rule.getAction());
                                                    existing.setPriority(rule.getPriority());
                                                    if (rule.getActive() != null) {
                                                        existing.setActive(rule.getActive());
                                                    }
                                                    if (rule.getMinConfidence() != null) {
                                                        existing.setMinConfidence(
                                                                rule.getMinConfidence());
                                                    }
                                                    existing.setMaxConfidence(
                                                            rule.getMaxConfidence());
                                                    existing.setDescription(rule.getDescription());
                                                    existing.setReason(rule.getReason());
                                                    existing.setEffectiveFrom(
                                                            rule.getEffectiveFrom());
                                                    existing.setEffectiveTo(rule.getEffectiveTo());
                                                    return Mono.fromCallable(
                                                                    () -> repository.save(existing))
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic());
                                                })
                                        .orElse(
                                                Mono.error(
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND))));
    }

    @Operation(summary = "Delete a policy rule")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String id) {
        return Mono.fromRunnable(() -> repository.deleteById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "List version history for a rule by name")
    @GetMapping("/history/{name}")
    public Mono<List<PolicyRuleConfig>> history(@PathVariable String name) {
        return Mono.fromCallable(
                        () ->
                                repository.findAll().stream()
                                        .filter(r -> name.equals(r.getName()))
                                        .sorted(
                                                Comparator.comparingInt(
                                                        PolicyRuleConfig::getRuleVersion))
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }
}
