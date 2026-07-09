package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.IngestionJob;

public record IngestionJobDto(
        String jobId,
        String sourceType,
        String sourceRef,
        String tenantId,
        String status,
        Integer chunkCount,
        String errorMessage,
        String createdAt,
        String contentHash) {

    public static IngestionJobDto from(IngestionJob job) {
        return new IngestionJobDto(
                job.getId().toString(),
                job.getSourceType().name(),
                job.getSourceRef(),
                job.getTenantId() != null ? job.getTenantId().toString() : null,
                job.getStatus().name(),
                job.getChunkCount(),
                job.getErrorMessage(),
                job.getCreatedAt() != null ? job.getCreatedAt().toString() : null,
                job.getContentHash());
    }
}
