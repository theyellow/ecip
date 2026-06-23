package io.emcip.policy.engine.service;

import io.emcip.policy.engine.condition.ConditionEvaluatorRegistry;
import io.emcip.policy.engine.condition.ConditionType;
import io.emcip.policy.engine.condition.EvaluationContext;
import io.emcip.policy.engine.dto.ConditionResult;
import io.emcip.policy.engine.dto.DryRunResult;
import io.emcip.policy.engine.dto.GroupResult;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DryRunService {

    private final ConditionEvaluatorRegistry registry;

    public DryRunResult evaluate(PolicyRuleConfig rule, EvaluationContext ctx) {
        // Intent + confidence check
        boolean intentOk =
                "*".equals(rule.getTargetIntent())
                        || (rule.getTargetIntent() != null
                                && rule.getTargetIntent().equals(ctx.intent()));
        boolean confOk =
                ctx.confidence()
                                >= (rule.getMinConfidence() != null ? rule.getMinConfidence() : 0.0)
                        && (rule.getMaxConfidence() == null
                                || ctx.confidence() <= rule.getMaxConfidence());

        if (!intentOk || !confOk) {
            return new DryRunResult(false, -1, rule.getAction(), List.of());
        }

        Map<String, Object> conditions = rule.getConditions();
        if (conditions == null || !conditions.containsKey("groups")) {
            return new DryRunResult(true, -1, rule.getAction(), List.of());
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) conditions.get("groups");
        if (groups.isEmpty()) {
            return new DryRunResult(true, -1, rule.getAction(), List.of());
        }

        List<GroupResult> groupResults = new ArrayList<>();
        int matchedGroupIndex = -1;

        for (int i = 0; i < groups.size(); i++) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conds =
                    (List<Map<String, Object>>) groups.get(i).getOrDefault("conditions", List.of());

            List<ConditionResult> condResults = new ArrayList<>();
            boolean groupPassed = true;

            for (Map<String, Object> cond : conds) {
                boolean passed;
                String detail;
                try {
                    passed = registry.evaluate(cond, ctx);
                    detail = registry.detail(cond, ctx);
                } catch (Exception e) {
                    passed = false;
                    detail = "Error: " + e.getMessage();
                }
                ConditionType type = ConditionType.valueOf((String) cond.get("type"));
                condResults.add(new ConditionResult(type, passed, detail));
                if (!passed) groupPassed = false;
            }

            groupResults.add(new GroupResult(i, groupPassed, condResults));
            if (groupPassed && matchedGroupIndex == -1) {
                matchedGroupIndex = i;
            }
        }

        return new DryRunResult(
                matchedGroupIndex != -1, matchedGroupIndex, rule.getAction(), groupResults);
    }
}
