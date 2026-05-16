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
 * JPA entity for storing versioned prompt templates. Supports versioning for A/B testing and
 * rollback capabilities.
 */
@Schema(description = "Versioned prompt template for LLM interactions")
@Entity
@Table(name = "prompt_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptTemplate {

    @Schema(description = "Unique template ID")
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Schema(description = "Unique template name", example = "intent-classifier-v2")
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Schema(description = "Semantic version of this template", example = "2.1.0")
    @Column(nullable = false, length = 50)
    private String version;

    @Schema(description = "Human-readable description of what this template does")
    @Column(nullable = false, length = 200)
    private String description;

    @Schema(description = "LLM provider this template targets", example = "openai")
    @Column(nullable = false, length = 50)
    private String modelProvider;

    @Schema(description = "Model name this template is tuned for", example = "gpt-4-turbo")
    @Column(nullable = false, length = 50)
    private String modelName;

    @Schema(description = "System prompt text sent to the LLM")
    @Column(nullable = false, length = 5000)
    private String systemPrompt;

    @Schema(description = "Optional user prompt template with placeholders (e.g. {{message}})")
    @Column(length = 5000)
    private String userPromptTemplate;

    @Schema(
            description = "Sampling temperature (0.0 = deterministic, 1.0 = creative)",
            example = "0.7")
    @Column(nullable = false)
    @Builder.Default
    private Double temperature = 0.7;

    @Schema(description = "Maximum tokens to generate in the response", example = "2048")
    @Column(nullable = false)
    @Builder.Default
    private Integer maxTokens = 2048;

    @Schema(description = "Whether this template is available for use")
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Schema(description = "Selection priority — lower number wins", example = "100")
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
