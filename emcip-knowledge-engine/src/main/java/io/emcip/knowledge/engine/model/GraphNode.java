package io.emcip.knowledge.engine.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record GraphNode(
        UUID id,
        String conceptType,
        UUID tenantId,
        String label,
        Map<String, Object> properties,
        Instant createdAt,
        Instant updatedAt) {}
