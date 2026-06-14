package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ConceptType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConceptTypeRepository extends JpaRepository<ConceptType, UUID> {
    Optional<ConceptType> findByName(String name);

    boolean existsByName(String name);
}
