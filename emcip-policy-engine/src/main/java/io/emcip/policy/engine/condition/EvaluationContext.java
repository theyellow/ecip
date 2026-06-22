package io.emcip.policy.engine.condition;

import java.time.ZonedDateTime;

public record EvaluationContext(
        String intent,
        double confidence,
        String language,
        int threadLength,
        int groupSize,
        int messageLength,
        int senderAccountAgeDays,
        int senderFlaggedCount,
        int senderFlagWindowDays,
        ZonedDateTime now) {}
