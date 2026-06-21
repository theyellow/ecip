package io.emcip.llm.orchestrator.config;

import io.emcip.llm.orchestrator.client.KnowledgeEngineClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Configures the {@link KnowledgeEngineClient} bean with a base-URL-scoped {@link RestClient}. */
@Configuration
public class KnowledgeClientConfig {

    @Bean
    public KnowledgeEngineClient knowledgeEngineClient(
            @Value("${knowledge.engine.base-url}") String baseUrl, ObjectMapper objectMapper) {
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
        return new KnowledgeEngineClient(restClient, objectMapper);
    }
}
