package io.emcip.knowledge.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ResolutionConfig.ResolutionProperties.class)
public class ResolutionConfig {

    @ConfigurationProperties(prefix = "knowledge.resolution")
    public record ResolutionProperties(double mergeThreshold, double flagThreshold) {
        public ResolutionProperties {
            if (mergeThreshold == 0.0) mergeThreshold = 0.92;
            if (flagThreshold == 0.0) flagThreshold = 0.80;
        }
    }
}
