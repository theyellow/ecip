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

class PubMedConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsPubMedResults() {
        // First call: esearch
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
                                {
                                  "esearchresult": {
                                    "idlist": ["12345678"]
                                  }
                                }
                                """)
                        .addHeader("Content-Type", "application/json"));

        // Second call: esummary
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
                                {
                                  "result": {
                                    "uids": ["12345678"],
                                    "12345678": {
                                      "uid": "12345678",
                                      "title": "Novel insights into quantum biology",
                                      "pubdate": "2023 Jan",
                                      "source": "Nature"
                                    }
                                  }
                                }
                                """)
                        .addHeader("Content-Type", "application/json"));

        PubMedConnector connector = new PubMedConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.SCHEDULED, "quantum biology", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("pubmed:12345678");
        assertThat(results.get(0).title()).isEqualTo("Novel insights into quantum biology");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("pubmed");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));

        PubMedConnector connector = new PubMedConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "topic", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).isEmpty();
    }
}
