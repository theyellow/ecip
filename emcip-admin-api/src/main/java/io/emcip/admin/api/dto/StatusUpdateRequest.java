package io.emcip.admin.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to update the review status of a policy flag")
public record StatusUpdateRequest(
        @NotBlank(message = "status is required")
                @Schema(description = "New status value", example = "REVIEWED")
                String status) {}
