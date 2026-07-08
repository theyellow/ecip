package io.emcip.llm.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.List;
import java.util.Optional;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleLlmClientBatchTest {

    private MockWebServer mockServer;
    @Mock private LlmProviderConfigService providerConfigService;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        client = new OpenAiCompatibleLlmClient(providerConfigService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.close();
    }

    @Test
    void embedBatch_returnsOrderedEmbeddings() throws Exception {
        var provider = new LlmProviderConfig();
        provider.setBaseUrl(mockServer.url("").toString().replaceAll("/$", ""));
        provider.setApiKey("test-key");
        when(providerConfigService.getActiveProvider()).thenReturn(Optional.of(provider));

        String responseJson =
                """
                {
                  "data": [
                    {"embedding": [0.1, 0.2], "index": 0},
                    {"embedding": [0.3, 0.4], "index": 1}
                  ],
                  "model": "bge-m3"
                }
                """;
        mockServer.enqueue(
                new MockResponse.Builder()
                        .body(responseJson)
                        .addHeader("Content-Type", "application/json")
                        .build());

        List<float[]> result = client.embedBatch("bge-m3", List.of("text one", "text two"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(result.get(1)).containsExactly(0.3f, 0.4f);
    }
}
