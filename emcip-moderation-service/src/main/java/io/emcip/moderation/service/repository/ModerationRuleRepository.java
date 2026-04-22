package io.emcip.moderation.service.repository;

import io.emcip.moderation.service.entity.ModerationRule;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface ModerationRuleRepository extends ReactiveCrudRepository<ModerationRule, Long> {

    Flux<ModerationRule> findByEnabledTrue();
}
