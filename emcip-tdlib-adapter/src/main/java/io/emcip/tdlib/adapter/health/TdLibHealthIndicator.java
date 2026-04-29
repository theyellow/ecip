package io.emcip.tdlib.adapter.health;

import io.emcip.tdlib.adapter.config.TdLibClient;
import io.emcip.tdlib.adapter.config.TdLibClientManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TdLibHealthIndicator implements HealthIndicator {

    private final TdLibClientManager manager;

    @Override
    public Health health() {
        var clients = manager.getClients();

        if (clients.isEmpty()) {
            return Health.down().withDetail("tdlib", "no accounts configured").build();
        }

        boolean anyAuthorized = clients.values().stream().anyMatch(TdLibClient::isAuthorized);

        if (anyAuthorized) {
            return Health.up()
                    .withDetail("tdlib", "connected and authorized")
                    .withDetail(
                            "activeAccounts",
                            clients.values().stream().filter(TdLibClient::isAuthorized).count())
                    .build();
        }

        return Health.down()
                .withDetail("tdlib", "no authorized accounts")
                .withDetail("totalAccounts", clients.size())
                .build();
    }
}
