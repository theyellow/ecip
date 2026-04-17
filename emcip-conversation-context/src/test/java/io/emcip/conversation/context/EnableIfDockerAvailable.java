package io.emcip.conversation.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Conditionally enables tests only if Docker is available. Use this to skip Testcontainers tests
 * when Docker is not running.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@EnabledIf(
        value = "io.emcip.conversation.context.DockerAvailabilityChecker#isDockerAvailable",
        disabledReason = "Docker not available - skipping Testcontainers test")
public @interface EnableIfDockerAvailable {}
