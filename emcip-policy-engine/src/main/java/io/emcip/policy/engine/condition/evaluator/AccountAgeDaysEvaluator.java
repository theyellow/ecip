package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AccountAgeDaysEvaluator implements ConditionEvaluator {

    @Override
    public ConditionType type() {
        return ConditionType.ACCOUNT_AGE_DAYS;
    }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        int max = ((Number) params.getOrDefault("max", Integer.MAX_VALUE)).intValue();
        return ctx.senderAccountAgeDays() <= max;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        int max = ((Number) params.getOrDefault("max", Integer.MAX_VALUE)).intValue();
        return ctx.senderAccountAgeDays() + "d <= " + max + "d";
    }
}
