package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.RelationshipType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RelationshipTypeRepository extends JpaRepository<RelationshipType, UUID> {
    Optional<RelationshipType> findByName(String name);

    boolean existsByName(String name);
}
