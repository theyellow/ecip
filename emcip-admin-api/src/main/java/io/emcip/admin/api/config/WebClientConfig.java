package io.emcip.admin.api.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Bean("tdlibWebClient")
    public WebClient tdlibWebClient(
            @Value("${service.tdlib.url}") String tdlibUrl,
            @Value("${admin.service-token}") String serviceToken) {
        return buildWebClient(
                WebClient.builder().defaultHeader("X-Service-Token", serviceToken),
                tdlibUrl,
                Duration.ofSeconds(30));
    }

    @Bean("orchestratorWebClient")
    public WebClient orchestratorWebClient(
            @Value("${service.orchestrator.url}") String orchestratorUrl) {
        // 60s: /api/provider-config/models calls an external LLM provider to list models,
        // which can be slow on the first call or under load.
        return buildWebClient(WebClient.builder(), orchestratorUrl, Duration.ofSeconds(60));
    }

    @Bean("knowledgeWebClient")
    public WebClient knowledgeWebClient(
            @Value("${service.knowledge.url}") String knowledgeUrl,
            @Value("${admin.service-token}") String serviceToken) {
        return buildWebClient(
                WebClient.builder().defaultHeader("X-Service-Token", serviceToken),
                knowledgeUrl,
                Duration.ofSeconds(30));
    }

    private WebClient buildWebClient(
            WebClient.Builder builder, String baseUrl, Duration responseTimeout) {
        HttpClient httpClient =
                HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                        .responseTimeout(responseTimeout);
        return builder.baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
