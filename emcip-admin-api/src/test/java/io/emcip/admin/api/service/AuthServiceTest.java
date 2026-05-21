package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.AdminUser;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.repository.TenantRepository;
import io.emcip.admin.api.security.JwtService;
import io.emcip.admin.api.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private AdminUserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private TenantRepository tenantRepository;

    @InjectMocks private AuthService authService;

    private AdminUser enabledUser() {
        return AdminUser.builder()
                .id(1L)
                .username("admin")
                .passwordHash("$2a$hash")
                .role(Role.ADMIN)
                .enabled(true)
                .build();
    }

    @Test
    void authenticate_validCredentials_returnsTokenWithRefresh() {
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
        when(passwordEncoder.matches("secret", "$2a$hash")).thenReturn(true);
        when(jwtService.generateToken("admin", "ADMIN", null, null)).thenReturn("jwt-abc");
        when(refreshTokenService.issue(1L)).thenReturn(Mono.just("refresh-xyz"));

        StepVerifier.create(authService.authenticate("admin", "secret"))
                .assertNext(
                        resp -> {
                            assertThat(resp.token()).isEqualTo("jwt-abc");
                            assertThat(resp.refreshToken()).isEqualTo("refresh-xyz");
                            assertThat(resp.expiresAt()).isNotNull();
                        })
                .verifyComplete();
    }

    @Test
    void authenticate_wrongPassword_returnsUnauthorized() {
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        StepVerifier.create(authService.authenticate("admin", "wrong"))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid credentials"))
                .verify();
    }

    @Test
    void authenticate_unknownUser_returnsUnauthorized() {
        when(userRepository.findByUsername("nobody")).thenReturn(Mono.empty());

        StepVerifier.create(authService.authenticate("nobody", "pass"))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid credentials"))
                .verify();
    }

    @Test
    void authenticate_disabledUser_returnsUnauthorized() {
        AdminUser disabled =
                AdminUser.builder()
                        .id(2L)
                        .username("admin")
                        .passwordHash("$2a$hash")
                        .role(Role.ADMIN)
                        .enabled(false)
                        .build();
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(disabled));

        StepVerifier.create(authService.authenticate("admin", "secret")).expectError().verify();
    }

    @Test
    void refresh_validToken_returnsNewTokenPair() {
        RefreshTokenService.RotateResult rotated =
                new RefreshTokenService.RotateResult("new-refresh", 1L);
        when(refreshTokenService.rotate("old-refresh")).thenReturn(Mono.just(rotated));
        when(userRepository.findById(1L)).thenReturn(Mono.just(enabledUser()));
        when(jwtService.generateToken("admin", "ADMIN", null, null)).thenReturn("new-jwt");

        StepVerifier.create(authService.refresh("old-refresh"))
                .assertNext(
                        resp -> {
                            assertThat(resp.token()).isEqualTo("new-jwt");
                            assertThat(resp.refreshToken()).isEqualTo("new-refresh");
                        })
                .verifyComplete();
    }
}
