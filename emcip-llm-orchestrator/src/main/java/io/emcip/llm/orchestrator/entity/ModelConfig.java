package io.emcip.llm.orchestrator.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA entity for storing model routing configuration. Defines available models and their
 * cost/performance characteristics.
 */
@Schema(description = "LLM model routing configuration with cost and performance characteristics")
@Entity
@Table(name = "model_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelConfig {

    @Schema(description = "Unique model config ID")
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Schema(description = "Unique routing key used to select this model", example = "gpt4-turbo")
    @Column(nullable = false, unique = true, length = 50)
    private String modelKey;

    @Schema(description = "LLM provider name", example = "openai")
    @Column(nullable = false, length = 50)
    private String provider;

    @Schema(
            description = "Underlying model name as recognized by the provider",
            example = "gpt-4-turbo")
    @Column(nullable = false, length = 100)
    private String modelName;

    @Schema(description = "Human-readable description of this model configuration")
    @Column(nullable = false, length = 200)
    private String description;

    @Schema(
            description = "Task category this model is optimised for",
            example = "CHAT",
            allowableValues = {
                "GENERAL",
                "CLASSIFICATION",
                "MODERATION",
                "EMBED",
                "CHAT",
                "SUMMARIZATION"
            })
    @Column(nullable = false, length = 50)
    private String taskType;

    @Schema(description = "Cost per 1 000 input tokens in USD", example = "0.01")
    @Column(nullable = false)
    private Double inputCostPer1kTokens;

    @Schema(description = "Cost per 1 000 output tokens in USD", example = "0.03")
    @Column(nullable = false)
    private Double outputCostPer1kTokens;

    @Schema(description = "Maximum context window in tokens", example = "128000")
    @Column(nullable = false)
    private Integer contextWindow;

    @Schema(description = "Maximum number of output tokens", example = "4096")
    @Column(nullable = false)
    private Integer maxOutputTokens;

    @Schema(description = "Average observed latency in milliseconds", example = "850.0")
    @Column(nullable = false)
    private Double avgLatencyMs;

    @Schema(description = "Whether this model supports streaming responses")
    @Column(nullable = false)
    @Builder.Default
    private Boolean supportsStreaming = false;

    @Schema(description = "Whether this model config is available for routing")
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Schema(description = "Routing priority — lower number wins", example = "100")
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 100;

    @Schema(description = "Creation timestamp (UTC)")
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Schema(hidden = true)
    @Version
    private Long versionLock;
}
