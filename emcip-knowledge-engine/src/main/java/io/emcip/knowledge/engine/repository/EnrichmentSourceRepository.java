package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.EnrichmentSource;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrichmentSourceRepository extends JpaRepository<EnrichmentSource, UUID> {

    List<EnrichmentSource> findAllByEnabledTrue();

    List<EnrichmentSource> findAllByEnabledTrueAndTenantIdIsNull();

    List<EnrichmentSource> findAllByEnabledTrueAndTenantId(UUID tenantId);
}
