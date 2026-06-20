package io.emcip.knowledge.engine.connector;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class EnrichmentConnectorRegistryIntegrationTest {

    @Autowired EnrichmentConnectorRegistry registry;

    @Test
    void registry_containsAllThirteenConnectors() {
        List<KnowledgeConnector> all = registry.all();
        assertThat(all).hasSizeGreaterThanOrEqualTo(13);

        List<String> vendorIds = all.stream().map(KnowledgeConnector::vendorId).toList();
        assertThat(vendorIds)
                .contains(
                        "wikipedia",
                        "arxiv",
                        "pubmed",
                        "wikidata",
                        "openalex",
                        "semantic-scholar",
                        "biorxiv",
                        "core",
                        "zenodo",
                        "unpaywall",
                        "doaj",
                        "exa",
                        "brave");
    }

    @Test
    void registry_find_returnsConnectorForKnownVendor() {
        assertThat(registry.find("wikipedia")).isPresent();
        assertThat(registry.find("unknown-vendor")).isEmpty();
    }
}
