package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.connector.*;
import java.util.*;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class DoajConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsArticleResults() {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
{
  "results": [
    {
      "bibjson": {
        "title": [{"text": "Machine Learning in Medicine"}],
        "abstract": "This review examines ML applications in clinical settings...",
        "identifier": [{"type": "doi", "id": "10.1234/ml.medicine.2022"}],
        "journal": {"title": "Journal of Medical AI"},
        "link": [{"url": "https://doaj.org/article/abcdef123456"}]
      }
    }
  ]
}
""")
                        .addHeader("Content-Type", "application/json"));

        DoajConnector connector = new DoajConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.SCHEDULED, "machine learning medicine", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Machine Learning in Medicine");
        assertThat(results.get(0).content()).contains("ML applications");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("doaj");
        assertThat(results.get(0).externalId()).startsWith("doaj:");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));
        DoajConnector connector = new DoajConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "q", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));
        assertThat(results).isEmpty();
    }
}
