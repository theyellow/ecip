package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.connector.*;
import java.util.*;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class SemanticScholarConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsPaperResults() {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
{
  "data": [
    {
      "paperId": "abc123def456",
      "title": "Deep Learning for NLP",
      "abstract": "We present a new approach...",
      "year": 2022,
      "openAccessPdf": {"url": "https://arxiv.org/pdf/2201.00001.pdf"}
    }
  ]
}
""")
                        .addHeader("Content-Type", "application/json"));

        SemanticScholarConnector connector = new SemanticScholarConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.SCHEDULED, "deep learning nlp", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("abc123def456");
        assertThat(results.get(0).title()).isEqualTo("Deep Learning for NLP");
        assertThat(results.get(0).content()).contains("new approach");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("semantic-scholar");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));
        SemanticScholarConnector connector = new SemanticScholarConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "q", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));
        assertThat(results).isEmpty();
    }
}
