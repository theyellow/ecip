package io.emcip.intent.classifier.health;

import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

@Component
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaAdmin kafkaAdmin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        try (AdminClient client = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            var options = new DescribeClusterOptions();
            options.timeoutMs(5000);

            var clusterId = client.describeCluster(options).clusterId().get(5, TimeUnit.SECONDS);

            var nodes = client.describeCluster(options).nodes().get(5, TimeUnit.SECONDS);

            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("brokerCount", nodes.size())
                    .withDetail("status", "Connected")
                    .build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
