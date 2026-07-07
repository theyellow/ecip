package io.emcip.llm.orchestrator.service;

import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.entity.PromptTemplate;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for LLM orchestration - model routing and prompt template management. Implements
 * US-3.2.1: Model routing and prompt templates.
 */
@Service
public class LlmOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(LlmOrchestratorService.class);

    private final ModelConfigRepository modelConfigRepository;
    private final PromptTemplateRepository promptTemplateRepository;

    public LlmOrchestratorService(
            ModelConfigRepository modelConfigRepository,
            PromptTemplateRepository promptTemplateRepository) {
        this.modelConfigRepository = modelConfigRepository;
        this.promptTemplateRepository = promptTemplateRepository;
    }

    /**
     * Select the best model for a given task type based on priority and configuration.
     *
     * @param taskType The type of task (e.g., "intent", "summary", "response")
     * @return Optional of ModelConfig
     */
    @Transactional(readOnly = true)
    public Optional<ModelConfig> selectModelForTask(String taskType) {
        log.debug("Selecting model for task type: {}", taskType);

        List<ModelConfig> models =
                modelConfigRepository.findByTaskTypeAndActiveTrueOrderByPriorityAsc(taskType);

        if (models.isEmpty()) {
            log.warn("No models configured for task type: {}", taskType);
            return Optional.empty();
        }

        // Return highest priority (lowest priority number) model
        ModelConfig selected = models.get(0);
        log.info(
                "Selected model {} for task type {} (priority: {}, provider: {})",
                selected.getModelKey(),
                taskType,
                selected.getPriority(),
                selected.getProvider());

        return Optional.of(selected);
    }

    /** Get a specific model by its key. */
    @Transactional(readOnly = true)
    public Optional<ModelConfig> getModelByKey(String modelKey) {
        return modelConfigRepository.findByModelKeyAndActiveTrue(modelKey);
    }

    /** Get all active models for a specific provider. */
    @Transactional(readOnly = true)
    public List<ModelConfig> getModelsByProvider(String provider) {
        return modelConfigRepository.findByProviderAndActiveTrue(provider);
    }

    /** Get prompt template by name (active version). */
    @Transactional(readOnly = true)
    public Optional<PromptTemplate> getPromptTemplate(String name) {
        return promptTemplateRepository.findByNameAndActiveTrue(name);
    }

    /** Get specific version of a prompt template. */
    @Transactional(readOnly = true)
    public Optional<PromptTemplate> getPromptTemplateVersion(String name, String version) {
        return promptTemplateRepository.findByNameAndVersion(name, version);
    }

    /** Get all active prompt templates. */
    @Transactional(readOnly = true)
    public List<PromptTemplate> getAllActivePromptTemplates() {
        return promptTemplateRepository.findByActiveTrueOrderByPriorityAsc();
    }

    /**
     * Render a prompt template with variables.
     *
     * @param template The prompt template
     * @param variables Map of variable names to values
     * @return Rendered prompt string
     */
    public String renderPromptTemplate(PromptTemplate template, Map<String, String> variables) {
        if (template == null || template.getUserPromptTemplate() == null) {
            return "";
        }

        String rendered = template.getUserPromptTemplate();
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            rendered = rendered.replace(placeholder, entry.getValue());
        }

        log.debug(
                "Rendered prompt template {} with {} variables",
                template.getName(),
                variables.size());

        return rendered;
    }

    /**
     * Build complete request payload for LLM API call.
     *
     * @param modelConfig The model configuration
     * @param promptTemplate The prompt template
     * @param userContent The user content/message
     * @param contextVariables Additional context variables for template rendering
     * @return Map containing the complete request payload
     */
    public Map<String, Object> buildLlmRequest(
            ModelConfig modelConfig,
            PromptTemplate promptTemplate,
            String userContent,
            Map<String, String> contextVariables) {

        String renderedPrompt = renderPromptTemplate(promptTemplate, contextVariables);

        // Combine user content with rendered template if needed
        String finalUserContent =
                renderedPrompt.isEmpty()
                        ? userContent
                        : renderedPrompt.replace("{{content}}", userContent);

        return Map.of(
                "model", modelConfig.getModelName(),
                "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "system",
                                        "content",
                                        promptTemplate.getSystemPrompt()),
                                Map.of("role", "user", "content", finalUserContent)),
                "temperature", promptTemplate.getTemperature(),
                "max_tokens", promptTemplate.getMaxTokens());
    }

    /** Create or update a model configuration. */
    @Transactional
    public ModelConfig saveModelConfig(ModelConfig config) {
        return modelConfigRepository.save(config);
    }

    /** Create or update a prompt template. */
    @Transactional
    public PromptTemplate savePromptTemplate(PromptTemplate template) {
        return promptTemplateRepository.save(template);
    }

    /** Deactivate a model configuration (soft delete). */
    @Transactional
    public void deactivateModel(String modelKey) {
        modelConfigRepository
                .findByModelKeyAndActiveTrue(modelKey)
                .ifPresent(
                        config -> {
                            config.setActive(false);
                            modelConfigRepository.save(config);
                            log.info("Deactivated model configuration: {}", modelKey);
                        });
    }

    /** Deactivate a prompt template (soft delete). */
    @Transactional
    public void deactivatePromptTemplate(String name) {
        promptTemplateRepository
                .findByNameAndActiveTrue(name)
                .ifPresent(
                        template -> {
                            template.setActive(false);
                            promptTemplateRepository.save(template);
                            log.info("Deactivated prompt template: {}", name);
                        });
    }
}
