package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.AdminUser;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private AdminUserRepository adminUserRepository;
    @Mock private JwtService jwtService;
    @Mock private PasswordEncoder passwordEncoder;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient =
                WebTestClient.bindToController(
                                new AuthController(
                                        adminUserRepository, jwtService, passwordEncoder))
                        .build();
    }

    private AdminUser enabledUser() {
        return AdminUser.builder()
                .id(1L)
                .username("admin")
                .passwordHash("$2a$hashed")
                .role("ADMIN")
                .enabled(true)
                .build();
    }

    @Test
    void authenticate_validCredentials_returns200WithToken() {
        when(adminUserRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
        when(passwordEncoder.matches("secret", "$2a$hashed")).thenReturn(true);
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("jwt-token-abc");

        webTestClient
                .post()
                .uri("/api/auth/token")
                .bodyValue(new AuthController.AuthRequest("admin", "secret"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(AuthController.TokenResponse.class)
                .value(resp -> assertThat(resp.token()).isEqualTo("jwt-token-abc"));
    }

    @Test
    void authenticate_wrongPassword_returns401() {
        when(adminUserRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
        when(passwordEncoder.matches("wrong", "$2a$hashed")).thenReturn(false);

        webTestClient
                .post()
                .uri("/api/auth/token")
                .bodyValue(new AuthController.AuthRequest("admin", "wrong"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void authenticate_unknownUser_returns401() {
        when(adminUserRepository.findByUsername("nobody")).thenReturn(Mono.empty());

        webTestClient
                .post()
                .uri("/api/auth/token")
                .bodyValue(new AuthController.AuthRequest("nobody", "pass"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void authenticate_disabledUser_returns401() {
        AdminUser disabled =
                AdminUser.builder()
                        .id(2L)
                        .username("admin")
                        .passwordHash("$2a$hashed")
                        .role("ADMIN")
                        .enabled(false)
                        .build();
        when(adminUserRepository.findByUsername("admin")).thenReturn(Mono.just(disabled));

        webTestClient
                .post()
                .uri("/api/auth/token")
                .bodyValue(new AuthController.AuthRequest("admin", "secret"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }
}
