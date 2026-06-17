package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Entity
@Table(name = "ke_resolution_flags")
@Data
public class ResolutionFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "candidate_label", nullable = false, length = 500)
    private String candidateLabel;

    @Column(name = "candidate_node_id", nullable = false)
    private UUID candidateNodeId;

    @Column(name = "similar_label", nullable = false, length = 500)
    private String similarLabel;

    @Column(name = "similar_node_id", nullable = false)
    private UUID similarNodeId;

    @Column(name = "concept_type", nullable = false, length = 100)
    private String conceptType;

    @Column(name = "similarity_score", nullable = false)
    private double similarityScore;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
