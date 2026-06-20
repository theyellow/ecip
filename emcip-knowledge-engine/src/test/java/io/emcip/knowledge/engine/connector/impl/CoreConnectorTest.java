package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emcip.knowledge.engine.connector.*;
import java.util.*;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class CoreConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsResults() {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
{
  "results": [
    {
      "id": 99887766,
      "title": "Open Access in Scholarly Publishing",
      "abstract": "We examine open access trends...",
      "downloadUrl": "https://core.ac.uk/download/pdf/99887766.pdf",
      "publishedDate": "2022-03-15T00:00:00"
    }
  ]
}
""")
                        .addHeader("Content-Type", "application/json"));

        CoreConnector connector = new CoreConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.SCHEDULED,
                                "open access scholarly publishing",
                                null,
                                Map.of()),
                        new ConnectorContext("test-api-key", UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("core:99887766");
        assertThat(results.get(0).title()).isEqualTo("Open Access in Scholarly Publishing");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("core");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));
        CoreConnector connector = new CoreConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "q", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));
        assertThat(results).isEmpty();
    }

    @Test
    void fetch_returns401_throwsConnectorException() {
        server.enqueue(new MockResponse().setResponseCode(401));
        CoreConnector connector = new CoreConnector(restClient, baseUrl());
        assertThrows(
                ConnectorException.class,
                () ->
                        connector.fetch(
                                new EnrichmentRequest(TriggerMode.MANUAL, "q", null, Map.of()),
                                new ConnectorContext("bad-key", UUID.randomUUID(), epoch())));
    }
}
