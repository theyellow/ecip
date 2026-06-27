package io.emcip.intent.classifier.controller;

import io.emcip.intent.classifier.dto.IntentRuleDto;
import io.emcip.intent.classifier.entity.IntentRule;
import io.emcip.intent.classifier.repository.IntentRuleRepository;
import io.emcip.intent.classifier.service.IntentClassificationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/intent-rules")
@RequiredArgsConstructor
@Slf4j
public class IntentRuleController {

    private final IntentRuleRepository repository;
    private final IntentClassificationService classificationService;

    @GetMapping
    public List<IntentRuleDto> list(
            @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
        List<IntentRule> rules = new ArrayList<>();
        if (tenantId != null) {
            rules.addAll(repository.findByTenantIdOrderByPriorityAsc(tenantId));
        }
        rules.addAll(repository.findByTenantIdIsNullOrderByPriorityAsc());
        return rules.stream().map(this::toDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IntentRuleDto create(
            @RequestBody IntentRuleDto dto,
            @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
        var now = Instant.now();
        var rule =
                IntentRule.builder()
                        .name(dto.name())
                        .description(dto.description())
                        .matchMode(dto.matchMode())
                        .pattern(dto.pattern())
                        .intent(dto.intent())
                        .confidence(dto.confidence())
                        .priority(dto.priority() != null ? dto.priority() : 100)
                        .active(true)
                        .tenantId(tenantId)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        var saved = repository.save(rule);
        classificationService.refreshRules();
        return toDto(saved);
    }

    @PutMapping("/{id}")
    public IntentRuleDto update(
            @PathVariable String id,
            @RequestBody IntentRuleDto dto,
            @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
        var rule =
                repository
                        .findById(id)
                        .filter(r -> tenantId == null || tenantId.equals(r.getTenantId()))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        rule.setName(dto.name());
        rule.setDescription(dto.description());
        rule.setMatchMode(dto.matchMode());
        rule.setPattern(dto.pattern());
        rule.setIntent(dto.intent());
        rule.setConfidence(dto.confidence());
        rule.setPriority(dto.priority());
        rule.setActive(dto.active());
        rule.setUpdatedAt(Instant.now());
        var saved = repository.save(rule);
        classificationService.refreshRules();
        return toDto(saved);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String id,
            @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId) {
        var rule =
                repository
                        .findById(id)
                        .filter(r -> tenantId == null || tenantId.equals(r.getTenantId()))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repository.delete(rule);
        classificationService.refreshRules();
    }

    private IntentRuleDto toDto(IntentRule r) {
        return new IntentRuleDto(
                r.getId(),
                r.getName(),
                r.getDescription(),
                r.getMatchMode(),
                r.getPattern(),
                r.getIntent(),
                r.getConfidence(),
                r.getPriority(),
                r.getActive(),
                r.getTenantId(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
