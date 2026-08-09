package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class RateLimitGroupsTest {

    private RateLimitGroups groups;

    @BeforeEach
    void setUp() {
        groups = new RateLimitGroups(RateLimiterRegistry.ofDefaults());
    }

    @Test
    void actuatorAndInternalPathsAreExempt() {
        assertThat(groups.resolve(HttpMethod.GET, "/actuator/health")).isEmpty();
        assertThat(groups.resolve(HttpMethod.POST, "/api/internal/events")).isEmpty();
    }

    @Test
    void authPathsMapToAuthGroupKeyedByIp() {
        Optional<RateLimitGroups.Group> g = groups.resolve(HttpMethod.POST, "/api/auth/token");

        assertThat(g).contains(RateLimitGroups.Group.AUTH);
        assertThat(g.orElseThrow().keyByIp()).isTrue();
    }

    @Test
    void legacyAuthTokenPathIsAlsoAuthGroup() {
        assertThat(groups.resolve(HttpMethod.POST, "/auth/token"))
                .contains(RateLimitGroups.Group.AUTH);
    }

    @Test
    void llmTriggeringEndpointsMapToLlmTriggerGroup() {
        assertThat(groups.resolve(HttpMethod.POST, "/api/flags/123/analyse"))
                .contains(RateLimitGroups.Group.LLM_TRIGGER);
        assertThat(groups.resolve(HttpMethod.POST, "/api/flags/123/chat"))
                .contains(RateLimitGroups.Group.LLM_TRIGGER);
        assertThat(groups.resolve(HttpMethod.POST, "/api/simulate/message"))
                .contains(RateLimitGroups.Group.LLM_TRIGGER);
    }

    @Test
    void llmTriggeringEndpointsMatchWithRealUuidFlagId() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";

        assertThat(groups.resolve(HttpMethod.POST, "/api/flags/" + uuid + "/analyse"))
                .contains(RateLimitGroups.Group.LLM_TRIGGER);
        assertThat(groups.resolve(HttpMethod.POST, "/api/flags/" + uuid + "/chat"))
                .contains(RateLimitGroups.Group.LLM_TRIGGER);
    }

    @Test
    void previouslyUnlimitedReplyEndpointFallsUnderAdminCrud() {
        assertThat(groups.resolve(HttpMethod.POST, "/api/flags/123/reply"))
                .contains(RateLimitGroups.Group.ADMIN_CRUD);
    }

    @Test
    void anyOtherApiPathFallsUnderAdminCrudKeyedByUser() {
        Optional<RateLimitGroups.Group> g =
                groups.resolve(HttpMethod.GET, "/api/some/endpoint/invented/later");

        assertThat(g).contains(RateLimitGroups.Group.ADMIN_CRUD);
        assertThat(g.orElseThrow().keyByIp()).isFalse();
    }

    @Test
    void sameKeyReturnsTheSameLimiterInstance() {
        assertThat(groups.limiterFor(RateLimitGroups.Group.AUTH, "1.2.3.4"))
                .isSameAs(groups.limiterFor(RateLimitGroups.Group.AUTH, "1.2.3.4"));
    }

    @Test
    void differentKeysGetIndependentLimiters() {
        assertThat(groups.limiterFor(RateLimitGroups.Group.AUTH, "1.2.3.4"))
                .isNotSameAs(groups.limiterFor(RateLimitGroups.Group.AUTH, "5.6.7.8"));
    }

    @Test
    void dotSegmentTraversalIntoInternalDoesNotReachExemptBranch() {
        // Canonicalizes to /api/flags/1/analyse - must be rate-limited as LLM_TRIGGER, not
        // waved through as /api/internal/** exempt traffic.
        assertThat(groups.resolve(HttpMethod.POST, "/api/internal/../flags/1/analyse"))
                .contains(RateLimitGroups.Group.LLM_TRIGGER);
    }

    @Test
    void dotSegmentTraversalIntoActuatorIsNotExempt() {
        // Canonicalizes to /actuator/health, but the raw path carries a traversal segment, so it
        // must never be trusted into the exempt branch - fails closed to a limited group instead.
        assertThat(groups.resolve(HttpMethod.GET, "/api/../actuator/health")).isPresent();
    }

    @Test
    void trailingSlashStillMapsToLlmTrigger() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";

        assertThat(groups.resolve(HttpMethod.POST, "/api/flags/" + uuid + "/analyse/"))
                .contains(RateLimitGroups.Group.LLM_TRIGGER);
    }

    @Test
    void matrixParametersDoNotEvadeAuthGroup() {
        assertThat(groups.resolve(HttpMethod.POST, "/api/auth;x=1/token"))
                .contains(RateLimitGroups.Group.AUTH);
    }

    @Test
    void caseVariantAuthPathIsNeverExempt() {
        // AntPathMatcher is case-sensitive, so /API/auth/token does not match the AUTH patterns.
        // The fail-closed default must still catch it - it must never fall through to exempt.
        assertThat(groups.resolve(HttpMethod.POST, "/API/auth/token")).isPresent();
    }

    @Test
    void doubleSlashStillMapsToAuthGroup() {
        assertThat(groups.resolve(HttpMethod.POST, "/api//auth/token"))
                .contains(RateLimitGroups.Group.AUTH);
    }

    @Test
    void rootAndErrorPathsFallUnderAdminCrudByFailClosedDefault() {
        assertThat(groups.resolve(HttpMethod.GET, "/")).contains(RateLimitGroups.Group.ADMIN_CRUD);
        assertThat(groups.resolve(HttpMethod.GET, "/error"))
                .contains(RateLimitGroups.Group.ADMIN_CRUD);
    }

    @Test
    void actuatorAndInternalRemainExemptAfterNormalization() {
        assertThat(groups.resolve(HttpMethod.GET, "/actuator/health")).isEmpty();
        assertThat(groups.resolve(HttpMethod.POST, "/api/internal/events")).isEmpty();
    }

    @Test
    void cacheStaysBoundedUnderKeyRotation() {
        for (int i = 0; i < 20_000; i++) {
            groups.limiterFor(RateLimitGroups.Group.AUTH, "10.0." + (i / 256) + "." + (i % 256));
        }
        groups.cleanUp(RateLimitGroups.Group.AUTH);

        assertThat(groups.estimatedSize(RateLimitGroups.Group.AUTH)).isLessThanOrEqualTo(10_000);
    }
}
