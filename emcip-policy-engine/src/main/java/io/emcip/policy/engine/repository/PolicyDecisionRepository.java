package io.emcip.policy.engine.repository;

import io.emcip.policy.engine.entity.PolicyDecision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for PolicyDecision entities.
 */
@Repository
public interface PolicyDecisionRepository extends JpaRepository<PolicyDecision, String> {

    /**
     * Find decisions by source event ID.
     */
    List<PolicyDecision> findBySourceEventId(String sourceEventId);

    /**
     * Find decisions by policy ID.
     */
    List<PolicyDecision> findByPolicyId(String policyId);

    /**
     * Find decisions by decision type.
     */
    List<PolicyDecision> findByDecision(String decision);

    /**
     * Find decisions within a time range.
     */
    List<PolicyDecision> findByTimestampBetween(Instant start, Instant end);

    /**
     * Find the most recent decision for a source event.
     */
    Optional<PolicyDecision> findTopBySourceEventIdOrderByTimestampDesc(String sourceEventId);

    /**
     * Count decisions by type within a time range.
     */
    @Query("SELECT pd.decision, COUNT(pd) FROM PolicyDecision pd " +
           "WHERE pd.timestamp BETWEEN :start AND :end " +
           "GROUP BY pd.decision")
    List<Object[]> countByDecisionTypeInRange(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * Find all decisions with pagination.
     */
    Page<PolicyDecision> findAllByOrderByTimestampDesc(Pageable pageable);

    /**
     * Find decisions for specific original intent.
     */
    List<PolicyDecision> findByOriginalIntent(String originalIntent);

    /**
     * Find decisions with confidence above threshold.
     */
    List<PolicyDecision> findByConfidenceGreaterThan(Double threshold);
}
