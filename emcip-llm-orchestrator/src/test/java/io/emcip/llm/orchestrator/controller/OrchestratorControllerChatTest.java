package io.emcip.llm.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.client.LlmResponse;
import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import io.emcip.llm.orchestrator.service.CostTrackingService;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OrchestratorControllerChatTest {

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private CostTrackingService costTrackingService;
    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private LlmProviderConfigService providerConfigService;
    @Mock private LlmProviderConfigRepository providerConfigRepository;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @InjectMocks private OrchestratorController controller;

    @Test
    void chat_success() {
        ModelConfig model = new ModelConfig();
        model.setModelName("qwen3-30b-a3b");
        when(orchestratorService.selectModelForTask("GENERAL")).thenReturn(Optional.of(model));
        when(llmClient.chat(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(new LlmResponse("analysis result", 100, 50, "qwen3-30b-a3b"));

        var request =
                new OrchestratorController.ChatRequest(
                        List.of(
                                new OrchestratorController.ChatMessage("system", "You are helpful"),
                                new OrchestratorController.ChatMessage("user", "Analyse this")),
                        "GENERAL");

        var response = controller.chat(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().content()).isEqualTo("analysis result");
        assertThat(response.getBody().model()).isEqualTo("qwen3-30b-a3b");
    }

    @Test
    void chat_noModel_returns503() {
        when(orchestratorService.selectModelForTask("GENERAL")).thenReturn(Optional.empty());

        var request =
                new OrchestratorController.ChatRequest(
                        List.of(new OrchestratorController.ChatMessage("user", "Hi")), null);

        var response = controller.chat(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().success()).isFalse();
    }
}
