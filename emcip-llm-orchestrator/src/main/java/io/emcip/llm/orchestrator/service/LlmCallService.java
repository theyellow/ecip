package io.emcip.llm.orchestrator.service;

import io.emcip.common.tenant.TenantContext;
import io.emcip.llm.orchestrator.client.LlmCallResult;
import io.emcip.llm.orchestrator.client.LlmResponse;
import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.config.KnowledgeEnrichmentProperties;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.entity.PromptTemplate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates external LLM calls: model routing → template rendering → API call → cost logging.
 * Implements US-3.2.2: external LLM integration.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmCallService {

    private final LlmOrchestratorService orchestratorService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final CostTrackingService costTrackingService;
    private final KnowledgeContextEnricherService knowledgeContextEnricherService;
    private final KnowledgeEnrichmentProperties knowledgeEnrichmentProperties;

    /**
     * Select model and template by task type, then call the LLM.
     *
     * @param taskType Task type for model routing (e.g. "response", "summary")
     * @param templateName Prompt template name to use
     * @param userContent User message content
     * @param contextVars Variables for prompt template rendering
     * @param sourceEventId Source event ID for cost tracking
     * @param conversationId Conversation ID for cost tracking (nullable)
     * @return LlmCallResult if model and template are configured, empty otherwise
     */
    public Optional<LlmCallResult> callForTask(
            String taskType,
            String templateName,
            String userContent,
            Map<String, String> contextVars,
            String sourceEventId,
            String conversationId) {

        Optional<PromptTemplate> templateOpt = orchestratorService.getPromptTemplate(templateName);

        if (templateOpt.isEmpty()) {
            log.warn(
                    "No prompt template '{}' configured for task '{}' - skipping LLM call",
                    templateName,
                    taskType);
            return Optional.empty();
        }

        PromptTemplate template = templateOpt.get();

        // Template owns model choice; fall back to taskType selection if not set
        ModelConfig modelConfig;
        if (template.getModelConfig() != null) {
            modelConfig = template.getModelConfig();
            log.debug("Using template's model config: {}", modelConfig.getModelKey());
        } else {
            Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
            if (modelOpt.isEmpty()) {
                log.warn("No model configured for task type '{}' - skipping LLM call", taskType);
                return Optional.empty();
            }
            modelConfig = modelOpt.get();
            log.debug("Template has no model config, falling back to task type: {}", taskType);
        }

        return Optional.of(
                call(
                        modelConfig,
                        template,
                        userContent,
                        contextVars,
                        sourceEventId,
                        conversationId));
    }

    /**
     * Call the LLM with explicit model and template.
     *
     * @param modelConfig Model configuration to use
     * @param template Prompt template to use
     * @param userContent User message content
     * @param contextVars Variables for prompt template rendering
     * @param sourceEventId Source event ID for cost tracking
     * @param conversationId Conversation ID for cost tracking (nullable)
     * @return LlmCallResult with success status, content, and request ID
     */
    public LlmCallResult call(
            ModelConfig modelConfig,
            PromptTemplate template,
            String userContent,
            Map<String, String> contextVars,
            String sourceEventId,
            String conversationId) {

        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        try {
            UUID tenantUuid =
                    TenantContext.getTenantId() != null
                            ? UUID.fromString(TenantContext.getTenantId())
                            : null;
            // Wrap user content with boundary markers (RT-002/003)
            String markedContent =
                    "<<<USER_CONTENT_BEGIN>>>\n" + userContent + "\n<<<USER_CONTENT_END>>>";

            String enrichedContent =
                    knowledgeEnrichmentProperties.enabled()
                            ? buildEnrichedContent(userContent, markedContent, tenantUuid)
                            : markedContent;

            String renderedUser = orchestratorService.renderPromptTemplate(template, contextVars);
            String finalContent =
                    renderedUser.isEmpty()
                            ? enrichedContent
                            : renderedUser.replace("{{content}}", enrichedContent);

            LlmResponse response =
                    llmClient.call(
                            modelConfig.getModelName(),
                            template.getSystemPrompt(),
                            finalContent,
                            template.getMaxTokens(),
                            template.getTemperature());

            long latencyMs = System.currentTimeMillis() - startTime;

            costTrackingService.logSuccessfulCall(
                    requestId,
                    modelConfig,
                    template.getName(),
                    response.inputTokens(),
                    response.outputTokens(),
                    latencyMs,
                    sourceEventId,
                    conversationId);

            log.info(
                    "LLM call successful: request={}, model={}, tokens={}, latency={}ms",
                    requestId,
                    response.model(),
                    response.inputTokens() + response.outputTokens(),
                    latencyMs);

            return new LlmCallResult(true, response.content(), response.model(), requestId);

        } catch (Exception e) {
            log.error(
                    "LLM call failed: request={}, model={}, error={}",
                    requestId,
                    modelConfig.getModelKey(),
                    e.getMessage(),
                    e);

            costTrackingService.logFailedCall(
                    requestId,
                    modelConfig,
                    template.getName(),
                    e.getMessage(),
                    sourceEventId,
                    conversationId);

            return new LlmCallResult(false, null, modelConfig.getModelName(), requestId);
        }
    }

    private String buildEnrichedContent(String userQuery, String markedContent, UUID tenantId) {
        String context = knowledgeContextEnricherService.buildContext(userQuery, tenantId);
        if (context.isBlank()) {
            return markedContent;
        }
        return "Relevant context from the knowledge base:\n"
                + context
                + "\n\n---\n\n"
                + markedContent;
    }
}
