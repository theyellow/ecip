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
        int min = ParamUtil.getInt(params, "min", 0);
        return ctx.threadLength() >= min;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        return ctx.threadLength() + " >= " + ParamUtil.getInt(params, "min", 0);
    }
}
