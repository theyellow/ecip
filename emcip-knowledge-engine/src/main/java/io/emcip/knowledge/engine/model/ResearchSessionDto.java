package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResearchSessionDto(
        UUID id,
        UUID tenantId,
        String question,
        ResearchStatus status,
        int maxIterations,
        int maxLlmCalls,
        double costLimitUsd,
        int iterationsUsed,
        int llmCallsUsed,
        double costUsedUsd,
        String errorMessage,
        List<ResearchEvidenceDto> evidence,
        UUID reportId,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchSessionDto from(
            ResearchSession s, List<ResearchEvidenceDto> evidence, UUID reportId) {
        return new ResearchSessionDto(
                s.getId(),
                s.getTenantId(),
                s.getQuestion(),
                s.getStatus(),
                s.getMaxIterations(),
                s.getMaxLlmCalls(),
                s.getCostLimitUsd(),
                s.getIterationsUsed(),
                s.getLlmCallsUsed(),
                s.getCostUsedUsd(),
                s.getErrorMessage(),
                evidence,
                reportId,
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}
