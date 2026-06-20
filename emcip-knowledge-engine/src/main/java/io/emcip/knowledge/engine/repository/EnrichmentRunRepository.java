package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.EnrichmentRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrichmentRunRepository extends JpaRepository<EnrichmentRun, UUID> {

    List<EnrichmentRun> findBySourceIdOrderByStartedAtDesc(UUID sourceId);
}
