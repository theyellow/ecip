package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MinThreadLengthEvaluator implements ConditionEvaluator {

    @Override
    public ConditionType type() {
        return ConditionType.MIN_THREAD_LENGTH;
    }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        int min = ((Number) params.getOrDefault("min", 0)).intValue();
        return ctx.threadLength() >= min;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        return ctx.threadLength() + " >= " + ((Number) params.getOrDefault("min", 0)).intValue();
    }
}
