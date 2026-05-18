package io.emcip.policy.engine.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Test configuration providing infrastructure stubs for integration tests. Liquibase is now managed
 * by Spring Boot auto-configuration; properties are injected by TestcontainersInitializer
 * (change-log, drop-first=true, enabled=true).
 */
@TestConfiguration
public class TestDatabaseConfig {

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("bootstrap.servers", "localhost:14003");
        return new KafkaAdmin(configs);
    }
}
