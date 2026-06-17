package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ResolutionFlagRepository extends JpaRepository<ResolutionFlag, UUID> {

    @Query(
            """
            SELECT f FROM ResolutionFlag f
            WHERE (:status IS NULL OR f.status = :status)
              AND (:conceptType IS NULL OR f.conceptType = :conceptType)
              AND (:tenantId IS NULL OR f.tenantId = :tenantId)
            ORDER BY f.createdAt DESC
            """)
    Page<ResolutionFlag> findFiltered(
            @Param("status") String status,
            @Param("conceptType") String conceptType,
            @Param("tenantId") UUID tenantId,
            Pageable pageable);
}
