package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GroupSizeEvaluator implements ConditionEvaluator {

    @Override
    public ConditionType type() {
        return ConditionType.GROUP_SIZE;
    }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        int min = ((Number) params.getOrDefault("min", 0)).intValue();
        return ctx.groupSize() >= min;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        return ctx.groupSize()
                + " >= "
                + ((Number) params.getOrDefault("min", 0)).intValue()
                + " members";
    }
}
