package io.emcip.admin.api.controller;

import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.security.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Obtain JWT tokens for API access")
public class AuthController {

    private final AdminUserRepository adminUserRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Operation(summary = "Authenticate and receive a JWT token")
    @PostMapping("/token")
    public Mono<ResponseEntity<TokenResponse>> authenticate(@RequestBody AuthRequest request) {
        return adminUserRepository
                .findByUsername(request.username())
                .filter(
                        user ->
                                user.isEnabled()
                                        && passwordEncoder.matches(
                                                request.password(), user.getPasswordHash()))
                .map(
                        user -> {
                            String token =
                                    jwtService.generateToken(user.getUsername(), user.getRole());
                            Instant expiresAt = Instant.now().plusMillis(8 * 60 * 60 * 1000L);
                            return ResponseEntity.ok(new TokenResponse(token, expiresAt));
                        })
                .defaultIfEmpty(
                        ResponseEntity.status(HttpStatus.UNAUTHORIZED).<TokenResponse>build());
    }

    public record AuthRequest(String username, String password) {}

    public record TokenResponse(String token, Instant expiresAt) {}
}
