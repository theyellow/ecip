package io.emcip.knowledge.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public class TestcontainersInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(TestcontainersInitializer.class);

    // Our own image (postgres:16 + pgvector + Apache AGE), built from
    // docker/postgres-knowledge/Dockerfile and published to GHCR. Using it gives the
    // integration tests the same graph engine as production instead of an AGE-less stand-in.
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("ghcr.io/theyellow/ecip/postgres:16")
                                    .asCompatibleSubstituteFor("postgres"))
                    // Match the production database name. This also stays compatible with the
                    // currently-published image whose init script hardcoded `ALTER DATABASE emcip`
                    // (the Dockerfile now derives the name from current_database() instead).
                    .withDatabaseName("emcip")
                    .withUsername("emcip")
                    .withPassword("emcip");

    static {
        postgres.start();
        log.info(
                "PostgreSQL container started: {}:{}",
                postgres.getHost(),
                postgres.getFirstMappedPort());
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        log.info("Configuring test properties for PostgreSQL container");

        TestPropertyValues.of(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                        "spring.jpa.defer-datasource-initialization=false",
                        "spring.sql.init.mode=never",
                        "spring.liquibase.enabled=true",
                        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml",
                        "spring.liquibase.drop-first=true",
                        "spring.liquibase.default-schema=public",
                        "spring.liquibase.liquibase-schema=public",
                        "spring.kafka.bootstrap-servers=localhost:14003",
                        // AGE is now present in the container, so load it per connection
                        // (matches application.yml) instead of blanking the init SQL.
                        "spring.datasource.hikari.connection-init-sql=LOAD 'age'; SET search_path"
                                + " = ag_catalog, \"$user\", public")
                .applyTo(applicationContext.getEnvironment());
    }
}
