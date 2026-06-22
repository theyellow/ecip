package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.config.WebSearchProperties;
import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentConnectorRegistry;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import io.emcip.knowledge.engine.entity.VendorApiKey;
import io.emcip.knowledge.engine.repository.VendorApiKeyRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebSearchServiceTest {

    @Mock private EnrichmentConnectorRegistry registry;
    @Mock private VendorApiKeyRepository vendorApiKeyRepository;
    @Mock private KnowledgeConnector searxngConnector;
    @Mock private KnowledgeConnector braveConnector;

    private WebSearchService serviceWithSearXng;
    private WebSearchService serviceWithBraveOnly;
    private WebSearchService serviceDisabled;

    @BeforeEach
    void setUp() {
        var propsWithSearXng =
                new WebSearchProperties(
                        true, new WebSearchProperties.SearXngConfig("http://searxng.local"));
        var propsWithBraveOnly =
                new WebSearchProperties(true, new WebSearchProperties.SearXngConfig(""));
        var propsDisabled =
                new WebSearchProperties(false, new WebSearchProperties.SearXngConfig(""));

        serviceWithSearXng =
                new WebSearchService(propsWithSearXng, registry, vendorApiKeyRepository);
        serviceWithBraveOnly =
                new WebSearchService(propsWithBraveOnly, registry, vendorApiKeyRepository);
        serviceDisabled = new WebSearchService(propsDisabled, registry, vendorApiKeyRepository);
    }

    @Test
    void search_returnsEmpty_whenDisabled() {
        List<EnrichmentResult> results = serviceDisabled.search("AI ethics", UUID.randomUUID());
        assertThat(results).isEmpty();
    }

    @Test
    void search_usesSearXng_whenConfigured() {
        UUID tenantId = UUID.randomUUID();
        EnrichmentResult result =
                new EnrichmentResult(
                        "https://example.com",
                        "AI Ethics",
                        "Content",
                        "https://example.com",
                        "searxng",
                        null,
                        Map.of());
        when(registry.find("searxng")).thenReturn(Optional.of(searxngConnector));
        when(searxngConnector.fetch(any(EnrichmentRequest.class), any(ConnectorContext.class)))
                .thenReturn(List.of(result));

        List<EnrichmentResult> results = serviceWithSearXng.search("AI ethics", tenantId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("AI Ethics");
    }

    @Test
    void search_fallsBackToBrave_whenSearXngNotConfigured() {
        UUID tenantId = UUID.randomUUID();
        VendorApiKey braveKey = new VendorApiKey();
        braveKey.setApiKey("brave-key-123");
        braveKey.setEnabled(true);

        EnrichmentResult result =
                new EnrichmentResult(
                        "https://brave.com/r/1",
                        "Brave Result",
                        "Brave content",
                        "https://brave.com/r/1",
                        "brave",
                        null,
                        Map.of());

        when(registry.find("brave")).thenReturn(Optional.of(braveConnector));
        when(vendorApiKeyRepository.findByVendorIdAndTenantId("brave", tenantId))
                .thenReturn(Optional.of(braveKey));
        when(braveConnector.fetch(any(EnrichmentRequest.class), any(ConnectorContext.class)))
                .thenReturn(List.of(result));

        List<EnrichmentResult> results = serviceWithBraveOnly.search("AI ethics", tenantId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).sourceVendorId()).isEqualTo("brave");
    }

    @Test
    void search_returnsEmpty_whenNoBraveKeyFound() {
        UUID tenantId = UUID.randomUUID();
        when(registry.find("brave")).thenReturn(Optional.of(braveConnector));
        when(vendorApiKeyRepository.findByVendorIdAndTenantId("brave", tenantId))
                .thenReturn(Optional.empty());
        when(vendorApiKeyRepository.findByVendorIdAndTenantIdIsNull("brave"))
                .thenReturn(Optional.empty());

        List<EnrichmentResult> results = serviceWithBraveOnly.search("AI ethics", tenantId);

        assertThat(results).isEmpty();
    }
}
