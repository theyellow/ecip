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

class ExaConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsSearchResults() {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
{
  "results": [
    {
      "id": "https://example.com/ai-paper",
      "title": "Advances in AI Research",
      "text": "This paper explores the latest advances in artificial intelligence...",
      "url": "https://example.com/ai-paper",
      "publishedDate": "2023-06-01T00:00:00.000Z"
    }
  ]
}
""")
                        .addHeader("Content-Type", "application/json"));

        ExaConnector connector = new ExaConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.SCHEDULED, "artificial intelligence", null, Map.of()),
                        new ConnectorContext("test-api-key", UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Advances in AI Research");
        assertThat(results.get(0).content()).contains("artificial intelligence");
        assertThat(results.get(0).url()).isEqualTo("https://example.com/ai-paper");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("exa");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));
        ExaConnector connector = new ExaConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "q", null, Map.of()),
                        new ConnectorContext("key", UUID.randomUUID(), epoch()));
        assertThat(results).isEmpty();
    }

    @Test
    void fetch_returns401_throwsConnectorException() {
        server.enqueue(new MockResponse().setResponseCode(401));
        ExaConnector connector = new ExaConnector(restClient, baseUrl());
        assertThrows(
                ConnectorException.class,
                () ->
                        connector.fetch(
                                new EnrichmentRequest(TriggerMode.SCHEDULED, "q", null, Map.of()),
                                new ConnectorContext("bad-key", UUID.randomUUID(), epoch())));
    }
}
