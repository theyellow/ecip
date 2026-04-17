package io.emcip.conversation.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Initializes Testcontainers PostgreSQL for integration tests.
 */
@Slf4j
public class TestcontainersInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"));

    static {
        log.info("Starting PostgreSQL Testcontainer...");
        postgres.start();
        log.info("PostgreSQL Testcontainer started at: {}", postgres.getJdbcUrl());
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        log.info("Configuring test properties for PostgreSQL container");

        TestPropertyValues.of(
                "spring.r2dbc.url=r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName(),
                "spring.r2dbc.username=" + postgres.getUsername(),
                "spring.r2dbc.password=" + postgres.getPassword(),
                "spring.datasource.url=" + postgres.getJdbcUrl(),
                "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword(),
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml"
        ).applyTo(applicationContext.getEnvironment());
    }
}
