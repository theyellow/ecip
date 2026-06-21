package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ke_research_sessions")
@Getter
@Setter
@NoArgsConstructor
public class ResearchSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResearchStatus status = ResearchStatus.CREATED;

    @Column(name = "max_iterations", nullable = false)
    private int maxIterations = 10;

    @Column(name = "max_llm_calls", nullable = false)
    private int maxLlmCalls = 20;

    @Column(name = "cost_limit_usd", nullable = false)
    private double costLimitUsd = 1.00;

    @Column(name = "iterations_used", nullable = false)
    private int iterationsUsed = 0;

    @Column(name = "llm_calls_used", nullable = false)
    private int llmCallsUsed = 0;

    @Column(name = "cost_used_usd", nullable = false)
    private double costUsedUsd = 0.0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version_lock", nullable = false)
    private Long versionLock;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isWithinLimits() {
        return iterationsUsed < maxIterations
                && llmCallsUsed < maxLlmCalls
                && costUsedUsd < costLimitUsd;
    }

    public void incrementLlmCalls(int n) {
        this.llmCallsUsed += n;
    }

    public void incrementIterations(int n) {
        this.iterationsUsed += n;
    }
}
