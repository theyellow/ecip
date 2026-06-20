package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.TriggerMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class OpenAlexConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsWorkResults() {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
                                {
                                  "results": [
                                    {
                                      "id": "https://openalex.org/W2741809807",
                                      "title": "Attention Is All You Need",
                                      "doi": "https://doi.org/10.48550/arxiv.1706.03762",
                                      "publication_date": "2017-06-12"
                                    }
                                  ]
                                }
                                """)
                        .addHeader("Content-Type", "application/json"));

        OpenAlexConnector connector = new OpenAlexConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.SCHEDULED, "transformer attention", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("W2741809807");
        assertThat(results.get(0).title()).isEqualTo("Attention Is All You Need");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("openalex");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));
        OpenAlexConnector connector = new OpenAlexConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "q", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));
        assertThat(results).isEmpty();
    }
}
