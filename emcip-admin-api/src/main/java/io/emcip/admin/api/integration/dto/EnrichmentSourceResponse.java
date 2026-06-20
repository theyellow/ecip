package io.emcip.admin.api.integration.dto;

import java.time.Instant;
import java.util.UUID;

public record EnrichmentSourceResponse(
        UUID id,
        String vendorId,
        UUID tenantId,
        boolean enabled,
        String scheduleCron,
        Instant lastRunAt,
        String lastRunStatus,
        long version) {

    public static EnrichmentSourceResponse from(
            io.emcip.admin.api.integration.EnrichmentSourceRow row) {
        return new EnrichmentSourceResponse(
                row.getId(),
                row.getVendorId(),
                row.getTenantId(),
                row.isEnabled(),
                row.getScheduleCron(),
                row.getLastRunAt(),
                row.getLastRunStatus(),
                row.getVersion());
    }
}
