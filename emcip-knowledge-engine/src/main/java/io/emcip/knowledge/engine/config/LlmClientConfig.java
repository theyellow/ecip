package io.emcip.knowledge.engine.config;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class LlmClientConfig {

    @Bean
    public RestClient llmOrchestratorRestClient(
            @Value("${knowledge.llm-orchestrator.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public LlmOrchestratorClient llmOrchestratorClient(
            RestClient llmOrchestratorRestClient, ObjectMapper objectMapper) {
        return new LlmOrchestratorClient(llmOrchestratorRestClient, objectMapper);
    }
}
