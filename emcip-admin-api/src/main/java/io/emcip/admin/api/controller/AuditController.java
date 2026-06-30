package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.AuditServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@Tag(name = "Audit", description = "Read audit events from the audit service")
public class AuditController {

    private final AuditServiceClient auditServiceClient;

    @Operation(summary = "List recent audit events, optionally filtered by type and time range")
    @GetMapping("/events")
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    public Mono<JsonNode> getEvents(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to) {
        return auditServiceClient.listEvents(page, Math.min(size, 200), eventType, from, to);
    }
}
