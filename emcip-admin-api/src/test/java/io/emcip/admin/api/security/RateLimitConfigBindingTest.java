package io.emcip.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.springboot3.ratelimiter.autoconfigure.RateLimiterAutoConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;

/**
 * Asserts the rate-limit budgets in the real {@code application.yml} reach the registry under the
 * exact names {@link RateLimitGroups.Group#instanceName()} looks them up by.
 *
 * <p>This exists because the failure mode is invisible: {@code
 * RateLimiterRegistry.rateLimiter(name)} does not fail on an unknown name — it silently creates a
 * limiter from the registry's default config. A typo, or a binding quirk in a hyphenated instance
 * name ({@code admin-crud}, {@code llm-trigger}), would swap the intended budget for resilience4j's
 * default of 50 permits with no error anywhere. Every other test would still pass: the limiter
 * works, it just enforces a number nobody chose.
 *
 * <p>Loads the actual {@code application.yml} via {@link ConfigDataApplicationContextInitializer}
 * rather than restating the properties inline — restating them would test this file against itself
 * and prove nothing about what ships.
 */
class RateLimitConfigBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(AutoConfigurations.of(RateLimiterAutoConfiguration.class));

    @Test
    void everyGroupResolvesToItsConfiguredBudgetNotTheResilience4jDefault() {
        runner.run(assertBudgets());
    }

    private ContextConsumer<AssertableApplicationContext> assertBudgets() {
        return context -> {
            assertThat(context).hasSingleBean(RateLimiterRegistry.class);
            RateLimiterRegistry registry = context.getBean(RateLimiterRegistry.class);

            for (RateLimitGroups.Group group : RateLimitGroups.Group.values()) {
                RateLimiterConfig config =
                        registry.rateLimiter(group.instanceName()).getRateLimiterConfig();

                assertThat(config.getLimitForPeriod())
                        .as(
                                "group %s (instance '%s') — a value of 50 means application.yml did"
                                        + " not bind and resilience4j's default was used instead",
                                group, group.instanceName())
                        .isEqualTo(expectedPermits(group));

                assertThat(config.getLimitRefreshPeriod())
                        .as("group %s refresh period", group)
                        .isEqualTo(Duration.ofSeconds(60));

                // A non-zero timeout makes acquirePermission() BLOCK waiting for a permit, which on
                // a reactive event loop is worse than the 429 it avoids.
                assertThat(config.getTimeoutDuration())
                        .as("group %s timeout must be zero (never block the event loop)", group)
                        .isZero();
            }
        };
    }

    private static int expectedPermits(RateLimitGroups.Group group) {
        return switch (group) {
            case AUTH -> 10;
            case LLM_TRIGGER -> 20;
            case ADMIN_CRUD -> 600;
        };
    }
}
