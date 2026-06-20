package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.connector.*;
import java.util.*;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class ZenodoConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsRecordResults() {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
{
  "hits": {
    "hits": [
      {
        "id": 7654321,
        "metadata": {
          "title": "Open Science Data in Ecology",
          "description": "Dataset and analysis of ecological surveys...",
          "publication_date": "2022-11-20"
        },
        "links": {
          "html": "https://zenodo.org/record/7654321"
        }
      }
    ]
  }
}
""")
                        .addHeader("Content-Type", "application/json"));

        ZenodoConnector connector = new ZenodoConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "ecology", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("zenodo:7654321");
        assertThat(results.get(0).title()).isEqualTo("Open Science Data in Ecology");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("zenodo");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));
        ZenodoConnector connector = new ZenodoConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "q", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));
        assertThat(results).isEmpty();
    }
}
