package io.emcip.moderation.service.controller;

import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
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

@RestController
@RequestMapping("/api/moderation-rules")
@RequiredArgsConstructor
@Tag(name = "Moderation Rules", description = "Create, read, update, and delete moderation rules")
public class ModerationRuleController {

    private final ModerationRuleRepository repository;

    @GetMapping
    @Operation(summary = "List all moderation rules")
    public Flux<ModerationRule> list() {
        return repository.findAllOrderedByTenantId(
                UUID.fromString(io.emcip.common.tenant.TenantContext.getTenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new moderation rule")
    public Mono<ModerationRule> create(@RequestBody ModerationRule rule) {
        rule.setId(null);
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(Instant.now());
        if (rule.getSeverity() == null || rule.getSeverity().isBlank()) {
            rule.setSeverity("MEDIUM");
        }
        if (rule.getAction() == null || rule.getAction().isBlank()) {
            rule.setAction("FLAG");
        }
        rule.setEnabled(true);
        rule.setTenantId(UUID.fromString(io.emcip.common.tenant.TenantContext.getTenantId()));
        return repository.save(rule);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing moderation rule")
    public Mono<ModerationRule> update(@PathVariable Long id, @RequestBody ModerationRule rule) {
        return repository
                .findByIdAndTenantId(
                        id, UUID.fromString(io.emcip.common.tenant.TenantContext.getTenantId()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(
                        existing -> {
                            existing.setName(rule.getName());
                            existing.setRuleType(rule.getRuleType());
                            existing.setPattern(rule.getPattern());
                            existing.setSeverity(rule.getSeverity());
                            existing.setAction(rule.getAction());
                            existing.setEnabled(rule.isEnabled());
                            existing.setUpdatedAt(Instant.now());
                            return repository.save(existing);
                        });
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a moderation rule")
    public Mono<Void> delete(@PathVariable Long id) {
        return repository.deleteById(id);
    }
}
