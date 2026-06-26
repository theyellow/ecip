package io.emcip.intent.classifier.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IntentSignalConfigDto(
        String id,
        UUID tenantId,
        String description,
        Double foreignScriptRatio,
        Double cyrillicRatio,
        Integer lookalikeSuspicion,
        Integer zeroWidthAbuse,
        Double capsRatio,
        List<String> toxicityWords,
        Instant createdAt,
        Instant updatedAt) {}
