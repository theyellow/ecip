package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.dto.RefreshRequest;
import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.security.JwtRevocationService;
import io.emcip.admin.api.service.AuthService;
import io.emcip.admin.api.service.RefreshTokenService;
import io.emcip.admin.api.util.ClientIp;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthControllerTest {

    @Mock private AuthService authService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AdminUserRepository userRepository;
    @Mock private JwtRevocationService revocationService;
    @Mock private RateLimiterRegistry rateLimiterRegistry;
    @Mock private ClientIp clientIp;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        RateLimiter rateLimiter = RateLimiter.of("test", RateLimiterConfig.ofDefaults());
        when(rateLimiterRegistry.rateLimiter(anyString())).thenReturn(rateLimiter);
        when(clientIp.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ClientIp.Resolved("203.0.113.7", "XFF_TRUSTED"));

        webTestClient =
                WebTestClient.bindToController(
                                new AuthController(
                                        authService,
                                        refreshTokenService,
                                        userRepository,
                                        revocationService,
                                        rateLimiterRegistry,
                                        clientIp))
                        .controllerAdvice(
                                new GlobalExceptionHandler(
                                        org.mockito.Mockito.mock(
                                                io.emcip.admin.api.audit.AdminAuditPublisher
                                                        .class)))
                        .build();
    }

    private TokenResponse tokenResponse() {
        return new TokenResponse("jwt-abc", Instant.now().plusSeconds(3600), "refresh-xyz");
    }

    @Test
    void token_validCredentials_returns200WithToken() {
        when(authService.authenticate(
                        eq("admin"), eq("secret"), eq("203.0.113.7"), eq("XFF_TRUSTED")))
                .thenReturn(Mono.just(tokenResponse()));

        webTestClient
                .post()
                .uri("/api/auth/token")
                .header("X-Forwarded-For", "203.0.113.7")
                .bodyValue(new AuthController.AuthRequest("admin", "secret"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(TokenResponse.class)
                .value(
                        resp -> {
                            assertThat(resp.token()).isEqualTo("jwt-abc");
                            assertThat(resp.refreshToken()).isEqualTo("refresh-xyz");
                        });
    }

    @Test
    void token_invalidCredentials_returns401() {
        when(authService.authenticate(
                        eq("admin"), eq("wrong"), eq("203.0.113.7"), eq("XFF_TRUSTED")))
                .thenReturn(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "Invalid credentials")));

        webTestClient
                .post()
                .uri("/api/auth/token")
                .header("X-Forwarded-For", "203.0.113.7")
                .bodyValue(new AuthController.AuthRequest("admin", "wrong"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void refresh_validToken_returns200() {
        when(authService.refresh("refresh-xyz")).thenReturn(Mono.just(tokenResponse()));

        webTestClient
                .post()
                .uri("/api/auth/refresh")
                .bodyValue(new RefreshRequest("refresh-xyz"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(TokenResponse.class)
                .value(resp -> assertThat(resp.token()).isEqualTo("jwt-abc"));
    }

    @Test
    void refresh_invalidToken_returns401() {
        when(authService.refresh("bad-token"))
                .thenReturn(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED,
                                        "Invalid or expired refresh token")));

        webTestClient
                .post()
                .uri("/api/auth/refresh")
                .bodyValue(new RefreshRequest("bad-token"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void logout_returns204() {
        when(refreshTokenService.revoke("refresh-xyz")).thenReturn(Mono.empty());

        webTestClient
                .post()
                .uri("/api/auth/logout")
                .bodyValue(new RefreshRequest("refresh-xyz"))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void token_blankUsername_returns400() {
        webTestClient
                .post()
                .uri("/api/auth/token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "", "password", "validpassword"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
}
