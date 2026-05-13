package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.AuditServiceClient;
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
public class AuditController {

    private final AuditServiceClient auditServiceClient;

    @GetMapping("/events")
    public Flux<JsonNode> getEvents(
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "eventType", required = false) String eventType) {
        return auditServiceClient.listEvents(size, eventType);
    }
}
