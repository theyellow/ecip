package io.emcip.llm.orchestrator.service;

import io.emcip.llm.orchestrator.client.AnthropicLlmClient;
import io.emcip.llm.orchestrator.client.LlmCallResult;
import io.emcip.llm.orchestrator.client.LlmResponse;
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
    private final AnthropicLlmClient anthropicClient;
    private final CostTrackingService costTrackingService;

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

        Optional<ModelConfig> modelOpt = orchestratorService.selectModelForTask(taskType);
        Optional<PromptTemplate> templateOpt = orchestratorService.getPromptTemplate(templateName);

        if (modelOpt.isEmpty()) {
            log.warn("No model configured for task type '{}' - skipping LLM call", taskType);
            return Optional.empty();
        }

        if (templateOpt.isEmpty()) {
            log.warn(
                    "No prompt template '{}' configured for task '{}' - skipping LLM call",
                    templateName,
                    taskType);
            return Optional.empty();
        }

        return Optional.of(
                call(
                        modelOpt.get(),
                        templateOpt.get(),
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
            String renderedUser = orchestratorService.renderPromptTemplate(template, contextVars);
            String finalContent =
                    renderedUser.isEmpty()
                            ? userContent
                            : renderedUser.replace("{{content}}", userContent);

            LlmResponse response =
                    anthropicClient.call(
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
}
