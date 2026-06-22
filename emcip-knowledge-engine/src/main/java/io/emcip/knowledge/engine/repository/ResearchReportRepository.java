package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ResearchReport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchReportRepository extends JpaRepository<ResearchReport, UUID> {

    Optional<ResearchReport> findBySessionId(UUID sessionId);
}
