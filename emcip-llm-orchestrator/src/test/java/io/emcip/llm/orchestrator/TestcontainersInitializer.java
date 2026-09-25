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
                        // Schema comes from this service's real Liquibase changelog, exactly as
                        // in production (LO-LIQUIBASE): a changeset that fails on an empty
                        // database fails every test here. ddl-auto=none as in production;
                        // "validate" is not yet possible - entity column types have drifted
                        // from the changelog (e.g. Double vs numeric), see LIQ-VALIDATE.
                        "spring.jpa.hibernate.ddl-auto=none",
                        // Any valid 32-byte key: these tests care about which values get
                        // encrypted, never about interoperating with a real deployment.
                        "emcip.secret-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
                .applyTo(applicationContext.getEnvironment());
    }
}
