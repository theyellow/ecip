package io.emcip.llm.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

class KnowledgeEngineClientTest {

    private RestClient.RequestBodyUriSpec uriSpec;
    private RestClient.RequestBodySpec bodySpec;
    private RestClient.ResponseSpec responseSpec;
    private RestClient restClient;
    private KnowledgeEngineClient client;

    @BeforeEach
    void setUp() {
        uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        bodySpec = mock(RestClient.RequestBodySpec.class);
        responseSpec = mock(RestClient.ResponseSpec.class);
        restClient = mock(RestClient.class);
        client = new KnowledgeEngineClient(restClient, new ObjectMapper());

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/knowledge/search")).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    void search_returnsDocumentResults() {
        String json =
                """
                {
                  "graphResults": [],
                  "documentResults": [
                    {
                      "document": {
                        "id": "00000000-0000-0000-0000-000000000001",
                        "content": "Fact about climate change.",
                        "sourceRef": "https://example.com/article",
                        "sourceType": "WEBPAGE"
                      },
                      "similarity": 0.85
                    }
                  ]
                }
                """;
        when(responseSpec.body(String.class)).thenReturn(json);

        KnowledgeEngineClient.SearchResponse response =
                client.search("climate change", "HYBRID", UUID.randomUUID(), 5);

        assertThat(response.documentResults()).hasSize(1);
        assertThat(response.documentResults().get(0).similarity()).isEqualTo(0.85);
        assertThat(response.documentResults().get(0).document().content())
                .isEqualTo("Fact about climate change.");
    }

    @Test
    void search_returnsEmptyOnNullResponse() {
        when(responseSpec.body(String.class)).thenReturn(null);

        KnowledgeEngineClient.SearchResponse response = client.search("query", "HYBRID", null, 5);

        assertThat(response.documentResults()).isEmpty();
        assertThat(response.graphResults()).isEmpty();
    }

    @Test
    void search_returnsEmptyOnNetworkError() {
        when(responseSpec.body(String.class)).thenThrow(new RestClientException("timeout"));

        KnowledgeEngineClient.SearchResponse response =
                client.search("query", "HYBRID", UUID.randomUUID(), 5);

        assertThat(response.documentResults()).isEmpty();
    }
}
