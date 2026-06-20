package io.emcip.admin.api.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VendorApiKeyRequest(
        @NotBlank String vendorId, @NotBlank @Size(max = 512) String apiKey, boolean enabled) {}
