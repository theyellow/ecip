package io.emcip.admin.api.dto;

import java.time.Instant;

public record AuditEventResponse(
        String eventId,
        String eventType,
        String sourceService,
        String action,
        String actorType,
        String actorId,
        String entityId,
        String outcome,
        String details,
        Instant timestamp) {}
