package io.emcip.llm.orchestrator.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA entity for storing versioned prompt templates. Supports versioning for A/B testing and
 * rollback capabilities.
 */
@Schema(description = "Versioned prompt template for LLM interactions")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
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

    @Schema(description = "Tenant this template belongs to")
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Schema(description = "Unique template name", example = "intent-classifier-v2")
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Schema(description = "Semantic version of this template", example = "2.1.0")
    @Column(nullable = false, length = 50)
    private String version;

    @Schema(description = "Human-readable description of what this template does")
    @Column(nullable = false, length = 200)
    private String description;

    @Schema(description = "Whether this is a built-in system template (cannot be deleted/renamed)")
    @Column(nullable = false)
    @Builder.Default
    private Boolean system = false;

    @Schema(description = "Model configuration this template uses for LLM calls")
    @ManyToOne
    @JoinColumn(name = "model_config_id")
    private ModelConfig modelConfig;

    @Schema(description = "System prompt text sent to the LLM")
    @Column(nullable = false, length = 5000)
    private String systemPrompt;

    @Schema(description = "Optional user prompt template with placeholders (e.g. {{message}})")
    @Column(length = 5000)
    private String userPromptTemplate;

    @Schema(
            description = "Sampling temperature (0.0 = deterministic, 1.0 = creative)",
            example = "0.7")
    @Column
    private Double temperature;

    @Schema(description = "Maximum tokens to generate in the response", example = "8192")
    @Column(nullable = false)
    @Builder.Default
    private Integer maxTokens = 8192;

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
