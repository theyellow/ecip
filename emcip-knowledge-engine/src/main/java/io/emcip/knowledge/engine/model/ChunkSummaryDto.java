package io.emcip.knowledge.engine.model;

import java.util.UUID;

public record ChunkSummaryDto(
        UUID id, int chunkIndex, String contentPreview, int entityCount, int relationshipCount) {}
