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
     * @return the group this request belongs to, or empty if the path is exempt. First match wins;
     *     the {@code /api/**} catch-all is deliberate, so an endpoint added later is limited by
     *     default rather than silently unlimited.
     */
    public Optional<Group> resolve(HttpMethod method, String path) {
        if (matches(path, "/actuator/**") || matches(path, "/api/internal/**")) {
            return Optional.empty();
        }
        if (matches(path, "/api/auth/**") || matches(path, "/auth/**")) {
            return Optional.of(Group.AUTH);
        }
        if (HttpMethod.POST.equals(method)
                && (matches(path, "/api/flags/*/analyse")
                        || matches(path, "/api/flags/*/chat")
                        || matches(path, "/api/simulate/message"))) {
            return Optional.of(Group.LLM_TRIGGER);
        }
        if (matches(path, "/api/**")) {
            return Optional.of(Group.ADMIN_CRUD);
        }
        return Optional.empty();
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
