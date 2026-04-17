package io.emcip.tdlib.adapter.health;

import io.emcip.tdlib.adapter.config.TdLibClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class TdLibHealthIndicator implements HealthIndicator {

    private final TdLibClient tdLibClient;

    public TdLibHealthIndicator(TdLibClient tdLibClient) {
        this.tdLibClient = tdLibClient;
    }

    @Override
    public Health health() {
        boolean initialized = tdLibClient.isInitialized();
        boolean authorized = tdLibClient.isAuthorized();

        if (!initialized) {
            return Health.down().withDetail("tdlib", "not initialized").build();
        }

        if (!authorized) {
            return Health.outOfService()
                    .withDetail("tdlib", "initialized but not authorized")
                    .withDetail("authorization", "pending")
                    .build();
        }

        return Health.up()
                .withDetail("tdlib", "connected and authorized")
                .withDetail("authorization", "complete")
                .build();
    }
}
