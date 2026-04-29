package io.emcip.policy.engine.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native image hints for emcip-policy-engine.
 *
 * <p>Spring Boot AOT handles entity reflection, JPA repository proxies, and Kafka listener wiring.
 * We only need to register resources that Spring Boot's auto-configuration would normally register
 * but cannot because spring.liquibase.enabled=false (our custom LiquibaseConfig is used instead).
 */
public class PolicyEngineRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("db/changelog/**");
    }
}
