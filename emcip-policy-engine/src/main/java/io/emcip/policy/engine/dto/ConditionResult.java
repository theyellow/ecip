package io.emcip.policy.engine.dto;

import io.emcip.policy.engine.condition.ConditionType;

public record ConditionResult(ConditionType type, boolean passed, String detail) {}
