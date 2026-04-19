package io.emcip.llm.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories
public class LlmOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmOrchestratorApplication.class, args);
    }
}
