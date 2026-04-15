package io.emcip.admin.api.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    private final R2dbcEntityTemplate template;

    public DatabaseHealthIndicator(R2dbcEntityTemplate template) {
        this.template = template;
    }

    @Override
    public Health health() {
        try {
            return template.getDatabaseClient()
                    .sql("SELECT 1")
                    .fetch()
                    .rowsUpdated()
                    .map(
                            count ->
                                    Health.up()
                                            .withDetail("database", "PostgreSQL")
                                            .withDetail("status", "Connected")
                                            .build())
                    .onErrorResume(
                            e ->
                                    Mono.just(
                                            Health.down()
                                                    .withDetail("database", "PostgreSQL")
                                                    .withDetail("error", e.getMessage())
                                                    .build()))
                    .block();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("database", "PostgreSQL")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
