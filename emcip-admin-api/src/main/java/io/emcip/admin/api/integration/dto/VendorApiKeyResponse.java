package io.emcip.admin.api.integration.dto;

import java.time.Instant;
import java.util.UUID;

public record VendorApiKeyResponse(
        UUID id,
        String vendorId,
        UUID tenantId,
        String maskedKey,
        boolean enabled,
        Instant updatedAt) {

    public static VendorApiKeyResponse from(io.emcip.admin.api.integration.VendorApiKeyRow row) {
        return new VendorApiKeyResponse(
                row.getId(),
                row.getVendorId(),
                row.getTenantId(),
                maskKey(row.getApiKey()),
                row.isEnabled(),
                row.getUpdatedAt());
    }

    private static String maskKey(String raw) {
        if (raw == null || raw.length() < 4) return "••••";
        return "••••••••" + raw.substring(raw.length() - 4);
    }
}
