package io.emcip.admin.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank(message = "refreshToken is required") String refreshToken) {}
