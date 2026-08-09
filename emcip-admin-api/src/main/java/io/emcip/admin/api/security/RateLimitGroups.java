package io.emcip.admin.api.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

/**
 * Decides which rate-limit bucket a request belongs to.
 *
 * <p>Kept separate from {@code RateLimitWebFilter} because this half is pure data: it needs no
 * exchange, no security context, and no reactive plumbing to test. It is also the half that gets
 * edited whenever endpoints are added.
 *
 * <p>Per-key limiters live in bounded Caffeine caches rather than the Resilience4j registry. The
 * registry never evicts, so an attacker rotating keys would grow it without limit — turning a
 * denial-of-service control into a denial-of-service vector.
 */
@Component
public class RateLimitGroups {

    public enum Group {
        AUTH("auth", true),
        LLM_TRIGGER("llm-trigger", false),
        ADMIN_CRUD("admin-crud", false);

        private final String instanceName;
        private final boolean keyByIp;

        Group(String instanceName, boolean keyByIp) {
            this.instanceName = instanceName;
            this.keyByIp = keyByIp;
        }

        public String instanceName() {
            return instanceName;
        }

        /** Auth endpoints are unauthenticated, so the only available identity is the client IP. */
        public boolean keyByIp() {
            return keyByIp;
        }
    }

    private static final int MAX_KEYS_PER_GROUP = 10_000;

    /**
     * Long enough that a limiter is not recreated mid-window (the configured refresh period is
     * 60s); short enough that idle keys are released quickly. A limiter recreated after this long
     * starts full, which is correct — the key made no requests during the window.
     */
    private static final Duration KEY_TTL = Duration.ofMinutes(2);

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RateLimiterRegistry registry;
    private final Map<Group, Cache<String, RateLimiter>> caches = new EnumMap<>(Group.class);

    public RateLimitGroups(RateLimiterRegistry registry) {
        this.registry = registry;
        for (Group group : Group.values()) {
            caches.put(
                    group,
                    Caffeine.newBuilder()
                            .maximumSize(MAX_KEYS_PER_GROUP)
                            .expireAfterAccess(KEY_TTL)
                            .build());
        }
    }

    /**
     * @return the group this request belongs to, or empty if the path is exempt. First match wins.
     *     <p>The raw request target is attacker-controlled, so matching happens against a
     *     canonicalized path (dot-segments collapsed, matrix parameters stripped, trailing slash
     *     removed) rather than the raw string - otherwise {@code AntPathMatcher} treats a pattern
     *     like {@code /api/internal/**} as a literal token match and lets {@code
     *     /api/internal/../flags/1/analyse} through as "internal" traffic even though it denotes an
     *     LLM-trigger endpoint.
     *     <p>The default is fail-closed: only a path that explicitly matches an exempt pattern is
     *     exempt. Everything else - including paths nobody has written a rule for yet, and paths
     *     that only differ from a known route by case - falls through to {@code ADMIN_CRUD}. This
     *     is the same principle as the {@code /api/**} catch-all applied consistently: an endpoint
     *     added later, or a request shaped in a way nobody anticipated, is limited by default
     *     rather than silently unlimited or silently exempt.
     *     <p>A canonical path that collapses into looking like an exempt path (e.g. {@code
     *     /api/../actuator/health} canonicalizing to {@code /actuator/health}) is still never
     *     treated as exempt if the raw path carried a dot-segment: this filter's normalization is
     *     not guaranteed to agree with whatever normalization the downstream router applies, and
     *     the exempt branch is the dangerous direction to be wrong about. The same ambiguity in the
     *     non-exempt branches only costs a bucket assignment, never a bypass, so it is not guarded.
     */
    public Optional<Group> resolve(HttpMethod method, String path) {
        String canonical = canonicalize(path);
        boolean traversalAttempted = path.contains("..");

        if (!traversalAttempted && isExempt(canonical)) {
            return Optional.empty();
        }
        if (matches(canonical, "/api/auth/**") || matches(canonical, "/auth/**")) {
            return Optional.of(Group.AUTH);
        }
        if (HttpMethod.POST.equals(method)
                && (matches(canonical, "/api/flags/*/analyse")
                        || matches(canonical, "/api/flags/*/chat")
                        || matches(canonical, "/api/simulate/message"))) {
            return Optional.of(Group.LLM_TRIGGER);
        }
        return Optional.of(Group.ADMIN_CRUD);
    }

    /**
     * Exempt matching deliberately uses plain prefix checks rather than {@code AntPathMatcher}.
     * This is the dangerous branch - the one that turns off rate limiting entirely - so it should
     * not depend on wildcard-matching semantics at all, only on "does this canonical path literally
     * live under this literal directory."
     */
    private static boolean isExempt(String canonical) {
        return canonical.equals("/actuator")
                || canonical.startsWith("/actuator/")
                || canonical.equals("/api/internal")
                || canonical.startsWith("/api/internal/");
    }

    /**
     * Collapses dot-segments, repeated slashes, and matrix parameters ({@code ;key=value} suffixes
     * on a path segment), then strips a trailing slash (except for a bare {@code /}). The result is
     * deliberately the only value ever matched against - the raw request path is never matched
     * directly.
     */
    private static String canonicalize(String rawPath) {
        String cleaned = StringUtils.cleanPath(rawPath).replaceAll("/{2,}", "/");
        String[] segments = cleaned.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            int matrixParamIndex = segment.indexOf(';');
            sb.append(matrixParamIndex >= 0 ? segment.substring(0, matrixParamIndex) : segment);
            sb.append('/');
        }
        sb.setLength(Math.max(sb.length() - 1, 0));
        String result = sb.toString();
        if (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? "/" : result;
    }

    public RateLimiter limiterFor(Group group, String key) {
        return caches.get(group)
                .get(key, k -> RateLimiter.of(group.instanceName() + ":" + k, config(group)));
    }

    private RateLimiterConfig config(Group group) {
        return registry.rateLimiter(group.instanceName()).getRateLimiterConfig();
    }

    private static boolean matches(String path, String pattern) {
        return MATCHER.match(pattern, path);
    }

    /** Test seam: Caffeine evicts asynchronously, so tests must force pending maintenance. */
    void cleanUp(Group group) {
        caches.get(group).cleanUp();
    }

    long estimatedSize(Group group) {
        return caches.get(group).estimatedSize();
    }
}
