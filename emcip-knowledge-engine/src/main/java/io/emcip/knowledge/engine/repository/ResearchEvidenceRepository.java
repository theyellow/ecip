package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ResearchEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchEvidenceRepository extends JpaRepository<ResearchEvidence, UUID> {

    List<ResearchEvidence> findBySessionIdOrderByIterationAscCreatedAtAsc(UUID sessionId);
}
