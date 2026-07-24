package io.emcip.llm.orchestrator.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

/** JPA entity for LLM provider configuration. Stores LiteLLM proxy URL and credentials. */
@Schema(description = "LLM provider configuration pointing to a LiteLLM proxy instance")
@Entity
@Table(name = "llm_provider_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmProviderConfig {

    @Schema(description = "Unique provider config ID")
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Schema(description = "Display name for this provider config", example = "LiteLLM Local")
    @Column(nullable = false, unique = true, length = 255)
    private String name;

    @Schema(description = "Base URL of the LiteLLM proxy", example = "http://litellm:4000")
    @Column(nullable = false, length = 512)
    private String baseUrl;

    @Schema(
            description = "API key for the LiteLLM proxy (write-only, never returned in responses)",
            accessMode = Schema.AccessMode.WRITE_ONLY)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Convert(converter = LlmProviderApiKeyCipherConverter.class)
    @Column
    private String apiKey;

    @Schema(description = "Whether this provider is the active one used for LLM calls")
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = false;

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
