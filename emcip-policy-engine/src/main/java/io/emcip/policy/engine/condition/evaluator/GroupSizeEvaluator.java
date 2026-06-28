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
        int min = ParamUtil.getInt(params, "min", 0);
        return ctx.groupSize() >= min;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        return ctx.groupSize() + " >= " + ParamUtil.getInt(params, "min", 0) + " members";
    }
}
