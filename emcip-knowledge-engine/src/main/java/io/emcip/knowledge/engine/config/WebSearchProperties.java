package io.emcip.knowledge.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("web.search")
public record WebSearchProperties(boolean enabled, SearXngConfig searxng) {

    public record SearXngConfig(String baseUrl) {
        public SearXngConfig {
            if (baseUrl == null) baseUrl = "";
        }
    }

    public WebSearchProperties {
        if (searxng == null) searxng = new SearXngConfig("");
    }
}
