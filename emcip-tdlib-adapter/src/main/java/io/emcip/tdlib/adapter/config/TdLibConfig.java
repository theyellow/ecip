package io.emcip.tdlib.adapter.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TdLibProperties.class)
public class TdLibConfig {}
