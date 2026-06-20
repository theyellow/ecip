package io.emcip.knowledge.engine.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ConnectorConfig {

    @Bean(name = "connectorRestClient")
    public RestClient connectorRestClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "EMCIP-KnowledgeEngine/1.0 (research-enrichment)")
                .build();
    }
}
