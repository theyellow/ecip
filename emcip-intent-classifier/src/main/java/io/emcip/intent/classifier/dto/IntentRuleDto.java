package io.emcip.intent.classifier.dto;

import java.time.Instant;
import java.util.UUID;

public record IntentRuleDto(
        String id,
        String name,
        String description,
        String matchMode,
        String pattern,
        String intent,
        Double confidence,
        Integer priority,
        Boolean active,
        UUID tenantId,
        Instant createdAt,
        Instant updatedAt) {}
