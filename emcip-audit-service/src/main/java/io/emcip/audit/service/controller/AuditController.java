package io.emcip.audit.service.controller;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.service.AuditService;
import io.emcip.common.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/audit")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Audit Events", description = "Query audit events and pipeline summaries")
public class AuditController {

    private final AuditService auditService;

    /**
     * Query audit events with optional filtering by event type and date range.
     *
     * @param eventType optional event type filter
     * @param from optional ISO-8601 start timestamp; defaults to 24 hours ago
     * @param to optional ISO-8601 end timestamp; defaults to now
     * @param page zero-based page index (default 0)
     * @param size page size, capped at 200 (default 50)
     */
    @Operation(summary = "List audit events filtered by type and time range")
    @GetMapping("/events")
    public Mono<PageResponse<AuditEventEntity>> getEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String correlationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        if (correlationId != null && !correlationId.isBlank()) {
            return auditService
                    .findByCorrelationId(correlationId)
                    .collectList()
                    .map(items -> new PageResponse<>(items, (long) items.size(), 0, items.size()));
        }

        Instant fromInstant =
                from != null ? Instant.parse(from) : Instant.now().minus(24, ChronoUnit.HOURS);
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        int effectiveSize = Math.min(size, 200);

        return auditService.findPage(fromInstant, toInstant, page, effectiveSize, eventType);
    }

    /**
     * Retrieve a single audit event by its idempotency key.
     *
     * @param eventId the unique event identifier
     */
    @Operation(summary = "Get a single audit event by event ID")
    @GetMapping("/events/{eventId}")
    public Mono<ResponseEntity<AuditEventEntity>> getEvent(@PathVariable String eventId) {
        return auditService
                .findByEventId(eventId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Return a summary of event counts grouped by event type for the given time window.
     *
     * @param from optional ISO-8601 start timestamp; defaults to 24 hours ago
     * @param to optional ISO-8601 end timestamp; defaults to now
     */
    @Operation(summary = "Get event-type counts for a time range")
    @GetMapping("/summary")
    public Mono<Map<String, Long>> getSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        Instant fromInstant =
                from != null ? Instant.parse(from) : Instant.now().minus(24, ChronoUnit.HOURS);
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();

        return auditService
                .findByDateRange(fromInstant, toInstant)
                .collectMultimap(AuditEventEntity::getEventType)
                .map(
                        multimap ->
                                multimap.entrySet().stream()
                                        .collect(
                                                java.util.stream.Collectors.toMap(
                                                        Map.Entry::getKey,
                                                        e -> (long) e.getValue().size())));
    }
}
