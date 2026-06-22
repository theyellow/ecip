package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MessageLanguageEvaluator implements ConditionEvaluator {

    @Override
    public ConditionType type() {
        return ConditionType.MESSAGE_LANGUAGE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        List<String> languages = (List<String>) params.getOrDefault("languages", List.of());
        String mode = (String) params.getOrDefault("mode", "INCLUDE");
        boolean inList = languages.stream().anyMatch(l -> l.equalsIgnoreCase(ctx.language()));
        return "INCLUDE".equals(mode) ? inList : !inList;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        List<String> languages = (List<String>) params.getOrDefault("languages", List.of());
        String mode = (String) params.getOrDefault("mode", "INCLUDE");
        return ctx.language() + " " + mode + " " + languages;
    }
}
