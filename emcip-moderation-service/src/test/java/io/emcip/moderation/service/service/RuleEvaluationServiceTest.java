package io.emcip.moderation.service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class RuleEvaluationServiceTest {

    @Mock private ModerationRuleRepository repository;

    private RuleEvaluationService service;

    private ModerationRule keywordRule;
    private ModerationRule regexRule;
    private ModerationRule lengthRule;

    @BeforeEach
    void setUp() {
        service = new RuleEvaluationService(repository);

        keywordRule =
                ModerationRule.builder()
                        .id(1L)
                        .name("keyword-spam")
                        .ruleType("KEYWORD")
                        .pattern("spam")
                        .severity("HIGH")
                        .action("FLAG")
                        .enabled(true)
                        .build();

        regexRule =
                ModerationRule.builder()
                        .id(2L)
                        .name("regex-phone")
                        .ruleType("REGEX")
                        .pattern("\\d{3}-\\d{4}")
                        .severity("MEDIUM")
                        .action("FLAG")
                        .enabled(true)
                        .build();

        lengthRule =
                ModerationRule.builder()
                        .id(3L)
                        .name("excessive-length")
                        .ruleType("LENGTH")
                        .pattern("100")
                        .severity("LOW")
                        .action("FLAG")
                        .enabled(true)
                        .build();
    }

    @Test
    void evaluate_keywordRule_matchesCaseInsensitive() {
        when(repository.findByEnabledTrue()).thenReturn(Flux.just(keywordRule));
        service.refreshRules();

        Optional<RuleEvaluationService.EvaluationResult> result =
                service.evaluate("This is SPAM content");

        assertThat(result).isPresent();
        assertThat(result.get().ruleName()).isEqualTo("keyword-spam");
        assertThat(result.get().severity()).isEqualTo("HIGH");
        assertThat(result.get().action()).isEqualTo("FLAG");
        assertThat(result.get().ruleType()).isEqualTo("KEYWORD");
    }

    @Test
    void evaluate_keywordRule_noMatch() {
        when(repository.findByEnabledTrue()).thenReturn(Flux.just(keywordRule));
        service.refreshRules();

        Optional<RuleEvaluationService.EvaluationResult> result =
                service.evaluate("This is a clean message");

        assertThat(result).isEmpty();
    }

    @Test
    void evaluate_regexRule_matches() {
        when(repository.findByEnabledTrue()).thenReturn(Flux.just(regexRule));
        service.refreshRules();

        Optional<RuleEvaluationService.EvaluationResult> result =
                service.evaluate("Call me at 555-1234 anytime");

        assertThat(result).isPresent();
        assertThat(result.get().ruleName()).isEqualTo("regex-phone");
        assertThat(result.get().ruleType()).isEqualTo("REGEX");
    }

    @Test
    void evaluate_lengthRule_triggersWhenExceedsLimit() {
        when(repository.findByEnabledTrue()).thenReturn(Flux.just(lengthRule));
        service.refreshRules();

        String longText = "a".repeat(101);
        Optional<RuleEvaluationService.EvaluationResult> result = service.evaluate(longText);

        assertThat(result).isPresent();
        assertThat(result.get().ruleName()).isEqualTo("excessive-length");
        assertThat(result.get().ruleType()).isEqualTo("LENGTH");
        assertThat(result.get().severity()).isEqualTo("LOW");
    }

    @Test
    void evaluate_lengthRule_noMatchWhenUnderLimit() {
        when(repository.findByEnabledTrue()).thenReturn(Flux.just(lengthRule));
        service.refreshRules();

        String shortText = "a".repeat(100);
        Optional<RuleEvaluationService.EvaluationResult> result = service.evaluate(shortText);

        assertThat(result).isEmpty();
    }

    @Test
    void evaluate_emptyText_returnsEmpty() {
        when(repository.findByEnabledTrue()).thenReturn(Flux.just(keywordRule));
        service.refreshRules();

        assertThat(service.evaluate("")).isEmpty();
        assertThat(service.evaluate(null)).isEmpty();
        assertThat(service.evaluate("   ")).isEmpty();
    }

    @Test
    void evaluate_multipleRules_firstMatchWins() {
        when(repository.findByEnabledTrue())
                .thenReturn(Flux.just(keywordRule, lengthRule, regexRule));
        service.refreshRules();

        // "spam" triggers keyword rule first; length rule (>100 chars) also applies
        // but keyword rule comes first in the list
        String text = "spam " + "a".repeat(200);
        Optional<RuleEvaluationService.EvaluationResult> result = service.evaluate(text);

        assertThat(result).isPresent();
        assertThat(result.get().ruleName()).isEqualTo("keyword-spam");
        assertThat(result.get().ruleType()).isEqualTo("KEYWORD");
    }
}
