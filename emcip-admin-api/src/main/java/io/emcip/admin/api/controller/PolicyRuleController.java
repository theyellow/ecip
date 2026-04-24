package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.PolicyRule;
import io.emcip.admin.api.repository.PolicyRuleRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
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

@RestController
@RequestMapping("/api/policy-rules")
@RequiredArgsConstructor
public class PolicyRuleController {

    private final PolicyRuleRepository policyRuleRepository;
    private final R2dbcEntityTemplate r2dbcEntityTemplate;

    @GetMapping
    public Flux<PolicyRule> listActiveRules() {
        return policyRuleRepository.findActiveRules();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PolicyRule> createRule(@RequestBody PolicyRule rule) {
        rule.setId(UUID.randomUUID().toString());
        rule.setCreatedAt(Instant.now());
        if (rule.getRuleVersion() == null) {
            rule.setRuleVersion(1);
        }
        if (rule.getActive() == null) {
            rule.setActive(true);
        }
        if (rule.getTargetIntent() == null || rule.getTargetIntent().isBlank()) {
            rule.setTargetIntent("*");
        }
        if (rule.getMinConfidence() == null) {
            rule.setMinConfidence(0.0);
        }
        return r2dbcEntityTemplate.insert(rule);
    }

    @PutMapping("/{id}")
    public Mono<PolicyRule> updateRule(
            @PathVariable("id") String id, @RequestBody PolicyRule rule) {
        return policyRuleRepository
                .findById(id)
                .flatMap(
                        existing -> {
                            existing.setName(rule.getName());
                            existing.setTargetIntent(
                                    rule.getTargetIntent() != null
                                                    && !rule.getTargetIntent().isBlank()
                                            ? rule.getTargetIntent()
                                            : existing.getTargetIntent());
                            existing.setAction(rule.getAction());
                            existing.setPriority(rule.getPriority());
                            existing.setActive(
                                    rule.getActive() != null
                                            ? rule.getActive()
                                            : existing.getActive());
                            existing.setMinConfidence(
                                    rule.getMinConfidence() != null
                                            ? rule.getMinConfidence()
                                            : existing.getMinConfidence());
                            existing.setMaxConfidence(rule.getMaxConfidence());
                            existing.setDescription(rule.getDescription());
                            existing.setReason(rule.getReason());
                            existing.setEffectiveFrom(rule.getEffectiveFrom());
                            existing.setEffectiveTo(rule.getEffectiveTo());
                            return policyRuleRepository.save(existing);
                        });
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteRule(@PathVariable("id") String id) {
        return policyRuleRepository.deleteById(id);
    }

    @GetMapping("/history/{ruleName}")
    public Flux<PolicyRule> getRuleHistory(@PathVariable("ruleName") String ruleName) {
        return policyRuleRepository.findHistoryByName(ruleName);
    }
}
