package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.dto.RefreshRequest;
import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.service.AuthService;
import io.emcip.admin.api.service.RefreshTokenService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private AuthService authService;
    @Mock private RefreshTokenService refreshTokenService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient =
                WebTestClient.bindToController(new AuthController(authService, refreshTokenService))
                        .controllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private TokenResponse tokenResponse() {
        return new TokenResponse("jwt-abc", Instant.now().plusSeconds(3600), "refresh-xyz");
    }

    @Test
    void token_validCredentials_returns200WithToken() {
        when(authService.authenticate("admin", "secret")).thenReturn(Mono.just(tokenResponse()));

        webTestClient
                .post()
                .uri("/api/auth/token")
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
        when(authService.authenticate("admin", "wrong"))
                .thenReturn(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "Invalid credentials")));

        webTestClient
                .post()
                .uri("/api/auth/token")
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
