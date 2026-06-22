package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TimeWindowEvaluator implements ConditionEvaluator {

    @Override
    public ConditionType type() {
        return ConditionType.TIME_WINDOW;
    }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        String start = (String) params.get("start");
        String end = (String) params.get("end");
        if (start == null || end == null) return true;
        int now = ctx.now().getHour() * 60 + ctx.now().getMinute();
        int s = parseHhmm(start);
        int e = parseHhmm(end);
        return s <= e ? (now >= s && now < e) : (now >= s || now < e);
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        String nowStr = String.format("%02d:%02d", ctx.now().getHour(), ctx.now().getMinute());
        return nowStr
                + " in ["
                + params.getOrDefault("start", "?")
                + "–"
                + params.getOrDefault("end", "?")
                + "]";
    }

    private static int parseHhmm(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }
}
