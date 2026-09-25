package io.emcip.llm.orchestrator.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs this service's Liquibase changelog at startup.
 *
 * <p>Spring Boot 4 removed Liquibase auto-configuration from {@code spring-boot-autoconfigure}, so
 * {@code spring.liquibase.enabled: true} alone does nothing. Without this bean the changelog never
 * ran in any deployment (LO-LIQUIBASE, 2026-09-25): changesets were recorded by hand, some with a
 * fake {@code 9:manual} checksum, and the live schema drifted from it. Same pattern as the other
 * services' {@code LiquibaseConfig}; moving all of them to {@code spring-boot-starter-liquibase} is
 * tracked separately (LIQ-STARTER).
 */
@Configuration
public class LiquibaseConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Bean
    @ConditionalOnMissingBean(SpringLiquibase.class)
    public SpringLiquibase liquibase() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(ds);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        return liquibase;
    }

    /**
     * Migrations before JPA: Hibernate must not touch (or validate) the schema until the changelog
     * has run. Boot's own Liquibase auto-configuration registered this ordering; without it, it is
     * up to bean creation order.
     */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor entityManagerFactoryDependsOnLiquibase() {
        return new EntityManagerFactoryDependsOnPostProcessor("liquibase");
    }
}
