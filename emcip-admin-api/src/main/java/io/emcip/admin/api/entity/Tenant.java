package io.emcip.admin.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Schema(description = "Tenant configuration")
@Table("tenants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    @Schema(description = "Unique tenant ID (UUID)")
    @Id
    private UUID id;

    @Schema(description = "Tenant display name", example = "Acme Corp")
    @Column("name")
    private String name;

    @Schema(description = "Optional description of this tenant")
    @Column("description")
    private String description;

    @Schema(
            description = "Override the default LLM model key for this tenant",
            example = "gpt4-turbo")
    @Column("llm_model_override")
    private String llmModelOverride;

    @Schema(description = "Creation timestamp (UTC)")
    @Column("created_at")
    private Instant createdAt;
}
