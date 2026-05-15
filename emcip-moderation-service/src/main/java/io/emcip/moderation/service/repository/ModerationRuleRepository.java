package io.emcip.moderation.service.repository;

import io.emcip.moderation.service.entity.ModerationRule;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ModerationRuleRepository extends ReactiveCrudRepository<ModerationRule, Long> {

    Flux<ModerationRule> findByEnabledTrue();

    @Query("SELECT * FROM moderation_rules ORDER BY rule_type ASC, name ASC")
    Flux<ModerationRule> findAllOrdered();
}
