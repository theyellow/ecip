package io.emcip.audit.service.repository;

import io.emcip.audit.service.entity.AuditEventEntity;
import java.time.Instant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface AuditEventRepository extends ReactiveCrudRepository<AuditEventEntity, Long> {

    Flux<AuditEventEntity> findByEventType(String eventType);

    Flux<AuditEventEntity> findByCreatedAtBetween(Instant from, Instant to);

    Flux<AuditEventEntity> findByEventTypeAndCreatedAtBetween(
            String eventType, Instant from, Instant to);

    Mono<AuditEventEntity> findByEventId(String eventId);
}
