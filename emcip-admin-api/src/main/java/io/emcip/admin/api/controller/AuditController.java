package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.AuditServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@Tag(name = "Audit", description = "Read audit events from the audit service")
public class AuditController {

    private final AuditServiceClient auditServiceClient;

    @Operation(summary = "List recent audit events, optionally filtered by type")
    @GetMapping("/events")
    public Flux<JsonNode> getEvents(
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "eventType", required = false) String eventType) {
        return auditServiceClient.listEvents(size, eventType);
    }
}
