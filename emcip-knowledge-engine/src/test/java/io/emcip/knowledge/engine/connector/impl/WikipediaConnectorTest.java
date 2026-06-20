package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.TriggerMode;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class WikipediaConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsSummaryResult() {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
{
  "title": "Quantum computing",
  "extract": "Quantum computing is a type of computation...",
  "content_urls": {
    "desktop": { "page": "https://en.wikipedia.org/wiki/Quantum_computing" }
  },
  "timestamp": "2026-01-10T00:00:00Z"
}
""")
                        .addHeader("Content-Type", "application/json"));

        WikipediaConnector connector = new WikipediaConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.TOPIC_DRIVEN, "Quantum computing", null, Map.of()),
                        new ConnectorContext(null, java.util.UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Quantum computing");
        assertThat(results.get(0).content()).contains("Quantum computing is");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("wikipedia");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));

        WikipediaConnector connector = new WikipediaConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.TOPIC_DRIVEN, "something", null, Map.of()),
                        new ConnectorContext(null, java.util.UUID.randomUUID(), epoch()));

        assertThat(results).isEmpty();
    }
}
