package io.emcip.policy.engine.dto;

import java.util.List;

public record GroupResult(int index, boolean matched, List<ConditionResult> conditionResults) {}
