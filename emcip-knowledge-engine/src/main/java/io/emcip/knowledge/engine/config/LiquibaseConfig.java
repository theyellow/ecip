package io.emcip.knowledge.engine.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
}
