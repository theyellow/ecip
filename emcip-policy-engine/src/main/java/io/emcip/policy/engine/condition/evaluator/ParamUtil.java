package io.emcip.policy.engine.condition.evaluator;

/** Utility for safely reading numeric params that may arrive as String or Number. */
final class ParamUtil {

    private ParamUtil() {}

    static int getInt(java.util.Map<String, Object> params, String key, int defaultValue) {
        Object v = params.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
