package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.dto.TokenResponse;
import io.emcip.admin.api.service.AuthService;
import java.time.Instant;
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

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new AuthController(authService)).build();
    }

    @Test
    void token_validCredentials_returns200WithToken() {
        when(authService.authenticate("admin", "secret"))
                .thenReturn(Mono.just(new TokenResponse("jwt-token-abc", Instant.now())));

        webTestClient
                .post()
                .uri("/api/auth/token")
                .bodyValue(new AuthController.AuthRequest("admin", "secret"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(TokenResponse.class)
                .value(resp -> assertThat(resp.token()).isEqualTo("jwt-token-abc"));
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
}
