package io.emcip.knowledge.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "io.emcip.knowledge.engine.repository")
public class KnowledgeEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeEngineApplication.class, args);
    }
}
