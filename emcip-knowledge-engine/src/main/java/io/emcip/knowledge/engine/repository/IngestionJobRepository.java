package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.IngestionJob;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

    Page<IngestionJob> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<IngestionJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<IngestionJob> findAllByStatus(IngestionJob.IngestionStatus status);
}
