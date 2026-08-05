package io.emcip.admin.api.controller;

import io.emcip.admin.api.dto.RefreshRequest;
import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.security.JwtRevocationService;
import io.emcip.admin.api.security.JwtService;
import io.emcip.admin.api.service.AuthService;
import io.emcip.admin.api.service.RefreshTokenService;
import io.emcip.admin.api.util.ClientIp;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Obtain and refresh JWT tokens")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final AdminUserRepository userRepository;
    private final JwtRevocationService revocationService;
    private final RateLimiterRegistry rateLimiterRegistry;

    @Operation(summary = "Obtain a JWT token")
    @PostMapping({"/api/auth/token", "/auth/token"})
    public Mono<ResponseEntity<TokenResponse>> token(
            @Valid @RequestBody AuthRequest request, ServerWebExchange exchange) {
        ClientIp.Resolved client = ClientIp.resolve(exchange.getRequest());
        return authService
                .authenticate(request.username(), request.password(), client.ip(), client.source())
                .map(ResponseEntity::ok)
                .transformDeferred(RateLimiterOperator.of(rateLimiterRegistry.rateLimiter("auth")));
    }

    @Operation(summary = "Refresh an access token using a valid refresh token")
    @PostMapping("/api/auth/refresh")
    public Mono<ResponseEntity<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return authService
                .refresh(request.refreshToken())
                .map(ResponseEntity::ok)
                .transformDeferred(RateLimiterOperator.of(rateLimiterRegistry.rateLimiter("auth")));
    }

    @Operation(summary = "Revoke a refresh token (logout)")
    @PostMapping("/api/auth/logout")
    public Mono<ResponseEntity<Void>> logout(@Valid @RequestBody RefreshRequest request) {
        return refreshTokenService
                .revoke(request.refreshToken())
                .thenReturn(ResponseEntity.<Void>noContent().build());
    }

    @Operation(summary = "Revoke a user's access token (admin only)")
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    @PostMapping("/api/auth/revoke/{userId}")
    public Mono<ResponseEntity<Void>> revokeAccess(@PathVariable Long userId) {
        return userRepository
                .findById(userId)
                .<ResponseEntity<Void>>flatMap(
                        user -> {
                            if (user.getCurrentJti() != null) {
                                revocationService.revoke(
                                        user.getCurrentJti(),
                                        Instant.now().plusMillis(JwtService.EXPIRY_MS));
                            }
                            return Mono.just(ResponseEntity.<Void>noContent().build());
                        })
                .defaultIfEmpty(ResponseEntity.<Void>notFound().build());
    }

    public record AuthRequest(
            @NotBlank(message = "username is required") String username,
            @NotBlank(message = "password is required") String password) {}
}
