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
    public WebClient tdlibWebClient(@Value("${service.tdlib.url}") String tdlibUrl) {
        return buildWebClient(WebClient.builder(), tdlibUrl);
    }

    @Bean("orchestratorWebClient")
    public WebClient orchestratorWebClient(
            @Value("${service.orchestrator.url}") String orchestratorUrl) {
        return buildWebClient(WebClient.builder(), orchestratorUrl);
    }

    private WebClient buildWebClient(WebClient.Builder builder, String baseUrl) {
        HttpClient httpClient =
                HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                        .responseTimeout(Duration.ofSeconds(30));
        return builder.baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
