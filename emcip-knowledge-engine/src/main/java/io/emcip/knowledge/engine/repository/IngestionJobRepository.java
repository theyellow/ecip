package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.entity.IngestionJob.IngestionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

    Page<IngestionJob> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<IngestionJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<IngestionJob> findAllByStatus(IngestionJob.IngestionStatus status);

    @Query(
            """
            SELECT j FROM IngestionJob j
            WHERE j.sourceRef = :sourceRef
              AND j.status = :status
              AND (j.tenantId = :tenantId OR (j.tenantId IS NULL AND :tenantId IS NULL))
            ORDER BY j.createdAt DESC
            LIMIT 1
            """)
    Optional<IngestionJob> findCompletedBySourceRefAndTenant(
            @Param("sourceRef") String sourceRef,
            @Param("tenantId") UUID tenantId,
            @Param("status") IngestionStatus status);

    @Query(
            """
            SELECT j FROM IngestionJob j
            WHERE j.contentHash = :contentHash
              AND j.status = :status
              AND j.id <> :excludeJobId
              AND (j.tenantId = :tenantId OR (j.tenantId IS NULL AND :tenantId IS NULL))
            ORDER BY j.createdAt DESC
            LIMIT 1
            """)
    Optional<IngestionJob> findCompletedByContentHashAndTenant(
            @Param("contentHash") String contentHash,
            @Param("tenantId") UUID tenantId,
            @Param("status") IngestionStatus status,
            @Param("excludeJobId") UUID excludeJobId);
}
