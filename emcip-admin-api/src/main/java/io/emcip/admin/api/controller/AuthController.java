package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.RefreshRequest;
import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.service.AuthService;
import io.emcip.admin.api.service.RefreshTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Obtain and refresh JWT tokens")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    @Operation(summary = "Obtain a JWT token")
    @PostMapping({"/api/auth/token", "/auth/token"})
    public Mono<ResponseEntity<TokenResponse>> token(@Valid @RequestBody AuthRequest request) {
        return authService
                .authenticate(request.username(), request.password())
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Refresh an access token using a valid refresh token")
    @PostMapping("/api/auth/refresh")
    public Mono<ResponseEntity<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken()).map(ResponseEntity::ok);
    }

    @Operation(summary = "Revoke a refresh token (logout)")
    @PostMapping("/api/auth/logout")
    public Mono<ResponseEntity<Void>> logout(@Valid @RequestBody RefreshRequest request) {
        return refreshTokenService
                .revoke(request.refreshToken())
                .thenReturn(ResponseEntity.<Void>noContent().build());
    }

    public record AuthRequest(
            @NotBlank(message = "username is required") String username,
            @NotBlank(message = "password is required") String password) {}
}
