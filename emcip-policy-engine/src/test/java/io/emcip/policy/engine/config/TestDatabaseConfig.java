package io.emcip.policy.engine.config;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Test configuration to ensure Liquibase runs before JPA initialization. Also provides mock
 * KafkaAdmin for health checks.
 */
@TestConfiguration
public class TestDatabaseConfig {

    @Bean
    public SpringLiquibase liquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        liquibase.setDropFirst(true);
        liquibase.setDefaultSchema("public");
        return liquibase;
    }

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("bootstrap.servers", "localhost:14003");
        return new KafkaAdmin(configs);
    }
}
