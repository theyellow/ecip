package io.emcip.llm.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
class OrchestratorControllerBatchEmbedTest {

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private CostTrackingService costTrackingService;
    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private LlmProviderConfigService providerConfigService;
    @Mock private LlmProviderConfigRepository providerConfigRepository;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @InjectMocks private OrchestratorController controller;

    @Test
    void batchEmbed_success_returnsEmbeddings() {
        var model = new ModelConfig();
        model.setModelName("bge-m3");
        when(orchestratorService.selectModelForTask("EMBED")).thenReturn(Optional.of(model));
        when(llmClient.embedBatch("bge-m3", List.of("text one", "text two")))
                .thenReturn(List.of(new float[] {0.1f, 0.2f}, new float[] {0.3f, 0.4f}));

        var request = new OrchestratorController.BatchEmbedRequest(List.of("text one", "text two"));
        var response = controller.batchEmbed(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body.success()).isTrue();
        assertThat(body.embeddings()).hasSize(2);
        assertThat(body.model()).isEqualTo("bge-m3");
    }

    @Test
    void batchEmbed_noModel_returns503() {
        when(orchestratorService.selectModelForTask("EMBED")).thenReturn(Optional.empty());

        var request = new OrchestratorController.BatchEmbedRequest(List.of("text"));
        var response = controller.batchEmbed(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    void batchEmbed_tooManyInputs_returns400() {
        var inputs =
                java.util.stream.IntStream.rangeClosed(1, 33).mapToObj(i -> "text " + i).toList();

        var request = new OrchestratorController.BatchEmbedRequest(inputs);
        var response = controller.batchEmbed(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
