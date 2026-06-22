package io.emcip.policy.engine.controller;

import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.entity.PolicyRuleHistory;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import io.emcip.policy.engine.repository.PolicyRuleHistoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Tag(name = "Policy Rules", description = "Manage active policy rules and view rule history")
@RestController
@RequestMapping("/api/policy-rules")
@RequiredArgsConstructor
public class PolicyRuleController {

    private final PolicyRuleConfigRepository repository;
    private final PolicyRuleHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

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
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId is required"));
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

    @Operation(summary = "Update an existing policy rule; writes a history snapshot first")
    @PutMapping("/{id}")
    public Mono<PolicyRuleConfig> update(
            @PathVariable String id,
            @RequestBody PolicyRuleConfig rule,
            @RequestHeader(value = "X-Edited-By", required = false) String editedBy) {
        return Mono.fromCallable(
                        () -> {
                            PolicyRuleConfig existing =
                                    repository
                                            .findById(id)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND));

                            // Write snapshot before overwriting
                            PolicyRuleHistory snap = new PolicyRuleHistory();
                            snap.setId(UUID.randomUUID());
                            snap.setRuleId(existing.getId());
                            snap.setTenantId(existing.getTenantId());
                            snap.setSnapshot(toMap(existing));
                            snap.setEditedBy(editedBy);
                            snap.setEditedAt(Instant.now());
                            snap.setRuleVersion(
                                    existing.getRuleVersion() != null
                                            ? existing.getRuleVersion()
                                            : 1);
                            historyRepository.save(snap);

                            // Apply updates
                            existing.setName(rule.getName());
                            if (rule.getTargetIntent() != null) {
                                existing.setTargetIntent(rule.getTargetIntent());
                            }
                            existing.setAction(rule.getAction());
                            existing.setPriority(rule.getPriority());
                            if (rule.getActive() != null) {
                                existing.setActive(rule.getActive());
                            }
                            if (rule.getMinConfidence() != null) {
                                existing.setMinConfidence(rule.getMinConfidence());
                            }
                            existing.setMaxConfidence(rule.getMaxConfidence());
                            existing.setDescription(rule.getDescription());
                            existing.setReason(rule.getReason());
                            existing.setEffectiveFrom(rule.getEffectiveFrom());
                            existing.setEffectiveTo(rule.getEffectiveTo());
                            existing.setConditions(rule.getConditions());
                            existing.setRuleVersion(
                                    (existing.getRuleVersion() != null
                                                    ? existing.getRuleVersion()
                                                    : 1)
                                            + 1);

                            return repository.save(existing);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Delete a policy rule (no history snapshot written)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String id) {
        return Mono.fromRunnable(() -> repository.deleteById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "List version history snapshots for a rule")
    @GetMapping("/{id}/history")
    public Flux<PolicyRuleHistory> getHistory(@PathVariable String id) {
        return Mono.fromCallable(() -> historyRepository.findByRuleIdOrderByEditedAtDesc(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    private Map<String, Object> toMap(PolicyRuleConfig rule) {
        return objectMapper.convertValue(rule, new TypeReference<Map<String, Object>>() {});
    }
}
