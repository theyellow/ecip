package io.emcip.llm.orchestrator;

import io.emcip.llm.orchestrator.config.KnowledgeEnrichmentProperties;
import io.emcip.llm.orchestrator.config.LlmOrchestratorRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaRepositories
@EnableScheduling
@ImportRuntimeHints(LlmOrchestratorRuntimeHints.class)
@EnableConfigurationProperties(KnowledgeEnrichmentProperties.class)
public class LlmOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmOrchestratorApplication.class, args);
    }
}
