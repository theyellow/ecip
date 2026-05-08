package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.ModerationRule;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ModerationRuleRepository extends ReactiveCrudRepository<ModerationRule, Long> {

    @Query("SELECT * FROM moderation_rules ORDER BY rule_type ASC, name ASC")
    Flux<ModerationRule> findAllOrdered();
}
