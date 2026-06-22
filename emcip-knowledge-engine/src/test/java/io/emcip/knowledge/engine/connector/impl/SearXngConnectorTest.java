package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.TriggerMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SearXngConnectorTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec uriSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @Test
    void fetch_parsesJsonResults_intoEnrichmentResults() {
        String json =
                """
{
  "results": [
    {"url": "https://example.com", "title": "Example", "content": "Some snippet", "engine": "google", "score": 0.9}
  ]
}
""";

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(json);

        var connector =
                new SearXngConnector(restClient, "http://searxng.local", new ObjectMapper());
        var request = new EnrichmentRequest(TriggerMode.MANUAL, "test query", null, Map.of());
        var ctx = new ConnectorContext(null, UUID.randomUUID(), Instant.EPOCH);

        List<EnrichmentResult> results = connector.fetch(request, ctx);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Example");
        assertThat(results.get(0).url()).isEqualTo("https://example.com");
        assertThat(results.get(0).content()).isEqualTo("Some snippet");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("searxng");
    }

    @Test
    void fetch_returnsEmpty_whenBaseUrlIsBlank() {
        var connector = new SearXngConnector(restClient, "", new ObjectMapper());
        var request = new EnrichmentRequest(TriggerMode.MANUAL, "test query", null, Map.of());
        var ctx = new ConnectorContext(null, UUID.randomUUID(), Instant.EPOCH);

        List<EnrichmentResult> results = connector.fetch(request, ctx);

        assertThat(results).isEmpty();
    }

    @Test
    void fetch_returnsEmpty_whenResponseIsNull() {
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(null);

        var connector =
                new SearXngConnector(restClient, "http://searxng.local", new ObjectMapper());
        var request = new EnrichmentRequest(TriggerMode.MANUAL, "test query", null, Map.of());
        var ctx = new ConnectorContext(null, UUID.randomUUID(), Instant.EPOCH);

        List<EnrichmentResult> results = connector.fetch(request, ctx);

        assertThat(results).isEmpty();
    }

    @Test
    void fetch_returnsEmpty_whenQueryIsBlank() {
        var connector =
                new SearXngConnector(restClient, "http://searxng.local", new ObjectMapper());
        var request = new EnrichmentRequest(TriggerMode.MANUAL, "   ", null, Map.of());
        var ctx = new ConnectorContext(null, UUID.randomUUID(), Instant.EPOCH);

        List<EnrichmentResult> results = connector.fetch(request, ctx);

        assertThat(results).isEmpty();
    }

    @Test
    void fetch_returnsEmpty_whenHttpCallThrows() {
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenThrow(new RuntimeException("connection refused"));

        var connector =
                new SearXngConnector(restClient, "http://searxng.local", new ObjectMapper());
        var request = new EnrichmentRequest(TriggerMode.MANUAL, "test query", null, Map.of());
        var ctx = new ConnectorContext(null, UUID.randomUUID(), Instant.EPOCH);

        List<EnrichmentResult> results = connector.fetch(request, ctx);

        assertThat(results).isEmpty();
    }
}
