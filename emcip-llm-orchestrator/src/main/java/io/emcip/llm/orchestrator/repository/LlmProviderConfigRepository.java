package io.emcip.llm.orchestrator.repository;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for LlmProviderConfig entity. */
@Repository
public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfig, UUID> {

    /** Returns the most-recently-updated active provider config. */
    Optional<LlmProviderConfig> findFirstByActiveTrueOrderByUpdatedAtDesc();

    /** Returns all configs — used for deactivating all before activating one. */
    List<LlmProviderConfig> findAll();
}
