package io.emcip.audit.service.service;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        return repository.findByEventType(eventType);
    }

    public Flux<AuditEventEntity> findByDateRange(Instant from, Instant to) {
        return repository.findByCreatedAtBetween(from, to);
    }

    public Flux<AuditEventEntity> findByEventTypeAndDateRange(
            String eventType, Instant from, Instant to) {
        return repository.findByEventTypeAndCreatedAtBetween(eventType, from, to);
    }

    public Mono<AuditEventEntity> findByEventId(String eventId) {
        return repository.findByEventId(eventId);
    }

    /**
     * Serialize a map of event fields to a JSON string suitable for the JSONB details column.
     *
     * @param fields key/value pairs to serialize
     * @return JSON string, or null if serialization fails
     */
    public String serializeDetails(Map<String, Object> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JacksonException e) {
            log.warn("Failed to serialize details map: {}", e.getMessage());
            return null;
        }
    }
}
