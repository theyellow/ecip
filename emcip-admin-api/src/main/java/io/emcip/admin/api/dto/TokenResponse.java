package io.emcip.admin.api.dto;

import java.time.Instant;

public record TokenResponse(String token, Instant expiresAt) {}
