package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.AuditEventResponse;
import io.emcip.admin.api.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditEventRepository auditEventRepository;

    @GetMapping("/events")
    public Flux<AuditEventResponse> getEvents(
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "eventType", required = false) String eventType) {
        Flux<io.emcip.admin.api.entity.AuditEvent> events =
                (eventType != null && !eventType.isBlank())
                        ? auditEventRepository.findRecentByType(eventType, size)
                        : auditEventRepository.findRecent(size);
        return events.map(
                e ->
                        new AuditEventResponse(
                                e.getEventId(),
                                e.getEventType(),
                                e.getSourceService(),
                                e.getAction(),
                                e.getActorType(),
                                e.getActorId(),
                                e.getResourceId(),
                                e.getOutcome(),
                                e.getDetails(),
                                e.getCreatedAt()));
    }
}
