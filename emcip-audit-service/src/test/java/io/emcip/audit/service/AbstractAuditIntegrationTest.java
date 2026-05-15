package io.emcip.audit.service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for audit service integration tests.
 *
 * <p>Containers are started once per JVM using a static initializer (singleton pattern) rather than
 * per-test-class via {@code @Testcontainers}/{@code @Container}. This prevents Testcontainers from
 * stopping the shared containers when the first test class finishes, which would break the cached
 * Spring context for subsequent test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestKafkaProducerConfig.class)
public abstract class AbstractAuditIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final KafkaContainer KAFKA;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.r2dbc.url",
                () -> POSTGRES.getJdbcUrl().replace("jdbc:postgresql", "r2dbc:postgresql"));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Suppress OTLP connection errors during tests — no Tempo running
        registry.add("management.otlp.tracing.endpoint", () -> "http://localhost:1/v1/traces");
    }
}
