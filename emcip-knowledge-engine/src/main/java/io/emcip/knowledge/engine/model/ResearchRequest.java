package io.emcip.knowledge.engine.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record ResearchRequest(
        @NotBlank String question,
        UUID tenantId,
        @Min(0) @Max(50) int maxIterations,
        @Min(0) @Max(100) int maxLlmCalls,
        @DecimalMin("0.0") double costLimitUsd) {

    public ResearchRequest {
        if (maxIterations == 0) maxIterations = 10;
        if (maxLlmCalls == 0) maxLlmCalls = 20;
        if (costLimitUsd == 0.0) costLimitUsd = 1.00;
    }
}
