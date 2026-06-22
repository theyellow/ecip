package io.emcip.policy.engine.condition;

import java.util.Map;

public interface ConditionEvaluator {
    ConditionType type();

    boolean evaluate(Map<String, Object> params, EvaluationContext ctx);

    /** Human-readable string explaining the result (used in dry-run). */
    String detail(Map<String, Object> params, EvaluationContext ctx);
}
