package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.config.GlobalExceptionHandler;
import io.emcip.admin.api.dto.UserRequest;
import io.emcip.admin.api.dto.UserResponse;
import io.emcip.admin.api.security.Role;
import io.emcip.admin.api.service.UserManagementService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserManagementControllerTest {

    @Mock private UserManagementService userManagementService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient =
                WebTestClient.bindToController(new UserManagementController(userManagementService))
                        .controllerAdvice(
                                new GlobalExceptionHandler(
                                        org.mockito.Mockito.mock(
                                                io.emcip.admin.api.audit.AdminAuditPublisher
                                                        .class)))
                        .build();
    }

    private UserResponse sampleUser() {
        return UserResponse.builder()
                .id(1L)
                .username("admin")
                .email("admin@example.com")
                .role(Role.ADMIN)
                .tenantId(null)
                .tenantName(null)
                .enabled(true)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void listUsers_returns200() {
        when(userManagementService.findAll()).thenReturn(Flux.just(sampleUser()));

        webTestClient
                .get()
                .uri("/api/users")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(UserResponse.class)
                .hasSize(1);
    }

    @Test
    void createUser_returns201() {
        when(userManagementService.create(any())).thenReturn(Mono.just(sampleUser()));

        UserRequest req = new UserRequest();
        req.setUsername("admin");
        req.setEmail("admin@example.com");
        req.setPassword("secret");
        req.setRole(Role.ADMIN);

        webTestClient
                .post()
                .uri("/api/users")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void updateUser_returns200() {
        when(userManagementService.update(any(), any(), any())).thenReturn(Mono.just(sampleUser()));

        UserRequest req = new UserRequest();
        req.setUsername("admin");
        req.setEmail("admin@example.com");
        req.setRole(Role.ADMIN);

        webTestClient
                .put()
                .uri("/api/users/1")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void deleteUser_returns204() {
        when(userManagementService.delete(any(), any())).thenReturn(Mono.empty());

        webTestClient.delete().uri("/api/users/1").exchange().expectStatus().isNoContent();
    }
}
