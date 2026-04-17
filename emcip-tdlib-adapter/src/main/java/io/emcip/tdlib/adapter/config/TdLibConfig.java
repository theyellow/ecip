package io.emcip.tdlib.adapter.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TdLibProperties.class)
public class TdLibConfig {

    @Bean
    public TdLibClient tdLibClient(TdLibProperties properties) {
        return new TdLibClient(properties);
    }
}
