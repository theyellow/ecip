package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.audit.AdminAuditPublisher;
import io.emcip.admin.api.entity.AdminUser;
import io.emcip.admin.api.repository.AdminUserRepository;
import io.emcip.admin.api.repository.TenantRepository;
import io.emcip.admin.api.security.JwtService;
import io.emcip.admin.api.security.Role;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private AdminUserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private TenantRepository tenantRepository;
    @Mock private AdminAuditPublisher auditPublisher;

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
    void authenticate_validCredentials_returnsTokenAndRecordsLastLogin() {
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
        when(passwordEncoder.matches("secret", "$2a$hash")).thenReturn(true);
        when(userRepository.save(any())).thenReturn(Mono.just(enabledUser()));
        when(jwtService.generateTokenWithJti("admin", "ADMIN", null, null))
                .thenReturn(new JwtService.TokenWithJti("jwt-abc", "jti-123"));
        when(refreshTokenService.issue(1L)).thenReturn(Mono.just("refresh-xyz"));

        StepVerifier.create(authService.authenticate("admin", "secret", "203.0.113.7", "XFF"))
                .assertNext(
                        resp -> {
                            assertThat(resp.token()).isEqualTo("jwt-abc");
                            assertThat(resp.refreshToken()).isEqualTo("refresh-xyz");
                            assertThat(resp.expiresAt()).isNotNull();
                        })
                .verifyComplete();

        var details = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditPublisher)
                .publish(
                        eq("LOGIN_SUCCESS"),
                        eq("Session"),
                        eq("admin"),
                        eq("admin"),
                        isNull(),
                        details.capture());
        assertThat(details.getValue())
                .containsEntry("ip", "203.0.113.7")
                .containsEntry("ipSource", "XFF");
    }

    @Test
    void authenticate_validCredentials_savesLastLogin() {
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
        when(passwordEncoder.matches("secret", "$2a$hash")).thenReturn(true);
        when(userRepository.save(any())).thenReturn(Mono.just(enabledUser()));
        when(jwtService.generateTokenWithJti("admin", "ADMIN", null, null))
                .thenReturn(new JwtService.TokenWithJti("jwt-abc", "jti-123"));
        when(refreshTokenService.issue(1L)).thenReturn(Mono.just("refresh-xyz"));

        Instant before = Instant.now();
        StepVerifier.create(authService.authenticate("admin", "secret", "203.0.113.7", "XFF"))
                .assertNext(resp -> assertThat(resp.token()).isNotNull())
                .verifyComplete();

        org.mockito.ArgumentCaptor<AdminUser> captor =
                org.mockito.ArgumentCaptor.forClass(AdminUser.class);
        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.times(1))
                .save(captor.capture());
        // Single save sets both lastLogin and currentJti
        assertThat(captor.getValue().getLastLogin()).isNotNull();
        assertThat(captor.getValue().getLastLogin()).isAfterOrEqualTo(before);
        assertThat(captor.getValue().getCurrentJti()).isEqualTo("jti-123");
    }

    @Test
    void authenticate_wrongPassword_returnsUnauthorized() {
        when(userRepository.findByUsername("admin")).thenReturn(Mono.just(enabledUser()));
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        StepVerifier.create(authService.authenticate("admin", "wrong", "203.0.113.7", "XFF"))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid credentials"))
                .verify();

        verify(auditPublisher)
                .publish(
                        eq("LOGIN_FAILURE"),
                        eq("Session"),
                        eq("admin"),
                        eq("admin"),
                        isNull(),
                        anyMap(),
                        eq("FAILURE"));
    }

    @Test
    void authenticate_unknownUser_returnsUnauthorized() {
        when(userRepository.findByUsername("nobody")).thenReturn(Mono.empty());

        StepVerifier.create(authService.authenticate("nobody", "pass", "203.0.113.7", "XFF"))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid credentials"))
                .verify();

        verify(auditPublisher)
                .publish(
                        eq("LOGIN_FAILURE"),
                        eq("Session"),
                        eq("nobody"),
                        eq("nobody"),
                        isNull(),
                        anyMap(),
                        eq("FAILURE"));
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

        StepVerifier.create(authService.authenticate("admin", "secret", "10.0.0.9", "SOCKET"))
                .expectError()
                .verify();

        verify(auditPublisher)
                .publish(
                        eq("LOGIN_FAILURE"),
                        eq("Session"),
                        eq("admin"),
                        eq("admin"),
                        isNull(),
                        anyMap(),
                        eq("FAILURE"));
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void userNotFound_publishesLoginFailure_notFound_globalTenant_and401() {
        when(userRepository.findByUsername("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(authService.authenticate("ghost", "pw", "203.0.113.7", "XFF"))
                .expectErrorSatisfies(
                        e -> {
                            assertThat(e).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) e).getStatusCode().value())
                                    .isEqualTo(401);
                        })
                .verify();

        var details = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditPublisher)
                .publish(
                        eq("LOGIN_FAILURE"),
                        eq("Session"),
                        eq("ghost"),
                        eq("ghost"),
                        isNull(),
                        details.capture(),
                        eq("FAILURE"));
        assertThat(details.getValue())
                .containsEntry("reason", "USER_NOT_FOUND")
                .containsEntry("ip", "203.0.113.7")
                .containsEntry("ipSource", "XFF");
    }

    @Test
    void wrongPassword_publishesLoginFailure_badPassword_withResolvedTenant() {
        UUID tenant = UUID.randomUUID();
        AdminUser user =
                AdminUser.builder()
                        .id(3L)
                        .username("bob")
                        .passwordHash("H")
                        .role(Role.ADMIN)
                        .enabled(true)
                        .tenantId(tenant)
                        .build();
        when(userRepository.findByUsername("bob")).thenReturn(Mono.just(user));
        when(passwordEncoder.matches("wrong", "H")).thenReturn(false);

        StepVerifier.create(authService.authenticate("bob", "wrong", "10.0.0.5", "SOCKET"))
                .expectError(ResponseStatusException.class)
                .verify();

        var details = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditPublisher)
                .publish(
                        eq("LOGIN_FAILURE"),
                        eq("Session"),
                        eq("bob"),
                        eq("bob"),
                        eq(tenant),
                        details.capture(),
                        eq("FAILURE"));
        assertThat(details.getValue()).containsEntry("reason", "BAD_PASSWORD");
    }

    @Test
    void disabledUser_publishesLoginFailure_disabled() {
        UUID tenant2 = UUID.randomUUID();
        AdminUser user =
                AdminUser.builder()
                        .id(4L)
                        .username("carol")
                        .passwordHash("H2")
                        .role(Role.ADMIN)
                        .enabled(false)
                        .tenantId(tenant2)
                        .build();
        when(userRepository.findByUsername("carol")).thenReturn(Mono.just(user));

        StepVerifier.create(authService.authenticate("carol", "pw", "10.0.0.9", "SOCKET"))
                .expectError(ResponseStatusException.class)
                .verify();

        verify(auditPublisher)
                .publish(
                        eq("LOGIN_FAILURE"),
                        eq("Session"),
                        eq("carol"),
                        eq("carol"),
                        eq(tenant2),
                        anyMap(),
                        eq("FAILURE"));
        // password encoder must NOT be consulted for a disabled user
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void refresh_validToken_returnsNewTokenPair() {
        RefreshTokenService.RotateResult rotated =
                new RefreshTokenService.RotateResult("new-refresh", 1L);
        when(refreshTokenService.rotate("old-refresh")).thenReturn(Mono.just(rotated));
        when(userRepository.findById(1L)).thenReturn(Mono.just(enabledUser()));
        when(userRepository.save(any())).thenReturn(Mono.just(enabledUser()));
        when(jwtService.generateTokenWithJti("admin", "ADMIN", null, null))
                .thenReturn(new JwtService.TokenWithJti("new-jwt", "jti-456"));

        StepVerifier.create(authService.refresh("old-refresh"))
                .assertNext(
                        resp -> {
                            assertThat(resp.token()).isEqualTo("new-jwt");
                            assertThat(resp.refreshToken()).isEqualTo("new-refresh");
                        })
                .verifyComplete();
    }
}
