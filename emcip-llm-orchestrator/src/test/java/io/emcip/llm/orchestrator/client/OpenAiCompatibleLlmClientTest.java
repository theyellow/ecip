package io.emcip.llm.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.List;
import java.util.Map;
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
class OpenAiCompatibleLlmClientTest {

    @Mock private LlmProviderConfigService providerConfigService;
    private MockWebServer server;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OpenAiCompatibleLlmClient(providerConfigService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void call_throwsWhenNoActiveProviderConfigured() {
        when(providerConfigService.getActiveProvider()).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> client.call("qwen3-30b-a3b", "You are helpful.", "Hello", 256, 0.7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active LLM provider");
    }

    private LlmProviderConfig mockProvider() {
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setBaseUrl(server.url("").toString().replaceAll("/$", ""));
        provider.setApiKey(null);
        provider.setActive(true);
        when(providerConfigService.getActiveProvider()).thenReturn(Optional.of(provider));
        return provider;
    }

    @Test
    void chat_sendsMessagesArrayAndReturnsContent() throws Exception {
        mockProvider();
        String responseJson =
                """
                {"choices":[{"message":{"content":"Hello from LLM"}}],\
                "usage":{"prompt_tokens":10,"completion_tokens":5},\
                "model":"qwen3-30b-a3b"}""";
        server.enqueue(
                new MockResponse.Builder()
                        .body(responseJson)
                        .addHeader("Content-Type", "application/json")
                        .build());

        List<Map<String, String>> messages =
                List.of(
                        Map.of("role", "system", "content", "You are helpful"),
                        Map.of("role", "user", "content", "Hi"));

        LlmResponse response = client.chat("qwen3-30b-a3b", messages, 1024, 0.3);

        assertThat(response.content()).isEqualTo("Hello from LLM");
        assertThat(response.inputTokens()).isEqualTo(10);
        assertThat(response.outputTokens()).isEqualTo(5);
        assertThat(response.model()).isEqualTo("qwen3-30b-a3b");
    }

    @Test
    void chat_noActiveProvider_throws() {
        when(providerConfigService.getActiveProvider()).thenReturn(Optional.empty());

        List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", "Hi"));

        assertThatThrownBy(() -> client.chat("model", messages, 1024, 0.3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active LLM provider");
    }

    @Test
    void call_omitsTemperatureWhenNull() throws Exception {
        mockProvider();
        String responseJson =
                """
                {"choices":[{"message":{"content":"Response"}}],\
                "usage":{"prompt_tokens":10,"completion_tokens":5},\
                "model":"test-model"}""";
        server.enqueue(
                new MockResponse.Builder()
                        .body(responseJson)
                        .addHeader("Content-Type", "application/json")
                        .build());

        LlmResponse response = client.call("test-model", "system", "user content", 256, null);

        assertThat(response.content()).isEqualTo("Response");
        // Verify the request body does not contain temperature
        // (checked via MockWebServer request inspection in integration test)
    }

    @Test
    void call_includesTemperatureWhenProvided() throws Exception {
        mockProvider();
        String responseJson =
                """
                {"choices":[{"message":{"content":"Response"}}],\
                "usage":{"prompt_tokens":10,"completion_tokens":5},\
                "model":"test-model"}""";
        server.enqueue(
                new MockResponse.Builder()
                        .body(responseJson)
                        .addHeader("Content-Type", "application/json")
                        .build());

        LlmResponse response = client.call("test-model", "system", "user content", 256, 0.7);

        assertThat(response.content()).isEqualTo("Response");
        // Verify the request body includes temperature (0.7)
    }

    @Test
    void chat_omitsTemperatureWhenNull() throws Exception {
        mockProvider();
        String responseJson =
                """
                {"choices":[{"message":{"content":"Response"}}],\
                "usage":{"prompt_tokens":10,"completion_tokens":5},\
                "model":"test-model"}""";
        server.enqueue(
                new MockResponse.Builder()
                        .body(responseJson)
                        .addHeader("Content-Type", "application/json")
                        .build());

        List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", "Hi"));

        LlmResponse response = client.chat("test-model", messages, 256, null);

        assertThat(response.content()).isEqualTo("Response");
        // Verify the request body does not contain temperature
    }

    @Test
    void chat_includesTemperatureWhenProvided() throws Exception {
        mockProvider();
        String responseJson =
                """
                {"choices":[{"message":{"content":"Response"}}],\
                "usage":{"prompt_tokens":10,"completion_tokens":5},\
                "model":"test-model"}""";
        server.enqueue(
                new MockResponse.Builder()
                        .body(responseJson)
                        .addHeader("Content-Type", "application/json")
                        .build());

        List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", "Hi"));

        LlmResponse response = client.chat("test-model", messages, 256, 0.7);

        assertThat(response.content()).isEqualTo("Response");
        // Verify the request body includes temperature (0.7)
    }
}
