package io.emcip.knowledge.engine.model;

import java.util.List;

public record IngestionJobDetailDto(
        IngestionJobDto job,
        List<ChunkSummaryDto> chunks,
        List<EntitySummaryDto> entities,
        int totalChunks,
        int totalEntities,
        int totalRelationships) {}
