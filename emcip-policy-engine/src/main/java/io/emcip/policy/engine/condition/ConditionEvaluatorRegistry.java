package io.emcip.policy.engine.condition;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ConditionEvaluatorRegistry {

    private final Map<ConditionType, ConditionEvaluator> evaluators;

    public ConditionEvaluatorRegistry(List<ConditionEvaluator> evaluators) {
        this.evaluators =
                evaluators.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(ConditionEvaluator::type, e -> e));
    }

    public boolean evaluate(Map<String, Object> condition, EvaluationContext ctx) {
        ConditionEvaluator ev = resolve(condition);
        return ev.evaluate(condition, ctx);
    }

    public String detail(Map<String, Object> condition, EvaluationContext ctx) {
        ConditionEvaluator ev = resolve(condition);
        return ev.detail(condition, ctx);
    }

    private ConditionEvaluator resolve(Map<String, Object> condition) {
        String typeStr = (String) condition.get("type");
        if (typeStr == null) throw new IllegalArgumentException("Condition missing 'type'");
        ConditionType type = ConditionType.valueOf(typeStr);
        ConditionEvaluator ev = evaluators.get(type);
        if (ev == null) throw new IllegalArgumentException("No evaluator registered for: " + type);
        return ev;
    }
}
