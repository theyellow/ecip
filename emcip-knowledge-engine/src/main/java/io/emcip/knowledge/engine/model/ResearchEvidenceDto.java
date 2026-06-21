package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.QueryStrategy;
import java.time.Instant;
import java.util.UUID;

public record ResearchEvidenceDto(
        UUID id,
        String subQuestion,
        QueryStrategy queryStrategy,
        String finding,
        String sourceType,
        String sourceRef,
        double confidenceScore,
        int iteration,
        Instant createdAt) {}
