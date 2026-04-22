package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.PolicyRule;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface PolicyRuleRepository extends ReactiveCrudRepository<PolicyRule, String> {

    @Query(
            "SELECT * FROM policy_rules WHERE active = true AND (effective_to IS NULL OR"
                    + " effective_to > NOW()) ORDER BY priority ASC")
    Flux<PolicyRule> findActiveRules();

    @Query("SELECT * FROM policy_rules WHERE name = :name ORDER BY rule_version ASC")
    Flux<PolicyRule> findHistoryByName(String name);
}
