package io.emcip.policy.engine.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator for Kafka connectivity.
 * Phase 1: Basic implementation - will be enhanced with actual Kafka checks in Phase 2.
 */
@Component
public class KafkaHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        // Phase 1: Basic health check
        // Phase 2: Add actual Kafka connectivity verification
        return Health.up()
            .withDetail("service", "policy-engine")
            .withDetail("broker", "kafka")
            .withDetail("status", "not-connected-yet")
            .build();
    }
}
