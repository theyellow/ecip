package io.emcip.moderation.service.repository;

import io.emcip.moderation.service.entity.ModerationRule;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ModerationRuleRepository extends ReactiveCrudRepository<ModerationRule, Long> {

    Flux<ModerationRule> findByEnabledTrue();

    @Query("SELECT * FROM moderation_rules ORDER BY rule_type ASC, name ASC")
    Flux<ModerationRule> findAllOrdered();

    Flux<ModerationRule> findByEnabledTrueAndTenantId(UUID tenantId);

    @Query(
            "SELECT * FROM moderation_rules WHERE tenant_id = :tenantId ORDER BY rule_type ASC,"
                    + " name ASC")
    Flux<ModerationRule> findAllOrderedByTenantId(UUID tenantId);

    Mono<ModerationRule> findByIdAndTenantId(Long id, UUID tenantId);
}
