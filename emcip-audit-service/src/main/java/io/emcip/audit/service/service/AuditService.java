package io.emcip.audit.service.service;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.common.pagination.PageResponse;
import io.emcip.common.tenant.ReactorTenantContext;
import io.r2dbc.postgresql.codec.Json;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public Mono<AuditEventEntity> save(AuditEventEntity entity) {
        return repository
                .save(entity)
                .doOnSuccess(
                        saved ->
                                log.debug(
                                        "Saved audit event: id={}, type={}",
                                        saved.getId(),
                                        saved.getEventType()))
                .doOnError(
                        e ->
                                log.error(
                                        "Failed to save audit event: eventId={}",
                                        entity.getEventId(),
                                        e));
    }

    public Flux<AuditEventEntity> findByEventType(String eventType) {
        return Flux.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByEventTypeAndTenantId(
                                eventType, UUID.fromString(tenantId));
                    }
                    return repository.findByEventType(eventType);
                });
    }

    public Flux<AuditEventEntity> findByDateRange(Instant from, Instant to) {
        return Flux.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByCreatedAtBetweenAndTenantId(
                                from, to, UUID.fromString(tenantId));
                    }
                    return repository.findByCreatedAtBetween(from, to);
                });
    }

    public Flux<AuditEventEntity> findByEventTypeAndDateRange(
            String eventType, Instant from, Instant to) {
        return Flux.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByEventTypeAndCreatedAtBetweenAndTenantId(
                                eventType, from, to, UUID.fromString(tenantId));
                    }
                    return repository.findByEventTypeAndCreatedAtBetween(eventType, from, to);
                });
    }

    public Flux<AuditEventEntity> findByCorrelationId(String correlationId) {
        return repository.findByCorrelationId(correlationId);
    }

    public Mono<AuditEventEntity> findByEventId(String eventId) {
        return Mono.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByEventIdAndTenantId(
                                eventId, UUID.fromString(tenantId));
                    }
                    return repository.findByEventId(eventId);
                });
    }

    public Mono<PageResponse<AuditEventEntity>> findPage(
            Instant from, Instant to, int page, int size, String eventType) {
        Pageable pageable = PageRequest.of(page, size);
        return Mono.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        UUID tid = UUID.fromString(tenantId);
                        if (eventType != null && !eventType.isBlank()) {
                            Flux<AuditEventEntity> items =
                                    repository
                                            .findByEventTypeAndCreatedAtBetweenAndTenantIdOrderByCreatedAtDesc(
                                                    eventType, from, to, tid, pageable);
                            Mono<Long> count =
                                    repository.countByEventTypeAndCreatedAtBetweenAndTenantId(
                                            eventType, from, to, tid);
                            return Mono.zip(items.collectList(), count)
                                    .map(t -> new PageResponse<>(t.getT1(), t.getT2(), page, size));
                        }
                        Flux<AuditEventEntity> items =
                                repository.findByCreatedAtBetweenAndTenantIdOrderByCreatedAtDesc(
                                        from, to, tid, pageable);
                        Mono<Long> count =
                                repository.countByCreatedAtBetweenAndTenantId(from, to, tid);
                        return Mono.zip(items.collectList(), count)
                                .map(t -> new PageResponse<>(t.getT1(), t.getT2(), page, size));
                    }
                    if (eventType != null && !eventType.isBlank()) {
                        Flux<AuditEventEntity> items =
                                repository.findByEventTypeAndCreatedAtBetweenOrderByCreatedAtDesc(
                                        eventType, from, to, pageable);
                        Mono<Long> count =
                                repository.countByEventTypeAndCreatedAtBetween(eventType, from, to);
                        return Mono.zip(items.collectList(), count)
                                .map(t -> new PageResponse<>(t.getT1(), t.getT2(), page, size));
                    }
                    Flux<AuditEventEntity> items =
                            repository.findByCreatedAtBetweenOrderByCreatedAtDesc(
                                    from, to, pageable);
                    Mono<Long> count = repository.countByCreatedAtBetween(from, to);
                    return Mono.zip(items.collectList(), count)
                            .map(t -> new PageResponse<>(t.getT1(), t.getT2(), page, size));
                });
    }

    /**
     * Serialize a map of event fields to a {@link Json} value for the JSONB details column.
     *
     * @param fields key/value pairs to serialize
     * @return Json wrapping the serialized JSON, or null if serialization fails
     */
    public Json serializeDetails(Map<String, Object> fields) {
        try {
            return Json.of(objectMapper.writeValueAsString(fields));
        } catch (JacksonException e) {
            log.warn("Failed to serialize details map: {}", e.getMessage());
            return null;
        }
    }
}
