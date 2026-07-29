package io.emcip.knowledge.engine.config;

import io.emcip.common.net.PinningDns;
import io.emcip.common.net.SsrfAllowList;
import io.emcip.common.net.SsrfGuard;
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
        SsrfGuard guard = new SsrfGuard(allowList);
        return new OkHttpClient.Builder()
                .dns(new PinningDns(guard))
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(30))
                .build();
    }
}
