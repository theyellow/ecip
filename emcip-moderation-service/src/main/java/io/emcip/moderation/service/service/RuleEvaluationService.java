package io.emcip.moderation.service.service;

import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class RuleEvaluationService {

    private static final int REGEX_TIMEOUT_MS = 1_000;

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
                        case "REGEX" -> matchesWithTimeout(rule.getPattern(), text);
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

    private boolean matchesWithTimeout(String pattern, String text) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> future =
                executor.submit(
                        () ->
                                Pattern.compile("(?i).*" + pattern + ".*", Pattern.DOTALL)
                                        .matcher(text)
                                        .matches());
        try {
            return future.get(REGEX_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn(
                    "Regex evaluation timed out for pattern '{}' on text length {}",
                    pattern,
                    text.length());
            return false;
        } catch (ExecutionException e) {
            log.warn(
                    "Regex evaluation failed for pattern '{}': {}",
                    pattern,
                    e.getCause().getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    public record EvaluationResult(
            String ruleName, String severity, String action, String ruleType) {}
}
