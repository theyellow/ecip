package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.connector.*;
import java.util.*;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class BiorxivConnectorTest extends ConnectorTestBase {

    private static final String BIORXIV_BODY =
            """
            {
              "collection": [
                {
                  "doi": "10.1101/2023.01.01.123456",
                  "title": "Novel CRISPR Applications in Gene Therapy",
                  "abstract": "We describe new CRISPR applications...",
                  "date": "2023-01-05"
                }
              ]
            }
            """;

    private static final String MEDRXIV_EMPTY =
            """
            {
              "collection": []
            }
            """;

    @Test
    void fetch_returnsResultsFromBothServers() {
        server.enqueue(
                new MockResponse()
                        .setBody(BIORXIV_BODY)
                        .addHeader("Content-Type", "application/json"));
        server.enqueue(
                new MockResponse()
                        .setBody(MEDRXIV_EMPTY)
                        .addHeader("Content-Type", "application/json"));

        BiorxivConnector connector = new BiorxivConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, null, null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("10.1101/2023.01.01.123456");
        assertThat(results.get(0).title()).isEqualTo("Novel CRISPR Applications in Gene Therapy");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("biorxiv");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(429));

        BiorxivConnector connector = new BiorxivConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, null, null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).isEmpty();
    }
}
