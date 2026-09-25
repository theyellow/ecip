package io.emcip.llm.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * The service's own wiring runs its whole changelog on an empty database (LO-LIQUIBASE).
 *
 * <p>Spring Boot 4 has no Liquibase auto-configuration, and until 2026-09-25 this service had no
 * {@code LiquibaseConfig}, so the changelog never ran anywhere: the tests built the schema with
 * Hibernate and had Liquibase switched off, and production changesets were recorded by hand. This
 * test gets its schema only from the production bean - {@code TestcontainersInitializer} supplies
 * no Liquibase of its own.
 */
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersInitializer.class)
class LiquibaseChangelogIT {

    private static final Pattern CHANGESET = Pattern.compile("<changeSet\\s+id=\"");

    @Autowired JdbcTemplate jdbc;

    @Test
    void everyChangesetInTheChangelogWasExecuted() throws IOException {
        int inSource = 0;
        for (Resource r :
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath:db/changelog/changes/*.xml")) {
            inSource +=
                    (int)
                            CHANGESET
                                    .matcher(r.getContentAsString(StandardCharsets.UTF_8))
                                    .results()
                                    .count();
        }

        Integer executed =
                jdbc.queryForObject(
                        "select count(*) from databasechangelog where exectype = 'EXECUTED'",
                        Integer.class);

        assertThat(inSource).isPositive();
        assertThat(executed).isEqualTo(inSource);
    }

    @Test
    void theLatestChangesetsSchemaChangeIsPresent() {
        // llm-16 widened the column; it is the changeset the deployed database never received.
        String type =
                jdbc.queryForObject(
                        "select data_type from information_schema.columns"
                                + " where table_name = 'llm_provider_configs'"
                                + " and column_name = 'api_key'",
                        String.class);

        assertThat(type).isEqualTo("text");
    }
}
