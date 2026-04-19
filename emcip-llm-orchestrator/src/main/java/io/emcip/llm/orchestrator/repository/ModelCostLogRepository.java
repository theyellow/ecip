package io.emcip.llm.orchestrator.repository;

import io.emcip.llm.orchestrator.entity.ModelCostLog;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for ModelCostLog entity. Supports cost analysis and tracking (US-3.2.3). */
@Repository
public interface ModelCostLogRepository extends JpaRepository<ModelCostLog, UUID> {

    /** Find cost log by request ID. */
    Optional<ModelCostLog> findByRequestId(String requestId);

    /** Find all cost logs for a source event. */
    List<ModelCostLog> findBySourceEventId(String sourceEventId);

    /** Find cost logs by conversation ID. */
    List<ModelCostLog> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    /** Calculate total cost for a time period. */
    @Query(
            "SELECT SUM(m.totalCostUsd) FROM ModelCostLog m WHERE m.createdAt BETWEEN :start AND"
                    + " :end AND m.status = 'SUCCESS'")
    Double calculateTotalCostForPeriod(@Param("start") Instant start, @Param("end") Instant end);

    /** Calculate total tokens for a model in a time period. */
    @Query(
            "SELECT SUM(m.totalTokens) FROM ModelCostLog m WHERE m.modelName = :modelName AND"
                    + " m.createdAt BETWEEN :start AND :end")
    Long calculateTotalTokensForModel(
            @Param("modelName") String modelName,
            @Param("start") Instant start,
            @Param("end") Instant end);

    /** Find cost logs by model name ordered by timestamp. */
    List<ModelCostLog> findByModelNameOrderByCreatedAtDesc(String modelName);

    /** Find cost logs by status. */
    List<ModelCostLog> findByStatusOrderByCreatedAtDesc(String status);

    /** Find recent cost logs for a prompt template. */
    List<ModelCostLog> findTop10ByPromptTemplateNameOrderByCreatedAtDesc(String promptTemplateName);
}
