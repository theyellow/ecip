package io.emcip.admin.api.config;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class LiquibaseConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    /**
     * Honoured explicitly because this bean replaces Boot's Liquibase autoconfiguration, which
     * would otherwise read it. Empty = no context filter (all changesets run), as before.
     */
    @Value("${spring.liquibase.contexts:}")
    private String contexts;

    /**
     * DataSource bean for Liquibase migrations and any other components that require traditional
     * JDBC (e.g., SecretsSelfCheckConfig's secret column scanning).
     *
     * <p>admin-api uses R2DBC for reactive CRUD operations, but certain operations (schema
     * migrations, secret scanning) require blocking JDBC. This bean is intentionally minimal and
     * not used for application data access.
     */
    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    public SpringLiquibase liquibase() {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource());
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        if (!contexts.isBlank()) {
            liquibase.setContexts(contexts);
        }
        return liquibase;
    }
}
