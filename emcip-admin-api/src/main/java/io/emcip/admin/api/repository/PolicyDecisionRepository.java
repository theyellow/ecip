package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.PolicyDecision;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PolicyDecisionRepository extends ReactiveCrudRepository<PolicyDecision, String> {

    @Query(
            "SELECT * FROM policy_decisions WHERE decision != 'ALLOW' ORDER BY timestamp DESC LIMIT"
                    + " :limit")
    Flux<PolicyDecision> findFlags(@Param("limit") int limit);

    @Query(
            "SELECT * FROM policy_decisions WHERE decision = :decision ORDER BY timestamp DESC"
                    + " LIMIT :limit")
    Flux<PolicyDecision> findByDecision(
            @Param("decision") String decision, @Param("limit") int limit);

    @Modifying
    @Query("UPDATE policy_decisions SET signal_status = :status WHERE id = :id")
    Mono<Void> updateSignalStatus(@Param("id") String id, @Param("status") String status);
}
