package io.emcip.conversation.context;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test configuration to ensure Liquibase runs before JPA initialization. */
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
}
