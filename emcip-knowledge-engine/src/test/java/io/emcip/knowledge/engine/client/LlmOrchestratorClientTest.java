package io.emcip.knowledge.engine.client;

import static org.assertj.core.api.Assertions.assertThat;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class LlmOrchestratorClientTest {

    private MockWebServer mockWebServer;
    private LlmOrchestratorClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/").toString();
        client =
                new LlmOrchestratorClient(
                        RestClient.builder().baseUrl(baseUrl).build(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void shouldExtractEntitiesFromText() throws Exception {
        String responseJson =
                """
{"success":true,"analysis":"{\\"entities\\":[{\\"type\\":\\"Person\\",\\"label\\":\\"Alice\\"},{\\"type\\":\\"Topic\\",\\"label\\":\\"AI\\"}],\\"relationships\\":[{\\"type\\":\\"DISCUSSES\\",\\"source\\":\\"Alice\\",\\"target\\":\\"AI\\"}]}","model":"test-model"}
""";
        mockWebServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .addHeader("Content-Type", "application/json"));

        var result = client.extract("Alice discussed AI in the chat", "Person,Topic", "DISCUSSES");

        assertThat(result).isNotNull();
        assertThat(result.entities()).isNotEmpty();
        assertThat(result.relationships()).isNotEmpty();
    }

    @Test
    void shouldCallAnalyseEndpointWithEmbedTaskType() throws Exception {
        String responseJson =
                """
                {"success":true,"analysis":"[0.1,0.2,0.3]","model":"embed-model"}
                """;
        mockWebServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .addHeader("Content-Type", "application/json"));

        float[] embedding = client.embed("Some text to embed");

        assertThat(embedding).isNotEmpty();
        var request = mockWebServer.takeRequest();
        assertThat(request.getBody().readUtf8()).contains("EMBED");
    }
}
