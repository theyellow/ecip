package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.EntityAlias;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EntityAliasRepository extends JpaRepository<EntityAlias, UUID> {
    Optional<EntityAlias> findByConceptTypeAndAlias(String conceptType, String alias);

    Optional<EntityAlias> findByConceptTypeAndAliasAndTenantId(
            String conceptType, String alias, UUID tenantId);
}
