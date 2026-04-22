package io.emcip.llm.orchestrator.repository;

import io.emcip.llm.orchestrator.entity.ModelConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for ModelConfig entity. Supports model routing configuration. */
@Repository
public interface ModelConfigRepository extends JpaRepository<ModelConfig, UUID> {

    /** Find active model configuration by key. */
    Optional<ModelConfig> findByModelKeyAndActiveTrue(String modelKey);

    /** Find all active models ordered by priority. */
    List<ModelConfig> findByActiveTrueOrderByPriorityAsc();

    /** Find models by task type. */
    List<ModelConfig> findByTaskTypeAndActiveTrueOrderByPriorityAsc(String taskType);

    /** Find models by provider. */
    List<ModelConfig> findByProviderAndActiveTrue(String provider);

    /** Check if model key exists. */
    boolean existsByModelKey(String modelKey);
}
