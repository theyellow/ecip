package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.AuditEvent;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AuditEventRepository extends ReactiveCrudRepository<AuditEvent, Long> {

    @Query("SELECT * FROM audit_events ORDER BY created_at DESC LIMIT :limit")
    Flux<AuditEvent> findRecent(@Param("limit") int limit);

    @Query(
            "SELECT * FROM audit_events WHERE event_type = :eventType ORDER BY created_at DESC"
                    + " LIMIT :limit")
    Flux<AuditEvent> findRecentByType(
            @Param("eventType") String eventType, @Param("limit") int limit);
}
