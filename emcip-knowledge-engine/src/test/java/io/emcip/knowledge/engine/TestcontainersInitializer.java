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

    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
                    .withDatabaseName("emcip_test")
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
                        "spring.kafka.bootstrap-servers=localhost:14003")
                .applyTo(applicationContext.getEnvironment());
    }
}
