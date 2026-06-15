package io.emcip.llm.orchestrator.service;

import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.entity.ModelCostLog;
import io.emcip.llm.orchestrator.repository.ModelCostLogRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for tracking and logging AI model call costs. Implements US-3.2.3: Track and log AI call
 * costs.
 */
@Service
public class CostTrackingService {

    private static final Logger log = LoggerFactory.getLogger(CostTrackingService.class);

    private final ModelCostLogRepository costLogRepository;

    public CostTrackingService(ModelCostLogRepository costLogRepository) {
        this.costLogRepository = costLogRepository;
    }

    /**
     * Log a successful AI model call with cost information.
     *
     * @param requestId Unique request identifier
     * @param modelConfig The model configuration used
     * @param promptTemplateName Name of the prompt template used
     * @param inputTokens Number of input tokens
     * @param outputTokens Number of output tokens
     * @param latencyMs Latency in milliseconds
     * @param sourceEventId Source event ID
     * @param conversationId Conversation ID (optional)
     * @return The created cost log entry
     */
    @Transactional
    public ModelCostLog logSuccessfulCall(
            String requestId,
            ModelConfig modelConfig,
            String promptTemplateName,
            int inputTokens,
            int outputTokens,
            long latencyMs,
            String sourceEventId,
            String conversationId) {

        int totalTokens = inputTokens + outputTokens;

        // Calculate costs based on model pricing
        double inputCost = (inputTokens / 1000.0) * modelConfig.getInputCostPer1kTokens();
        double outputCost = (outputTokens / 1000.0) * modelConfig.getOutputCostPer1kTokens();
        double totalCost = inputCost + outputCost;

        ModelCostLog costLog = new ModelCostLog();
        costLog.setId(UUID.randomUUID());
        costLog.setRequestId(requestId);
        costLog.setModelProvider(modelConfig.getProvider());
        costLog.setModelName(modelConfig.getModelName());
        costLog.setPromptTemplateName(promptTemplateName);
        costLog.setInputTokens(inputTokens);
        costLog.setOutputTokens(outputTokens);
        costLog.setTotalTokens(totalTokens);
        costLog.setInputCostUsd(inputCost);
        costLog.setOutputCostUsd(outputCost);
        costLog.setTotalCostUsd(totalCost);
        costLog.setLatencyMs(latencyMs);
        costLog.setStatus("SUCCESS");
        costLog.setSourceEventId(sourceEventId);
        costLog.setConversationId(conversationId);

        ModelCostLog saved = costLogRepository.save(costLog);

        log.info(
                "Logged successful LLM call: request={}, model={}, tokens={}, cost=${:.6f},"
                        + " latency={}ms",
                requestId,
                modelConfig.getModelKey(),
                totalTokens,
                totalCost,
                latencyMs);

        return saved;
    }

    /**
     * Log a failed AI model call.
     *
     * @param requestId Unique request identifier
     * @param modelConfig The model configuration attempted
     * @param promptTemplateName Name of the prompt template used
     * @param errorMessage Error message
     * @param sourceEventId Source event ID
     * @param conversationId Conversation ID (optional)
     * @return The created cost log entry
     */
    @Transactional
    public ModelCostLog logFailedCall(
            String requestId,
            ModelConfig modelConfig,
            String promptTemplateName,
            String errorMessage,
            String sourceEventId,
            String conversationId) {

        ModelCostLog costLog = new ModelCostLog();
        costLog.setId(UUID.randomUUID());
        costLog.setRequestId(requestId);
        costLog.setModelProvider(modelConfig.getProvider());
        costLog.setModelName(modelConfig.getModelName());
        costLog.setPromptTemplateName(promptTemplateName);
        costLog.setInputTokens(0);
        costLog.setOutputTokens(0);
        costLog.setTotalTokens(0);
        costLog.setInputCostUsd(0.0);
        costLog.setOutputCostUsd(0.0);
        costLog.setTotalCostUsd(0.0);
        costLog.setLatencyMs(0L);
        costLog.setStatus("FAILED");
        costLog.setErrorMessage(errorMessage);
        costLog.setSourceEventId(sourceEventId);
        costLog.setConversationId(conversationId);

        ModelCostLog saved = costLogRepository.save(costLog);

        log.warn(
                "Logged failed LLM call: request={}, model={}, error={}",
                requestId,
                modelConfig.getModelKey(),
                errorMessage);

        return saved;
    }

