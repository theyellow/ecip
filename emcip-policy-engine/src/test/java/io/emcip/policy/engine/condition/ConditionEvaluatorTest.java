package io.emcip.policy.engine.condition;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.policy.engine.condition.evaluator.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {

    private static final ZonedDateTime NIGHT =
            ZonedDateTime.of(2026, 6, 22, 23, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime DAY =
            ZonedDateTime.of(2026, 6, 22, 12, 0, 0, 0, ZoneOffset.UTC);

    // ctx: intent=SPAM, conf=0.9, lang=en, threadLen=5, groupSize=120, msgLen=45,
    //       senderAccountAgeDays=2, senderFlaggedCount=1, senderFlagWindowDays=90
    private static EvaluationContext ctx(ZonedDateTime now) {
        return new EvaluationContext("SPAM", 0.9, "en", 5, 120, 45, 2, 1, 90, now);
    }

    // TIME_WINDOW
    @Test
    void timeWindow_overnight_inside() {
        assertThat(
                        new TimeWindowEvaluator()
                                .evaluate(Map.of("start", "22:00", "end", "06:00"), ctx(NIGHT)))
                .isTrue();
    }

    @Test
    void timeWindow_overnight_outside() {
        assertThat(
                        new TimeWindowEvaluator()
                                .evaluate(Map.of("start", "22:00", "end", "06:00"), ctx(DAY)))
                .isFalse();
    }

    @Test
    void timeWindow_sameDay_inside() {
        ZonedDateTime noon = ZonedDateTime.of(2026, 6, 22, 13, 0, 0, 0, ZoneOffset.UTC);
        assertThat(
                        new TimeWindowEvaluator()
                                .evaluate(Map.of("start", "09:00", "end", "17:00"), ctx(noon)))
                .isTrue();
    }

    @Test
    void timeWindow_detail_format() {
        String d =
                new TimeWindowEvaluator()
                        .detail(Map.of("start", "22:00", "end", "06:00"), ctx(NIGHT));
        assertThat(d).contains("23:00").contains("22:00").contains("06:00");
    }

    // MIN_THREAD_LENGTH
    @Test
    void minThreadLength_passes() {
        assertThat(new MinThreadLengthEvaluator().evaluate(Map.of("min", 3), ctx(DAY))).isTrue();
    }

    @Test
    void minThreadLength_fails() {
        EvaluationContext short_ = new EvaluationContext("S", 0.9, "en", 1, 0, 0, 0, 0, 90, DAY);
        assertThat(new MinThreadLengthEvaluator().evaluate(Map.of("min", 5), short_)).isFalse();
    }

    // ACCOUNT_AGE_DAYS
    @Test
    void accountAge_passes_young() {
        assertThat(new AccountAgeDaysEvaluator().evaluate(Map.of("max", 7), ctx(DAY))).isTrue();
    }

    @Test
    void accountAge_fails_old() {
        EvaluationContext old = new EvaluationContext("S", 0.9, "en", 0, 0, 0, 100, 0, 90, DAY);
        assertThat(new AccountAgeDaysEvaluator().evaluate(Map.of("max", 7), old)).isFalse();
    }

    // MESSAGE_LANGUAGE
    @Test
    void language_include_match() {
        assertThat(
                        new MessageLanguageEvaluator()
                                .evaluate(
                                        Map.of("languages", List.of("en", "de"), "mode", "INCLUDE"),
                                        ctx(DAY)))
                .isTrue();
    }

    @Test
    void language_include_no_match() {
        assertThat(
                        new MessageLanguageEvaluator()
                                .evaluate(
                                        Map.of("languages", List.of("de", "fr"), "mode", "INCLUDE"),
                                        ctx(DAY)))
                .isFalse();
    }

    @Test
    void language_exclude_match() {
        assertThat(
                        new MessageLanguageEvaluator()
                                .evaluate(
                                        Map.of("languages", List.of("ru"), "mode", "EXCLUDE"),
                                        ctx(DAY)))
                .isTrue();
    }

    // GROUP_SIZE
    @Test
    void groupSize_passes() {
        assertThat(new GroupSizeEvaluator().evaluate(Map.of("min", 50), ctx(DAY))).isTrue();
    }

    @Test
    void groupSize_fails() {
        EvaluationContext small = new EvaluationContext("S", 0.9, "en", 0, 10, 0, 0, 0, 90, DAY);
        assertThat(new GroupSizeEvaluator().evaluate(Map.of("min", 100), small)).isFalse();
    }

    // MESSAGE_LENGTH
    @Test
    void messageLength_withinBounds() {
        assertThat(new MessageLengthEvaluator().evaluate(Map.of("min", 10, "max", 100), ctx(DAY)))
                .isTrue();
    }

    @Test
    void messageLength_tooShort() {
        assertThat(new MessageLengthEvaluator().evaluate(Map.of("min", 100), ctx(DAY))).isFalse();
    }

    @Test
    void messageLength_tooLong() {
        assertThat(new MessageLengthEvaluator().evaluate(Map.of("max", 10), ctx(DAY))).isFalse();
    }

    // FLAGGED_COUNT
    @Test
    void flaggedCount_passes() {
        assertThat(
                        new FlaggedCountEvaluator()
                                .evaluate(Map.of("min", 1, "windowDays", 30), ctx(DAY)))
                .isTrue();
    }

    @Test
    void flaggedCount_fails_insufficient() {
        assertThat(
                        new FlaggedCountEvaluator()
                                .evaluate(Map.of("min", 3, "windowDays", 30), ctx(DAY)))
                .isFalse();
    }

    @Test
    void flaggedCount_fails_window_too_wide() {
        assertThat(
                        new FlaggedCountEvaluator()
                                .evaluate(Map.of("min", 1, "windowDays", 120), ctx(DAY)))
                .isFalse();
    }
}
