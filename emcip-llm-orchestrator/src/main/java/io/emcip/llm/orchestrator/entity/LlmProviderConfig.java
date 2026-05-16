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

/** JPA entity for LLM provider configuration. Stores LiteLLM proxy URL and credentials. */
@Entity
@Table(name = "llm_provider_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String name;

    @Column(nullable = false, length = 512)
    private String baseUrl;

    @Column(length = 512)
    private String apiKey;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version private Long versionLock;
}
