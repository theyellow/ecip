package io.emcip.knowledge.engine.config;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class LlmClientConfig {

    @Bean
    public RestClient llmOrchestratorRestClient(
            @Value("${knowledge.llm-orchestrator.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeoutRequestFactory())
                .build();
    }

    @Bean
    public LlmOrchestratorClient llmOrchestratorClient(
            RestClient llmOrchestratorRestClient,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        return new LlmOrchestratorClient(
                llmOrchestratorRestClient, objectMapper, circuitBreakerRegistry);
    }

    private ClientHttpRequestFactory timeoutRequestFactory() {
        var httpClient =
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(180));
        return factory;
    }
}
