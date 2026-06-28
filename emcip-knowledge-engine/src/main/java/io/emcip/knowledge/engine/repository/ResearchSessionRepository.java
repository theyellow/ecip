package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchSessionRepository extends JpaRepository<ResearchSession, UUID> {

    List<ResearchSession> findAllByOrderByCreatedAtDesc();

    List<ResearchSession> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<ResearchSession> findByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, ResearchStatus status);
}
