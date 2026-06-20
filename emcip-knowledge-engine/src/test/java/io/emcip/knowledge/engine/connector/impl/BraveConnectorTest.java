package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.ConnectorException;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.TriggerMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class BraveConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsWebSearchResults() {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
{
  "web": {
    "results": [
      {
        "title": "Introduction to Quantum Computing",
        "description": "A comprehensive guide to quantum computing principles...",
        "url": "https://quantumcomputing.com/intro",
        "age": "2023-01-15"
      }
    ]
  }
}
""")
                        .addHeader("Content-Type", "application/json"));

        BraveConnector connector = new BraveConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.SCHEDULED, "quantum computing", null, Map.of()),
                        new ConnectorContext("test-api-key", UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Introduction to Quantum Computing");
        assertThat(results.get(0).content()).contains("quantum computing");
        assertThat(results.get(0).url()).isEqualTo("https://quantumcomputing.com/intro");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("brave");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));
        BraveConnector connector = new BraveConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "q", null, Map.of()),
                        new ConnectorContext("key", UUID.randomUUID(), epoch()));
        assertThat(results).isEmpty();
    }

    @Test
    void fetch_returns401_throwsConnectorException() {
        server.enqueue(new MockResponse().setResponseCode(401));
        BraveConnector connector = new BraveConnector(restClient, baseUrl());
        assertThrows(
                ConnectorException.class,
                () ->
                        connector.fetch(
                                new EnrichmentRequest(TriggerMode.SCHEDULED, "q", null, Map.of()),
                                new ConnectorContext("bad-key", UUID.randomUUID(), epoch())));
    }
}
