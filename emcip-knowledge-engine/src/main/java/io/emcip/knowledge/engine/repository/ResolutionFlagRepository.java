package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ResolutionFlagRepository extends JpaRepository<ResolutionFlag, UUID> {}
