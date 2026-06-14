package io.emcip.knowledge.engine.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record GraphEdge(
        UUID id,
        String relationshipType,
        UUID sourceNodeId,
        UUID targetNodeId,
        Map<String, Object> properties,
        UUID sourceMessageId,
        Instant createdAt) {}
