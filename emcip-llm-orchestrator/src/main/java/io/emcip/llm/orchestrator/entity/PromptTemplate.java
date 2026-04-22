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
@Entity
@Table(name = "prompt_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String version;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, length = 50)
    private String modelProvider;

    @Column(nullable = false, length = 50)
    private String modelName;

    @Column(nullable = false, length = 5000)
    private String systemPrompt;

    @Column(length = 5000)
    private String userPromptTemplate;

    @Column(nullable = false)
    @Builder.Default
    private Double temperature = 0.7;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxTokens = 2048;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 100;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version private Long versionLock;
}
