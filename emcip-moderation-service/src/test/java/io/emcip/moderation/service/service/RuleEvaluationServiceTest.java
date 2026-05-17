package io.emcip.moderation.service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class RuleEvaluationServiceTest {

    @Mock private ModerationRuleRepository repository;

    @InjectMocks private RuleEvaluationService service;

    @Test
    void evaluateMatchesKeywordRule() {
        UUID tenantId = UUID.randomUUID();
        ModerationRule rule = new ModerationRule();
        rule.setName("block-spam");
        rule.setRuleType("KEYWORD");
        rule.setPattern("spam");
        rule.setSeverity("HIGH");
        rule.setAction("BLOCK");
        rule.setEnabled(true);

        when(repository.findByEnabledTrueAndTenantId(tenantId)).thenReturn(Flux.just(rule));

        var result = service.evaluate("this is spam content", tenantId.toString());

        assertThat(result).isPresent();
        assertThat(result.get().ruleName()).isEqualTo("block-spam");
        assertThat(result.get().action()).isEqualTo("BLOCK");
    }

    @Test
    void evaluateReturnsEmptyWhenNoRulesMatch() {
        UUID tenantId = UUID.randomUUID();
        ModerationRule rule = new ModerationRule();
        rule.setRuleType("KEYWORD");
        rule.setPattern("badword");
        rule.setEnabled(true);
        rule.setSeverity("LOW");
        rule.setAction("FLAG");

        when(repository.findByEnabledTrueAndTenantId(tenantId)).thenReturn(Flux.just(rule));

        var result = service.evaluate("clean content", tenantId.toString());

        assertThat(result).isEmpty();
    }

    @Test
    void evaluateReturnsEmptyForBlankText() {
        var result = service.evaluate("", UUID.randomUUID().toString());
        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void evaluateReturnsEmptyForNullTenantId() {
        var result = service.evaluate("some text", null);
        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }
}
