package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FlaggedCountEvaluator implements ConditionEvaluator {

    @Override
    public ConditionType type() {
        return ConditionType.FLAGGED_COUNT;
    }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        int min = ((Number) params.getOrDefault("min", 0)).intValue();
        int windowDays = ((Number) params.getOrDefault("windowDays", 30)).intValue();
        // Conservatively fail if the rule requires a longer window than pre-computed.
        if (windowDays > ctx.senderFlagWindowDays()) return false;
        return ctx.senderFlaggedCount() >= min;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        int min = ((Number) params.getOrDefault("min", 0)).intValue();
        int windowDays = ((Number) params.getOrDefault("windowDays", 30)).intValue();
        return ctx.senderFlaggedCount() + " >= " + min + " in last " + windowDays + "d";
    }
}
