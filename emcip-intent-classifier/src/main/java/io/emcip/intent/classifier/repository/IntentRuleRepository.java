package io.emcip.intent.classifier.repository;

import io.emcip.intent.classifier.entity.IntentRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntentRuleRepository extends JpaRepository<IntentRule, String> {
    List<IntentRule> findByTenantIdAndActiveTrueOrderByPriorityAsc(UUID tenantId);

    List<IntentRule> findByTenantIdIsNullAndActiveTrueOrderByPriorityAsc();

    List<IntentRule> findByTenantIdOrderByPriorityAsc(UUID tenantId);

    List<IntentRule> findByTenantIdIsNullOrderByPriorityAsc();
}
