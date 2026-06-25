package io.emcip.policy.engine.repository;

import io.emcip.policy.engine.entity.PolicyDecision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Repository for PolicyDecision entities. */
@Repository
public interface PolicyDecisionRepository extends JpaRepository<PolicyDecision, String> {

    /** Find decisions by source event ID. */
    List<PolicyDecision> findBySourceEventId(String sourceEventId);

    /** Find decisions by policy ID. */
    List<PolicyDecision> findByPolicyId(String policyId);

    /** Find decisions by decision type. */
    List<PolicyDecision> findByDecision(String decision);

    /** Find decisions matching a decision value with pagination. */
    Page<PolicyDecision> findByDecision(String decision, Pageable pageable);

    /** Find decisions within a time range. */
    List<PolicyDecision> findByTimestampBetween(Instant start, Instant end);

    /** Find the most recent decision for a source event. */
    Optional<PolicyDecision> findTopBySourceEventIdOrderByTimestampDesc(String sourceEventId);

    /** Count decisions by type within a time range. */
    @Query(
            "SELECT pd.decision, COUNT(pd) FROM PolicyDecision pd "
                    + "WHERE pd.timestamp BETWEEN :start AND :end "
                    + "GROUP BY pd.decision")
    List<Object[]> countByDecisionTypeInRange(
            @Param("start") Instant start, @Param("end") Instant end);

    /** Find all decisions with pagination. */
    Page<PolicyDecision> findAllByOrderByTimestampDesc(Pageable pageable);

    /** Find decisions for specific original intent. */
    List<PolicyDecision> findByOriginalIntent(String originalIntent);

    /** Find decisions with confidence above threshold. */
    List<PolicyDecision> findByConfidenceGreaterThan(Double threshold);

    /** Find top N decisions whose decision is not the given value, ordered by timestamp desc. */
    @Query(
            "SELECT p FROM PolicyDecision p WHERE p.decision != :decision ORDER BY p.timestamp"
                    + " DESC LIMIT :limit")
    List<PolicyDecision> findTopByDecisionNotOrderByTimestampDesc(
            @Param("decision") String decision, @Param("limit") int limit);

    /** Find top N decisions matching the given decision value, ordered by timestamp desc. */
    @Query(
            "SELECT p FROM PolicyDecision p WHERE p.decision = :decision ORDER BY p.timestamp"
                    + " DESC LIMIT :limit")
    List<PolicyDecision> findByDecisionOrderByTimestampDesc(
            @Param("decision") String decision, @Param("limit") int limit);

    /** Multi-field filtered query with optional predicates. Null values disable that filter. */
    @Query(
            nativeQuery = true,
            value =
                    "SELECT * FROM policy_decisions pd WHERE (CAST(:decision AS text) IS NULL OR"
                        + " pd.decision = :decision) AND (CAST(:intent AS text) IS NULL OR"
                        + " pd.original_intent = :intent) AND (CAST(:fromTs AS timestamptz) IS NULL"
                        + " OR pd.timestamp >= CAST(:fromTs AS timestamptz)) AND (CAST(:toTs AS"
                        + " timestamptz) IS NULL OR pd.timestamp <= CAST(:toTs AS timestamptz)) AND"
                        + " (CAST(:minConfidence AS float8) IS NULL OR pd.confidence >="
                        + " CAST(:minConfidence AS float8)) ORDER BY pd.timestamp DESC",
            countQuery =
                    "SELECT COUNT(*) FROM policy_decisions pd WHERE (CAST(:decision AS text) IS"
                        + " NULL OR pd.decision = :decision) AND (CAST(:intent AS text) IS NULL OR"
                        + " pd.original_intent = :intent) AND (CAST(:fromTs AS timestamptz) IS NULL"
                        + " OR pd.timestamp >= CAST(:fromTs AS timestamptz)) AND (CAST(:toTs AS"
                        + " timestamptz) IS NULL OR pd.timestamp <= CAST(:toTs AS timestamptz)) AND"
                        + " (CAST(:minConfidence AS float8) IS NULL OR pd.confidence >="
                        + " CAST(:minConfidence AS float8))")
    Page<PolicyDecision> findByFilters(
            @Param("decision") String decision,
            @Param("intent") String intent,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs,
            @Param("minConfidence") Double minConfidence,
            Pageable pageable);

    /** Update the signal status of a decision by id. */
    @Modifying
    @Transactional
    @Query("UPDATE PolicyDecision p SET p.signalStatus = :status WHERE p.id = :id")
    int updateSignalStatus(@Param("id") String id, @Param("status") String status);

    /** Count BLOCK/FLAG decisions for a given senderId after the given timestamp. */
    @Query(
            nativeQuery = true,
            value =
                    "SELECT COUNT(*) FROM policy_decisions "
                            + "WHERE metadata->>'senderId' = :senderId "
                            + "AND decision IN ('BLOCK', 'FLAG') "
                            + "AND timestamp > :since")
    int countBlockedBySenderSince(
            @Param("senderId") String senderId, @Param("since") Instant since);
}
