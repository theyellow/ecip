package io.emcip.moderation.service.service;

import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class RuleEvaluationService {

    private final ModerationRuleRepository repository;

    private volatile List<ModerationRule> cachedRules = Collections.emptyList();

    @PostConstruct
    public void loadRules() {
        refreshRules();
    }

    @Scheduled(fixedDelay = 300_000)
    public void refreshRules() {
        repository
                .findByEnabledTrue()
                .collectList()
                .doOnNext(
                        rules -> {
                            cachedRules = rules;
                            log.info("Loaded {} moderation rules", rules.size());
                        })
                .subscribe();
    }

    public Optional<EvaluationResult> evaluate(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (ModerationRule rule : cachedRules) {
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
