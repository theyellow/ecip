package io.emcip.llm.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("knowledge.enrichment")
public record KnowledgeEnrichmentProperties(
        boolean enabled, double relevanceThreshold, int maxResults, int contextMaxChars) {

    public KnowledgeEnrichmentProperties {
        if (relevanceThreshold < 0.0 || relevanceThreshold > 1.0) {
            throw new IllegalArgumentException("relevanceThreshold must be between 0.0 and 1.0");
        }
    }
}
