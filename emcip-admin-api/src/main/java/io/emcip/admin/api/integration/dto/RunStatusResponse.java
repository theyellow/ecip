package io.emcip.admin.api.integration.dto;

import java.time.Instant;
import java.util.UUID;

public record RunStatusResponse(
        UUID id,
        UUID sourceId,
        String triggerType,
        Instant startedAt,
        Instant completedAt,
        String status,
        int itemsFetched,
        int itemsIngested,
        String errorMessage) {

    public static RunStatusResponse from(io.emcip.admin.api.integration.EnrichmentRunRow row) {
        return new RunStatusResponse(
                row.getId(),
                row.getSourceId(),
                row.getTriggerType(),
                row.getStartedAt(),
                row.getCompletedAt(),
                row.getStatus(),
                row.getItemsFetched(),
                row.getItemsIngested(),
                row.getErrorMessage());
    }
}
