package io.emcip.policy.engine.repository;

import io.emcip.policy.engine.entity.PolicyRuleHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PolicyRuleHistoryRepository extends JpaRepository<PolicyRuleHistory, UUID> {

    List<PolicyRuleHistory> findByRuleIdOrderByEditedAtDesc(String ruleId);
}
