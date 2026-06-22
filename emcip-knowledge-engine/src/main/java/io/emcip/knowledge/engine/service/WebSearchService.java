package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.config.WebSearchProperties;
import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentConnectorRegistry;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.VendorApiKey;
import io.emcip.knowledge.engine.repository.VendorApiKeyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchService {

    private final WebSearchProperties properties;
    private final EnrichmentConnectorRegistry registry;
    private final VendorApiKeyRepository vendorApiKeyRepository;

    /**
     * Searches the web for the given query. Tries SearXNG first if configured; falls back to Brave
     * using a stored API key. Returns an empty list if web search is disabled or no connector is
     * available.
     */
    public List<EnrichmentResult> search(String query, UUID tenantId) {
        if (!properties.enabled()) {
            log.debug("Web search is disabled");
            return List.of();
        }

        // Try SearXNG first when a base URL is configured
        if (!properties.searxng().baseUrl().isBlank()) {
            var searxng = registry.find("searxng");
            if (searxng.isPresent()) {
                try {
                    var request = new EnrichmentRequest(TriggerMode.MANUAL, query, null, Map.of());
                    var ctx = new ConnectorContext(null, tenantId, Instant.now());
                    List<EnrichmentResult> results = searxng.get().fetch(request, ctx);
                    if (!results.isEmpty()) {
                        return results;
                    }
                    log.debug("SearXNG returned no results, falling back to Brave");
                } catch (Exception e) {
                    log.warn(
                            "SearXNG search failed for '{}', falling back to Brave: {}",
                            query,
                            e.getMessage());
                }
            }
        }

        return searchWithBrave(query, tenantId);
    }

    private List<EnrichmentResult> searchWithBrave(String query, UUID tenantId) {
        var brave = registry.find("brave");
        if (brave.isEmpty()) {
            log.debug("Brave connector not available");
            return List.of();
        }

        String apiKey =
                vendorApiKeyRepository
                        .findByVendorIdAndTenantId("brave", tenantId)
                        .or(() -> vendorApiKeyRepository.findByVendorIdAndTenantIdIsNull("brave"))
                        .filter(VendorApiKey::isEnabled)
                        .map(VendorApiKey::getApiKey)
                        .orElse(null);

        if (apiKey == null) {
            log.debug("No enabled Brave API key for tenant {}", tenantId);
            return List.of();
        }

        var request = new EnrichmentRequest(TriggerMode.MANUAL, query, null, Map.of());
        var ctx = new ConnectorContext(apiKey, tenantId, Instant.now());
        return brave.get().fetch(request, ctx);
    }
}
