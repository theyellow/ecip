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

class ArxivConnectorTest extends ConnectorTestBase {

    private static final String ATOM_RESPONSE =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>http://arxiv.org/abs/2301.00001v1</id>
                <title>Quantum Advantage in Machine Learning</title>
                <summary>We demonstrate quantum advantage...</summary>
                <published>2023-01-01T00:00:00Z</published>
                <link href="https://arxiv.org/abs/2301.00001" rel="alternate"/>
              </entry>
            </feed>
            """;

    @Test
    void fetch_parsesAtomXml() {
        server.enqueue(
                new MockResponse()
                        .setBody(ATOM_RESPONSE)
                        .addHeader("Content-Type", "application/atom+xml"));

        ArxivConnector connector = new ArxivConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(
                                TriggerMode.SCHEDULED, "quantum machine learning", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("2301.00001");
        assertThat(results.get(0).title()).isEqualTo("Quantum Advantage in Machine Learning");
        assertThat(results.get(0).content()).contains("quantum advantage");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("arxiv");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));

        ArxivConnector connector = new ArxivConnector(restClient, baseUrl());
        List<EnrichmentResult> results =
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.SCHEDULED, "topic", null, Map.of()),
                        new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).isEmpty();
    }
}
