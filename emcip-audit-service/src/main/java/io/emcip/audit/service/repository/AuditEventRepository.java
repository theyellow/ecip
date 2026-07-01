package io.emcip.audit.service.repository;

import io.emcip.audit.service.entity.AuditEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface AuditEventRepository extends R2dbcRepository<AuditEventEntity, Long> {

    // --- existing un-paginated methods (kept for internal use) ---
    Flux<AuditEventEntity> findByEventType(String eventType);

    Flux<AuditEventEntity> findByCreatedAtBetween(Instant from, Instant to);

    Flux<AuditEventEntity> findByEventTypeAndCreatedAtBetween(
            String eventType, Instant from, Instant to);

    Mono<AuditEventEntity> findByEventId(String eventId);

    Flux<AuditEventEntity> findByCorrelationId(String correlationId);

    Flux<AuditEventEntity> findByEventTypeAndTenantId(String eventType, UUID tenantId);

    Flux<AuditEventEntity> findByCreatedAtBetweenAndTenantId(
            Instant from, Instant to, UUID tenantId);

    Flux<AuditEventEntity> findByEventTypeAndCreatedAtBetweenAndTenantId(
            String eventType, Instant from, Instant to, UUID tenantId);

    Mono<AuditEventEntity> findByEventIdAndTenantId(String eventId, UUID tenantId);

    // --- paginated variants ---
    Flux<AuditEventEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(
            Instant from, Instant to, Pageable pageable);

    Mono<Long> countByCreatedAtBetween(Instant from, Instant to);

    Flux<AuditEventEntity> findByEventTypeAndCreatedAtBetweenOrderByCreatedAtDesc(
            String eventType, Instant from, Instant to, Pageable pageable);

    Mono<Long> countByEventTypeAndCreatedAtBetween(String eventType, Instant from, Instant to);

    Flux<AuditEventEntity> findByCreatedAtBetweenAndTenantIdOrderByCreatedAtDesc(
            Instant from, Instant to, UUID tenantId, Pageable pageable);

    Mono<Long> countByCreatedAtBetweenAndTenantId(Instant from, Instant to, UUID tenantId);

    Flux<AuditEventEntity> findByEventTypeAndCreatedAtBetweenAndTenantIdOrderByCreatedAtDesc(
            String eventType, Instant from, Instant to, UUID tenantId, Pageable pageable);

    Mono<Long> countByEventTypeAndCreatedAtBetweenAndTenantId(
            String eventType, Instant from, Instant to, UUID tenantId);

    // --- tamper-resistance chain queries ---

    @Query("SELECT * FROM audit_events ORDER BY id DESC LIMIT 1")
    Mono<AuditEventEntity> findTopByOrderByIdDesc();

    @Query("SELECT * FROM audit_events WHERE created_at < :cutoff ORDER BY id ASC LIMIT 1")
    Mono<AuditEventEntity> findOldestBeforeCutoff(Instant cutoff);

    @Modifying
    @Query("DELETE FROM audit_events WHERE created_at < :cutoff")
    Mono<Long> deleteByCreatedAtBefore(Instant cutoff);

    @Query("SELECT * FROM audit_events ORDER BY id DESC LIMIT :limit")
    Flux<AuditEventEntity> findTopNByOrderByIdDesc(int limit);
}
