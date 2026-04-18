package io.emcip.policy.engine.repository;

import io.emcip.policy.engine.entity.PolicyRuleConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for PolicyRuleConfig entities.
 */
@Repository
public interface PolicyRuleConfigRepository extends JpaRepository<PolicyRuleConfig, String> {

    /**
     * Find all active rules ordered by priority.
     */
    List<PolicyRuleConfig> findByActiveTrueOrderByPriorityAsc();

    /**
     * Find rules by target intent.
     */
    List<PolicyRuleConfig> findByTargetIntentAndActiveTrueOrderByPriorityAsc(String targetIntent);

    /**
     * Find rules by action type.
     */
    List<PolicyRuleConfig> findByActionAndActiveTrue(String action);

    /**
     * Find rule by name.
     */
    Optional<PolicyRuleConfig> findByName(String name);

    /**
     * Find rules within confidence range.
     */
    @Query("SELECT pr FROM PolicyRuleConfig pr " +
           "WHERE pr.active = true " +
           "AND pr.minConfidence <= :confidence " +
           "AND (pr.maxConfidence IS NULL OR pr.maxConfidence >= :confidence) " +
           "ORDER BY pr.priority ASC")
    List<PolicyRuleConfig> findRulesForConfidence(@Param("confidence") Double confidence);

    /**
     * Activate a rule.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE PolicyRuleConfig pr SET pr.active = true WHERE pr.id = :id")
    int activate(@Param("id") String id);

    /**
     * Deactivate a rule.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE PolicyRuleConfig pr SET pr.active = false WHERE pr.id = :id")
    int deactivate(@Param("id") String id);

    /**
     * Update rule priority.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE PolicyRuleConfig pr SET pr.priority = :priority WHERE pr.id = :id")
    int updatePriority(@Param("id") String id, @Param("priority") Integer priority);

    /**
     * Count active rules by action type.
     */
    @Query("SELECT pr.action, COUNT(pr) FROM PolicyRuleConfig pr " +
           "WHERE pr.active = true GROUP BY pr.action")
    List<Object[]> countActiveByAction();
}
