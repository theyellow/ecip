package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ke_research_evidence")
@Getter
@Setter
@NoArgsConstructor
public class ResearchEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ResearchSession session;

    @Column(name = "sub_question", nullable = false, columnDefinition = "TEXT")
    private String subQuestion;

    @Enumerated(EnumType.STRING)
    @Column(name = "query_strategy", nullable = false, length = 50)
    private QueryStrategy queryStrategy;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String finding;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "source_ref", nullable = false, length = 1000)
    private String sourceRef;

    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore;

    @Column(nullable = false)
    private int iteration;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
