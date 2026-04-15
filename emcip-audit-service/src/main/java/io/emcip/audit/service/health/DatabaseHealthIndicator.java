package io.emcip.audit.service.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator for database connectivity.
 * Phase 1: Basic implementation - will be enhanced with actual DB checks in Phase 2.
 */
@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        // Phase 1: Basic health check
        // Phase 2: Add actual database connectivity verification
        return Health.up()
            .withDetail("service", "audit-service")
            .withDetail("database", "postgresql")
            .withDetail("status", "not-connected-yet")
            .build();
    }
}
