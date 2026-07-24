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

    /**
     * @param row the stored row; its api_key is ciphertext and must not be masked directly
     * @param plaintextKey the decrypted key — masking shows its last 4 characters, which is how
     *     users identify a key
     */
    public static VendorApiKeyResponse from(
            io.emcip.admin.api.integration.VendorApiKeyRow row, String plaintextKey) {
        return new VendorApiKeyResponse(
                row.getId(),
                row.getVendorId(),
                row.getTenantId(),
                maskKey(plaintextKey),
                row.isEnabled(),
                row.getUpdatedAt());
    }

    private static String maskKey(String raw) {
        if (raw == null || raw.length() < 4) return "••••";
        return "••••••••" + raw.substring(raw.length() - 4);
    }
}
