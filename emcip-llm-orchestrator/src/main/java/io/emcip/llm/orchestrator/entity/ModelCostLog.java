package io.emcip.llm.orchestrator.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * JPA entity for tracking AI model call costs. Supports cost analysis and budget monitoring
 * (US-3.2.3).
 */
@Entity
@Table(name = "model_cost_logs")
public class ModelCostLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String requestId;

    @Column(nullable = false, length = 50)
    private String modelProvider;

    @Column(nullable = false, length = 50)
    private String modelName;

    @Column(nullable = false, length = 100)
    private String promptTemplateName;

    @Column(nullable = false)
    private Integer inputTokens;

    @Column(nullable = false)
    private Integer outputTokens;

    @Column(nullable = false)
    private Integer totalTokens;

    @Column(nullable = false, precision = 10, scale = 6)
    private Double inputCostUsd;

    @Column(nullable = false, precision = 10, scale = 6)
    private Double outputCostUsd;

    @Column(nullable = false, precision = 10, scale = 6)
    private Double totalCostUsd;

    @Column(nullable = false)
    private Long latencyMs;

    @Column(length = 50)
    private String status;

    @Column(length = 500)
    private String errorMessage;

    @Column(nullable = false, length = 100)
    private String sourceEventId;

    @Column(length = 100)
    private String conversationId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Version private Long versionLock;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getPromptTemplateName() {
        return promptTemplateName;
    }

    public void setPromptTemplateName(String promptTemplateName) {
        this.promptTemplateName = promptTemplateName;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Double getInputCostUsd() {
        return inputCostUsd;
    }

    public void setInputCostUsd(Double inputCostUsd) {
        this.inputCostUsd = inputCostUsd;
    }

    public Double getOutputCostUsd() {
        return outputCostUsd;
    }

    public void setOutputCostUsd(Double outputCostUsd) {
        this.outputCostUsd = outputCostUsd;
    }

    public Double getTotalCostUsd() {
        return totalCostUsd;
    }

    public void setTotalCostUsd(Double totalCostUsd) {
        this.totalCostUsd = totalCostUsd;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(String sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Long getVersionLock() {
        return versionLock;
    }

    public void setVersionLock(Long versionLock) {
        this.versionLock = versionLock;
    }
}
