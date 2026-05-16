package io.emcip.llm.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleLlmClientTest {

    @Mock LlmProviderConfigService providerConfigService;

    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAiCompatibleLlmClient(providerConfigService, new ObjectMapper());
    }

    @Test
    void call_throwsWhenNoActiveProviderConfigured() {
        when(providerConfigService.getActiveProvider()).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> client.call("qwen3-30b-a3b", "You are helpful.", "Hello", 256, 0.7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active LLM provider");
    }
}
