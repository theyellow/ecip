package io.emcip.llm.orchestrator.repository;

import io.emcip.llm.orchestrator.entity.PromptTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for PromptTemplate entity. Supports versioned prompt template management. */
@Repository
public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {

    /** Find active prompt template by name. */
    Optional<PromptTemplate> findByNameAndActiveTrue(String name);

    /** Find all active templates ordered by priority. */
    List<PromptTemplate> findByActiveTrueOrderByPriorityAsc();

    /** Find templates by model provider. */
    List<PromptTemplate> findByModelProviderAndActiveTrue(String modelProvider);

    /** Find templates by task type (stored in description or derived from name). */
    @Query(
            "SELECT p FROM PromptTemplate p WHERE p.active = true AND p.name LIKE %:taskType% ORDER"
                    + " BY p.priority ASC")
    List<PromptTemplate> findByTaskType(@Param("taskType") String taskType);

    /** Find specific version of a template. */
    Optional<PromptTemplate> findByNameAndVersion(String name, String version);

    /** Check if template name exists. */
    boolean existsByName(String name);
}
