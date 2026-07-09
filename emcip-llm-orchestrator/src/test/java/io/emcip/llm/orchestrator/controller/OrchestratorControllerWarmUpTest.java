package io.emcip.llm.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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
class OrchestratorControllerWarmUpTest {

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private CostTrackingService costTrackingService;
    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private LlmProviderConfigService providerConfigService;
    @Mock private LlmProviderConfigRepository providerConfigRepository;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @InjectMocks private OrchestratorController controller;

    @Test
    void warmUp_bothTaskTypes_returnsReadyStatus() {
        var embedModel = new ModelConfig();
        embedModel.setModelName("bge-m3");
        var extractModel = new ModelConfig();
        extractModel.setModelName("qwen3-14b");

        when(orchestratorService.selectModelForTask("EMBED")).thenReturn(Optional.of(embedModel));
        when(orchestratorService.selectModelForTask("EXTRACT"))
                .thenReturn(Optional.of(extractModel));
        when(llmClient.embed("bge-m3", "ping")).thenReturn(new float[] {0.1f});
        when(llmClient.chat(anyString(), any(), anyInt(), isNull()))
                .thenReturn(new LlmResponse("pong", 10, 5, "qwen3-14b"));

        var request = new OrchestratorController.WarmUpRequest(List.of("EMBED", "EXTRACT"));
        var response = controller.warmUp(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.results()).containsKey("EMBED");
        assertThat(body.results().get("EMBED").ready()).isTrue();
        assertThat(body.results().get("EMBED").model()).isEqualTo("bge-m3");
        assertThat(body.results().containsKey("EXTRACT")).isTrue();
        assertThat(body.results().get("EXTRACT").ready()).isTrue();
    }

    @Test
    void warmUp_noModelConfigured_returnsNotReady() {
        when(orchestratorService.selectModelForTask("EMBED")).thenReturn(Optional.empty());

        var request = new OrchestratorController.WarmUpRequest(List.of("EMBED"));
        var response = controller.warmUp(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body.results().get("EMBED").ready()).isFalse();
        assertThat(body.results().get("EMBED").error()).isNotNull();
    }

    @Test
    void warmUp_embedFails_returnsNotReadyWithError() {
        var embedModel = new ModelConfig();
        embedModel.setModelName("bge-m3");
        when(orchestratorService.selectModelForTask("EMBED")).thenReturn(Optional.of(embedModel));
        when(llmClient.embed("bge-m3", "ping")).thenThrow(new RuntimeException("timeout"));

        var request = new OrchestratorController.WarmUpRequest(List.of("EMBED"));
        var response = controller.warmUp(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().results().get("EMBED").ready()).isFalse();
        assertThat(response.getBody().results().get("EMBED").error()).contains("timeout");
    }
}
