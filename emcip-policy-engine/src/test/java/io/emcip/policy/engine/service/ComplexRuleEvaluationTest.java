package io.emcip.policy.engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComplexRuleEvaluationTest {

    @Test
    void timeBasedRule_matchesDuringOvernightWindow() {
        Map<String, Object> conditions =
                Map.of("timeWindowStart", "22:00", "timeWindowEnd", "06:00");
        ZonedDateTime inWindow = ZonedDateTime.of(2026, 4, 22, 23, 0, 0, 0, ZoneOffset.UTC);
        assertThat(PolicyEvaluationService.matchesTimeWindow(conditions, inWindow)).isTrue();
    }

    @Test
    void timeBasedRule_doesNotMatchOutsideOvernightWindow() {
        Map<String, Object> conditions =
                Map.of("timeWindowStart", "22:00", "timeWindowEnd", "06:00");
        ZonedDateTime outside = ZonedDateTime.of(2026, 4, 22, 14, 0, 0, 0, ZoneOffset.UTC);
        assertThat(PolicyEvaluationService.matchesTimeWindow(conditions, outside)).isFalse();
    }

    @Test
    void timeBasedRule_matchesDuringDaytimeWindow() {
        Map<String, Object> conditions =
                Map.of("timeWindowStart", "09:00", "timeWindowEnd", "17:00");
        ZonedDateTime inWindow = ZonedDateTime.of(2026, 4, 22, 12, 0, 0, 0, ZoneOffset.UTC);
        assertThat(PolicyEvaluationService.matchesTimeWindow(conditions, inWindow)).isTrue();
    }

    @Test
    void contextAwareRule_matchesWhenThreadLongEnough() {
        Map<String, Object> conditions = Map.of("minThreadLength", 5);
        Map<String, Object> context = Map.of("threadLength", 7);
        assertThat(PolicyEvaluationService.matchesContextConditions(conditions, context)).isTrue();
    }

    @Test
    void contextAwareRule_doesNotMatchShortThread() {
        Map<String, Object> conditions = Map.of("minThreadLength", 5);
        Map<String, Object> context = Map.of("threadLength", 3);
        assertThat(PolicyEvaluationService.matchesContextConditions(conditions, context)).isFalse();
    }

    @Test
    void nullConditions_alwaysMatch() {
        assertThat(
                        PolicyEvaluationService.matchesTimeWindow(
                                null, ZonedDateTime.now(ZoneOffset.UTC)))
                .isTrue();
        assertThat(PolicyEvaluationService.matchesContextConditions(null, Map.of())).isTrue();
    }
}
