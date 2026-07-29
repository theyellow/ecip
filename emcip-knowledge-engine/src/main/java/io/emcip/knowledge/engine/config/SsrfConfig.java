package io.emcip.knowledge.engine.config;

import io.emcip.common.net.SsrfAllowList;
import io.emcip.common.net.SsrfHttpClients;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the SSRF-guarded OkHttp client used for URL document ingestion. */
@Configuration
@EnableConfigurationProperties(SsrfProperties.class)
public class SsrfConfig {

    @Bean
    public SsrfAllowList ssrfAllowList(SsrfProperties properties) {
        return SsrfAllowList.parse(properties.getAllowedHosts());
    }

    @Bean
    public OkHttpClient ssrfHttpClient(SsrfAllowList allowList) {
        return SsrfHttpClients.create(allowList, Duration.ofSeconds(30));
    }
}
