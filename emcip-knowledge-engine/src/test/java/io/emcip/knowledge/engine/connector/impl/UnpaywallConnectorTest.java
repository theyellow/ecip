package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.connector.*;
import java.util.*;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class UnpaywallConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_lookupsByDoi() {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                """
                                {
                                  "title": "The Impact of Open Access",
                                  "published_date": "2021-06-15",
                                  "best_oa_location": {
                                    "url_for_pdf": "https://example.com/pdf/12345.pdf"
                                  }
                                }
                                """)
                        .addHeader("Content-Type", "application/json"));

        UnpaywallConnector connector = new UnpaywallConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.MANUAL, null, "10.1234/example.doi", Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("10.1234/example.doi");
        assertThat(results.get(0).title()).isEqualTo("The Impact of Open Access");
        assertThat(results.get(0).url()).isEqualTo("https://example.com/pdf/12345.pdf");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("unpaywall");
    }

    @Test
    void fetch_noExternalId_returnsEmpty() {
        UnpaywallConnector connector = new UnpaywallConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "any query", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));
        assertThat(results).isEmpty();
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));
        UnpaywallConnector connector = new UnpaywallConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.MANUAL, null, "10.1234/something", Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));
        assertThat(results).isEmpty();
    }
}
