package io.emcip.policy.engine.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PolicyRuleConfigTest {

    @Test
    void newRuleDefaultsToVersion1() {
        PolicyRuleConfig rule = new PolicyRuleConfig();
        assertThat(rule.getRuleVersion()).isEqualTo(1);
    }

    @Test
    void isEffectiveAt_whenNoTimeBounds() {
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setActive(true);
        assertThat(rule.isEffectiveAt(Instant.now())).isTrue();
    }

    @Test
    void isNotEffective_whenEffectiveToInPast() {
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setActive(true);
        rule.setEffectiveTo(Instant.now().minusSeconds(60));
        assertThat(rule.isEffectiveAt(Instant.now())).isFalse();
    }

    @Test
    void isNotEffective_whenEffectiveFromInFuture() {
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setActive(true);
        rule.setEffectiveFrom(Instant.now().plusSeconds(3600));
        assertThat(rule.isEffectiveAt(Instant.now())).isFalse();
    }

    @Test
    void isNotEffective_whenInactive() {
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setActive(false);
        assertThat(rule.isEffectiveAt(Instant.now())).isFalse();
    }
}
