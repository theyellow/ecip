package io.emcip.knowledge.engine.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SSRF allow-list configuration for URL ingestion. */
@ConfigurationProperties(prefix = "emcip.ingestion.ssrf")
public class SsrfProperties {

    /**
     * Hostnames or CIDRs that bypass the private-range block (blocklist otherwise always applies).
     */
    private List<String> allowedHosts = List.of();

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }
}
