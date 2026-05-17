package io.emcip.moderation.service.service;

import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class RuleEvaluationService {

    private final ModerationRuleRepository repository;

    public Optional<EvaluationResult> evaluate(String text, String tenantId) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }

        List<ModerationRule> rules =
                repository
                        .findByEnabledTrueAndTenantId(UUID.fromString(tenantId))
                        .collectList()
                        .block();

        if (rules == null) {
            return Optional.empty();
        }

        for (ModerationRule rule : rules) {
            boolean matched =
                    switch (rule.getRuleType()) {
                        case "KEYWORD" ->
                                text.toLowerCase().contains(rule.getPattern().toLowerCase());
                        case "REGEX" -> text.matches("(?i).*" + rule.getPattern() + ".*");
                        case "LENGTH" -> text.length() > Integer.parseInt(rule.getPattern());
                        default -> false;
                    };
            if (matched) {
                return Optional.of(
                        new EvaluationResult(
                                rule.getName(),
                                rule.getSeverity(),
                                rule.getAction(),
                                rule.getRuleType()));
            }
        }
        return Optional.empty();
    }

    public record EvaluationResult(
            String ruleName, String severity, String action, String ruleType) {}
}
