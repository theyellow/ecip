package io.emcip.admin.api.entity;

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

@Table("tenants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    @Id private UUID id;

    @Column("name")
    private String name;

    @Column("description")
    private String description;

    @Column("llm_model_override")
    private String llmModelOverride;

    @Column("created_at")
    private Instant createdAt;
}
