package io.emcip.llm.orchestrator.config;

import io.emcip.llm.orchestrator.client.KnowledgeEngineClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Configures the {@link KnowledgeEngineClient} bean with a base-URL-scoped {@link RestClient}. */
@Configuration
public class KnowledgeClientConfig {

    @Bean
    public RestClient knowledgeEngineRestClient(
            @Value("${knowledge.engine.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeoutRequestFactory())
                .build();
    }

    @Bean
    public KnowledgeEngineClient knowledgeEngineClient(
            RestClient knowledgeEngineRestClient,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        return new KnowledgeEngineClient(
                knowledgeEngineRestClient, objectMapper, circuitBreakerRegistry);
    }

    private ClientHttpRequestFactory timeoutRequestFactory() {
        var httpClient =
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
