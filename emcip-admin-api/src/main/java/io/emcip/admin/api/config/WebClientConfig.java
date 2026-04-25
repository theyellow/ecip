package io.emcip.admin.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean("tdlibWebClient")
    public WebClient tdlibWebClient(@Value("${service.tdlib.url}") String tdlibUrl) {
        return WebClient.builder().baseUrl(tdlibUrl).build();
    }

    @Bean("orchestratorWebClient")
    public WebClient orchestratorWebClient(
            @Value("${service.orchestrator.url}") String orchestratorUrl) {
        return WebClient.builder().baseUrl(orchestratorUrl).build();
    }
}
