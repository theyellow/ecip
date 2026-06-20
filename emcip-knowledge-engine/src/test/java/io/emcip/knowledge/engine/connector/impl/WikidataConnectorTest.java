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

class WikidataConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsEntityResults() {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
                                {
                                  "search": [
                                    {
                                      "id": "Q82571",
                                      "label": "quantum computing",
                                      "description": "branch of computing",
                                      "url": "https://www.wikidata.org/wiki/Q82571"
                                    }
                                  ]
                                }
                                """)
                        .addHeader("Content-Type", "application/json"));

        WikidataConnector connector = new WikidataConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.TOPIC_DRIVEN, "quantum computing", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("Q82571");
        assertThat(results.get(0).title()).isEqualTo("quantum computing");
        assertThat(results.get(0).content()).contains("branch of computing");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("wikidata");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));
        WikidataConnector connector = new WikidataConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.TOPIC_DRIVEN, "q", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));
        assertThat(results).isEmpty();
    }
}
