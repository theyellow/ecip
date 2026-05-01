package io.emcip.llm.orchestrator;

import io.emcip.llm.orchestrator.config.LlmOrchestratorRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories
@ImportRuntimeHints(LlmOrchestratorRuntimeHints.class)
public class LlmOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmOrchestratorApplication.class, args);
    }
}
