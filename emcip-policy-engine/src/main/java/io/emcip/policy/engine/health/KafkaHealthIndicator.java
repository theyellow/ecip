package io.emcip.policy.engine.health;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class KafkaHealthIndicator implements HealthIndicator {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Override
    public Health health() {
        try (AdminClient client =
                AdminClient.create(
                        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            var options = new DescribeClusterOptions().timeoutMs(5000);
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
