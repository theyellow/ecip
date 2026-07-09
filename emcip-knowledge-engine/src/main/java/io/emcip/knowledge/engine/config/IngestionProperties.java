package io.emcip.knowledge.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledge.ingestion")
public record IngestionProperties(int parallelism) {

    public IngestionProperties {
        if (parallelism <= 0) {
            parallelism = 3;
        }
    }
}
