package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.entity.PromptTemplate;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for LlmOrchestratorService covering model routing, template versioning, rendering and
 * deactivation.
 */
@ExtendWith(MockitoExtension.class)
class LlmOrchestratorServiceTest {

    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;

    @InjectMocks private LlmOrchestratorService service;

    private ModelConfig modelConfig(String modelKey, int priority) {
        return ModelConfig.builder()
                .id(UUID.randomUUID())
                .modelKey(modelKey)
                .provider("anthropic")
                .modelName("claude-haiku-4-5-20251001")
                .description("Test model")
                .taskType("response")
                .priority(priority)
                .active(true)
                .inputCostPer1kTokens(0.80)
                .outputCostPer1kTokens(4.00)
                .contextWindow(200000)
                .maxOutputTokens(4096)
                .avgLatencyMs(500.0)
                .build();
    }

    private PromptTemplate template(String name, String version) {
        return PromptTemplate.builder()
                .id(UUID.randomUUID())
                .name(name)
                .version(version)
                .description("Test template")
                .userPromptTemplate("Respond to: {{content}}")
                .systemPrompt("You are helpful.")
                .modelProvider("anthropic")
                .modelName("claude-haiku-4-5-20251001")
                .temperature(0.7)
                .maxTokens(2048)
                .active(true)
                .priority(1)
                .build();
    }

    // --- Model Routing Tests ---

    @Test
    void selectModel_multipleModels_returnsHighestPriority() {
        ModelConfig model1Priority1 = modelConfig("claude-haiku", 1);
        ModelConfig model2Priority2 = modelConfig("claude-sonnet", 2);
        when(modelConfigRepository.findByTaskTypeAndActiveTrueOrderByPriorityAsc("response"))
                .thenReturn(List.of(model1Priority1, model2Priority2));

        Optional<ModelConfig> result = service.selectModelForTask("response");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(model1Priority1);
    }

    @Test
    void selectModel_noModelsForTask_returnsEmpty() {
        when(modelConfigRepository.findByTaskTypeAndActiveTrueOrderByPriorityAsc("response"))
                .thenReturn(List.of());

        Optional<ModelConfig> result = service.selectModelForTask("response");

        assertThat(result).isEmpty();
    }

    @Test
    void selectModel_singleModel_returnsThatModel() {
        ModelConfig model = modelConfig("claude-haiku", 1);
        when(modelConfigRepository.findByTaskTypeAndActiveTrueOrderByPriorityAsc("response"))
                .thenReturn(List.of(model));

        Optional<ModelConfig> result = service.selectModelForTask("response");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(model);
    }

    @Test
    void getModelByKey_found_returnsModel() {
        ModelConfig model = modelConfig("claude-haiku", 1);
        when(modelConfigRepository.findByModelKeyAndActiveTrue("claude-haiku"))
                .thenReturn(Optional.of(model));

        Optional<ModelConfig> result = service.getModelByKey("claude-haiku");

        assertThat(result).isPresent();
        assertThat(result.get().getModelKey()).isEqualTo("claude-haiku");
    }

    @Test
    void getModelByKey_notFound_returnsEmpty() {
        when(modelConfigRepository.findByModelKeyAndActiveTrue("unknown"))
                .thenReturn(Optional.empty());

        Optional<ModelConfig> result = service.getModelByKey("unknown");

        assertThat(result).isEmpty();
    }

    // --- Template Versioning Tests ---

    @Test
    void getPromptTemplate_activeVersion_found() {
        PromptTemplate tmpl = template("response-template", "1.0");
        when(promptTemplateRepository.findByNameAndActiveTrue("response-template"))
                .thenReturn(Optional.of(tmpl));

        Optional<PromptTemplate> result = service.getPromptTemplate("response-template");

        assertThat(result).isPresent();
    }

    @Test
    void getPromptTemplateVersion_specificVersion_found() {
        PromptTemplate templateV2 = template("response-template", "2.0");
        when(promptTemplateRepository.findByNameAndVersion("response-template", "2.0"))
                .thenReturn(Optional.of(templateV2));

        Optional<PromptTemplate> result =
                service.getPromptTemplateVersion("response-template", "2.0");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("response-template");
        assertThat(result.get().getVersion()).isEqualTo("2.0");
    }

    @Test
    void getPromptTemplateVersion_versionNotFound_returnsEmpty() {
        when(promptTemplateRepository.findByNameAndVersion("response-template", "99.0"))
                .thenReturn(Optional.empty());

        Optional<PromptTemplate> result =
                service.getPromptTemplateVersion("response-template", "99.0");

        assertThat(result).isEmpty();
    }

    // --- renderPromptTemplate Tests ---

    @Test
    void renderPromptTemplate_substitutesVariables() {
        PromptTemplate tmpl =
                PromptTemplate.builder()
                        .id(UUID.randomUUID())
                        .name("test")
                        .version("1.0")
                        .description("Test")
                        .userPromptTemplate("Hello {{name}}, you said: {{content}}")
                        .systemPrompt("You are helpful.")
                        .modelProvider("anthropic")
                        .modelName("claude-haiku-4-5-20251001")
                        .build();
        Map<String, String> variables = Map.of("name", "Alice", "content", "test");

        String result = service.renderPromptTemplate(tmpl, variables);

        assertThat(result).isEqualTo("Hello Alice, you said: test");
    }

    @Test
    void renderPromptTemplate_nullTemplate_returnsEmpty() {
        String result = service.renderPromptTemplate(null, Map.of());

        assertThat(result).isEqualTo("");
    }

    @Test
    void renderPromptTemplate_noVariables_returnsTemplateUnchanged() {
        PromptTemplate tmpl =
                PromptTemplate.builder()
                        .id(UUID.randomUUID())
                        .name("test")
                        .version("1.0")
                        .description("Test")
                        .userPromptTemplate("Static prompt")
                        .systemPrompt("You are helpful.")
                        .modelProvider("anthropic")
                        .modelName("claude-haiku-4-5-20251001")
                        .build();

        String result = service.renderPromptTemplate(tmpl, Map.of());

        assertThat(result).isEqualTo("Static prompt");
    }

    @Test
    void renderPromptTemplate_unknownVariable_leavesPlaceholder() {
        PromptTemplate tmpl =
                PromptTemplate.builder()
                        .id(UUID.randomUUID())
                        .name("test")
                        .version("1.0")
                        .description("Test")
                        .userPromptTemplate("Hello {{unknown}}")
                        .systemPrompt("You are helpful.")
                        .modelProvider("anthropic")
                        .modelName("claude-haiku-4-5-20251001")
                        .build();
        Map<String, String> variables = Map.of("name", "Alice");

        String result = service.renderPromptTemplate(tmpl, variables);

        assertThat(result).isEqualTo("Hello {{unknown}}");
    }

    // --- Deactivation Tests ---

    @Test
    void deactivateModel_existing_setsActiveFalseAndSaves() {
        ModelConfig model = modelConfig("claude-haiku", 1);
        when(modelConfigRepository.findByModelKeyAndActiveTrue("claude-haiku"))
                .thenReturn(Optional.of(model));

        service.deactivateModel("claude-haiku");

        assertThat(model.getActive()).isFalse();
        verify(modelConfigRepository).save(model);
    }

    @Test
    void deactivateModel_notFound_doesNothing() {
        when(modelConfigRepository.findByModelKeyAndActiveTrue("missing"))
                .thenReturn(Optional.empty());

        service.deactivateModel("missing");

        verify(modelConfigRepository, never()).save(any());
    }
}
