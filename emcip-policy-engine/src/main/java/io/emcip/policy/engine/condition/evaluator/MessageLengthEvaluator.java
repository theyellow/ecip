package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MessageLengthEvaluator implements ConditionEvaluator {

    @Override
    public ConditionType type() {
        return ConditionType.MESSAGE_LENGTH;
    }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        int len = ctx.messageLength();
        if (params.containsKey("min") && len < ((Number) params.get("min")).intValue())
            return false;
        if (params.containsKey("max") && len > ((Number) params.get("max")).intValue())
            return false;
        return true;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        StringBuilder sb = new StringBuilder(String.valueOf(ctx.messageLength()) + " chars");
        if (params.containsKey("min")) sb.append(", min=").append(params.get("min"));
        if (params.containsKey("max")) sb.append(", max=").append(params.get("max"));
        return sb.toString();
    }
}
