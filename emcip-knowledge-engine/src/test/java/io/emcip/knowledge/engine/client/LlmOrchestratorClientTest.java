package io.emcip.knowledge.engine.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
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
                        RestClient.builder().baseUrl(baseUrl).build(),
                        new ObjectMapper(),
                        CircuitBreakerRegistry.ofDefaults());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void shouldExtractEntitiesFromText() throws Exception {
        mockWebServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"success\":true,\"analysis\":\"{\\\"entities\\\":[{\\\"type\\\":\\\"Person\\\",\\\"label\\\":\\\"Alice\\\"},{\\\"type\\\":\\\"Topic\\\",\\\"label\\\":\\\"AI\\\"}],\\\"relationships\\\":[{\\\"type\\\":\\\"DISCUSSES\\\",\\\"source\\\":\\\"Alice\\\",\\\"target\\\":\\\"AI\\\"}]}\",\"model\":\"test-model\"}")
                        .addHeader("Content-Type", "application/json"));

        ConceptType person = new ConceptType();
        person.setName("Person");
        person.setDescription("A human");
        person.setShared(false);
        ConceptType topic = new ConceptType();
        topic.setName("Topic");
        topic.setDescription("A subject");
        topic.setShared(false);
        RelationshipType discusses = new RelationshipType();
        discusses.setName("DISCUSSES");
        discusses.setDescription("Connects a person to a topic");
        discusses.setSourceTypes(List.of("Person"));
        discusses.setTargetTypes(List.of("Topic"));

        var result =
                client.extract(
                        "Alice discussed AI in the chat",
                        List.of(person, topic),
                        List.of(discusses));

        assertThat(result).isNotNull();
        assertThat(result.entities()).isNotEmpty();
        assertThat(result.relationships()).isNotEmpty();
    }

    @Test
    void shouldBuildOntologyDrivenPromptWithDescriptions() throws Exception {
        mockWebServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"success\":true,\"analysis\":\"{\\\"entities\\\":[],\\\"relationships\\\":[]}\",\"model\":\"test\"}")
                        .addHeader("Content-Type", "application/json"));

        ConceptType person = new ConceptType();
        person.setName("PERSON");
        person.setDescription("A human individual");
        person.setShared(false);

        RelationshipType knows = new RelationshipType();
        knows.setName("KNOWS");
        knows.setDescription("One person knows another");
        knows.setSourceTypes(List.of("PERSON"));
        knows.setTargetTypes(List.of("PERSON"));

        client.extract("Alice knows Bob", List.of(person), List.of(knows));

        var request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("PERSON");
        assertThat(body).contains("A human individual");
        assertThat(body).contains("KNOWS");
        assertThat(body).contains("One person knows another");
        assertThat(body).contains("EXTRACT");
    }

    @Test
    void shouldParseResponseWithThinkTags() throws Exception {
        // qwen3 models wrap output in <think>...</think> tags
        String analysis =
                "<think>Let me analyze this text...</think>"
                        + "{\"entities\":[{\"type\":\"Person\",\"label\":\"Bob\"}],"
                        + "\"relationships\":[]}";
        String escaped = analysis.replace("\"", "\\\"");
        mockWebServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"success\":true,\"analysis\":\""
                                        + escaped
                                        + "\",\"model\":\"qwen3\"}")
                        .addHeader("Content-Type", "application/json"));

        ConceptType person = new ConceptType();
        person.setName("Person");
        person.setDescription("A human");
        person.setShared(false);

        var result = client.extract("Bob likes cats", List.of(person), List.of());

        assertThat(result.entities()).hasSize(1);
        assertThat(result.entities().getFirst().type()).isEqualTo("Person");
        assertThat(result.entities().getFirst().label()).isEqualTo("Bob");
    }

    @Test
    void shouldCallEmbedEndpoint() throws Exception {
        String responseJson =
                """
                {"success":true,"embedding":[0.1,0.2,0.3],"model":"embed-model"}
                """;
        mockWebServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .addHeader("Content-Type", "application/json"));

        float[] embedding = client.embed("Some text to embed");

        assertThat(embedding).isNotEmpty();
        assertThat(embedding).hasSize(3);
        assertThat(embedding[0]).isEqualTo(0.1f, org.assertj.core.data.Offset.offset(0.001f));
        var request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/embed");
        assertThat(request.getBody().readUtf8()).contains("Some text to embed");
    }

    @Test
    void shouldCallBatchEmbedEndpoint() throws Exception {
        String responseJson =
                """
                {"success":true,"embeddings":[[0.1,0.2],[0.3,0.4]],"model":"bge-m3"}
                """;
        mockWebServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .addHeader("Content-Type", "application/json"));

        List<float[]> result = client.embedBatch(List.of("text one", "text two"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(result.get(1)).containsExactly(0.3f, 0.4f);

        var recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getPath()).isEqualTo("/api/embed/batch");
    }
}
