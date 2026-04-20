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

/**
 * JPA entity for tracking AI model call costs. Supports cost analysis and budget monitoring
 * (US-3.2.3).
 */
@Entity
@Table(name = "model_cost_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
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

    @Column(nullable = false)
    private Double inputCostUsd;

    @Column(nullable = false)
    private Double outputCostUsd;

    @Column(nullable = false)
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
}
