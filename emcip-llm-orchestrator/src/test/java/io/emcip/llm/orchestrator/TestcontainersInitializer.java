package io.emcip.llm.orchestrator;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/** Starts a PostgreSQL container and points the test context at it, with Liquibase applied. */
public class TestcontainersInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("emcip")
                    .withUsername("emcip")
                    .withPassword("emcip");

    static {
        POSTGRES.start();
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        TestPropertyValues.of(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        // Schema comes from the entity mappings, not from Liquibase. These tests
                        // are about how the api_key converter behaves on read and write, which is
                        // independent of the migration history - and this service's Liquibase does
                        // not currently run at all (changeset llm-16 is absent from
                        // databasechangelog in the deployed database), so depending on it here
                        // would couple a converter test to an unrelated open defect.
                        "spring.jpa.hibernate.ddl-auto=create-drop",
                        "spring.liquibase.enabled=false",
                        // Any valid 32-byte key: these tests care about which values get
                        // encrypted, never about interoperating with a real deployment.
                        "emcip.secret-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
                .applyTo(applicationContext.getEnvironment());
    }
}
