package io.emcip.knowledge.engine;

import io.emcip.knowledge.engine.config.IngestionProperties;
import io.emcip.knowledge.engine.config.WebSearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "io.emcip.knowledge.engine.repository")
@EnableScheduling
@EnableConfigurationProperties({WebSearchProperties.class, IngestionProperties.class})
public class KnowledgeEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeEngineApplication.class, args);
    }
}