    /**
     * Get cost statistics for a specific time period.
     *
     * @param start Start of period
     * @param end End of period
     * @return Total cost in USD
     */
    @Transactional(readOnly = true)
    public Double getTotalCostForPeriod(Instant start, Instant end) {
        Double totalCost = costLogRepository.calculateTotalCostForPeriod(start, end);
        return totalCost != null ? totalCost : 0.0;
    }

    /**
     * Get total tokens used for a specific model in a time period.
     *
     * @param modelName Model name
     * @param start Start of period
     * @param end End of period
     * @return Total token count
     */
    @Transactional(readOnly = true)
    public Long getTotalTokensForModel(String modelName, Instant start, Instant end) {
        Long totalTokens = costLogRepository.calculateTotalTokensForModel(modelName, start, end);
        return totalTokens != null ? totalTokens : 0L;
    }

    /** Find cost log by request ID. */
    @Transactional(readOnly = true)
    public Optional<ModelCostLog> findByRequestId(String requestId) {
        return costLogRepository.findByRequestId(requestId);
    }

    /**
     * Calculate estimated cost before making a call.
     *
     * @param modelConfig Model configuration
     * @param estimatedInputTokens Estimated input tokens
     * @param estimatedOutputTokens Estimated output tokens
     * @return Estimated cost in USD
     */
    public double estimateCost(
            ModelConfig modelConfig, int estimatedInputTokens, int estimatedOutputTokens) {
        double inputCost = (estimatedInputTokens / 1000.0) * modelConfig.getInputCostPer1kTokens();
        double outputCost =
                (estimatedOutputTokens / 1000.0) * modelConfig.getOutputCostPer1kTokens();
        return inputCost + outputCost;
    }

    /** Get aggregated totals for a time period. */
    @Transactional(readOnly = true)
    public Map<String, Object> getTotals(Instant start, Instant end) {
        Object[] row = costLogRepository.calculateTotals(start, end);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCostUsd", row[0] != null ? ((Number) row[0]).doubleValue() : 0.0);
        result.put("totalTokens", row[1] != null ? ((Number) row[1]).longValue() : 0L);
        result.put("callCount", ((Number) row[2]).longValue());
        result.put("avgLatencyMs", row[3] != null ? ((Number) row[3]).doubleValue() : 0.0);
        result.put("successCount", ((Number) row[4]).longValue());
        result.put("failureCount", ((Number) row[5]).longValue());
        return result;
    }

    /** Get per-model aggregation for a time period. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getByModel(Instant start, Instant end) {
        return costLogRepository.aggregateByModel(start, end).stream()
                .map(
                        row -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("modelName", row[0]);
                            m.put("callCount", ((Number) row[1]).longValue());
                            m.put("inputTokens", ((Number) row[2]).longValue());
                            m.put("outputTokens", ((Number) row[3]).longValue());
                            m.put("totalTokens", ((Number) row[4]).longValue());
                            m.put("totalCostUsd", ((Number) row[5]).doubleValue());
                            m.put("avgLatencyMs", ((Number) row[6]).doubleValue());
                            return m;
                        })
                .toList();
    }

    /** Get per-day aggregation for a time period. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getByDay(Instant start, Instant end) {
        return costLogRepository.aggregateByDay(start, end).stream()
                .map(
                        row -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            // Native query returns java.sql.Date for DATE()
                            m.put("date", row[0].toString());
                            m.put("totalCostUsd", ((Number) row[1]).doubleValue());
                            m.put("callCount", ((Number) row[2]).longValue());
                            m.put("totalTokens", ((Number) row[3]).longValue());
                            return m;
                        })
                .toList();
    }
}
