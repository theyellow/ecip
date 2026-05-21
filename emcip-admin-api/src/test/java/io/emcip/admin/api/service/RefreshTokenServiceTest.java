package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.RefreshToken;
import io.emcip.admin.api.repository.RefreshTokenRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository repository;
    @InjectMocks private RefreshTokenService service;

    @Test
    void issue_savesTokenAndReturnsRaw() {
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.issue(1L))
                .assertNext(raw -> assertThat(raw).isNotBlank().hasSize(36)) // UUID
                .verifyComplete();
    }

    @Test
    void rotate_validToken_revokesOldAndReturnsNewRaw() {
        String raw = "test-raw-token-1234-5678-90ab-cdef";
        String hash = RefreshTokenService.sha256(raw);
        RefreshToken existing =
                RefreshToken.builder()
                        .id(1L)
                        .userId(42L)
                        .tokenHash(hash)
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .revoked(false)
                        .build();

        when(repository.findByTokenHash(hash)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.rotate(raw))
                .assertNext(
                        result -> {
                            assertThat(result.newRawToken()).isNotBlank();
                            assertThat(result.userId()).isEqualTo(42L);
                        })
                .verifyComplete();
    }

    @Test
    void rotate_expiredToken_returnsUnauthorized() {
        String raw = "expired-raw-token-1234-5678-90ab";
        String hash = RefreshTokenService.sha256(raw);
        RefreshToken expired =
                RefreshToken.builder()
                        .id(2L)
                        .userId(42L)
                        .tokenHash(hash)
                        .expiresAt(Instant.now().minusSeconds(1))
                        .revoked(false)
                        .build();

        when(repository.findByTokenHash(hash)).thenReturn(Mono.just(expired));

        StepVerifier.create(service.rotate(raw)).expectError().verify();
    }

    @Test
    void rotate_revokedToken_returnsUnauthorized() {
        String raw = "revoked-raw-token-1234-5678-90ab";
        String hash = RefreshTokenService.sha256(raw);
        RefreshToken revoked =
                RefreshToken.builder()
                        .id(3L)
                        .userId(42L)
                        .tokenHash(hash)
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .revoked(true)
                        .build();

        when(repository.findByTokenHash(hash)).thenReturn(Mono.just(revoked));

        StepVerifier.create(service.rotate(raw)).expectError().verify();
    }

    @Test
    void revoke_marksTokenRevoked() {
        String raw = "revoke-me-raw-token-1234-5678-90";
        String hash = RefreshTokenService.sha256(raw);
        RefreshToken token =
                RefreshToken.builder()
                        .id(4L)
                        .userId(1L)
                        .tokenHash(hash)
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .revoked(false)
                        .build();

        when(repository.findByTokenHash(hash)).thenReturn(Mono.just(token));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.revoke(raw)).verifyComplete();
    }
}
