package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ke_ingestion_jobs")
@Getter
@Setter
@NoArgsConstructor
public class IngestionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = true)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceType sourceType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IngestionStatus status;

    @Column(nullable = true)
    private Integer chunkCount;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public enum IngestionStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        FLAGGED_INJECTION_RISK
    }

    public enum SourceType {
        URL,
        FILE_UPLOAD
    }
}
