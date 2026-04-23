package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.PolicyRule;
import io.emcip.admin.api.repository.PolicyRuleRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @GetMapping("/history/{ruleName}")
    public Flux<PolicyRule> getRuleHistory(@PathVariable("ruleName") String ruleName) {
        return policyRuleRepository.findHistoryByName(ruleName);
    }
}
