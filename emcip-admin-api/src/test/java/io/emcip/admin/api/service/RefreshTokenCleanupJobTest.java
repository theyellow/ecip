package io.emcip.admin.api.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.repository.RefreshTokenRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupJobTest {

    @Mock RefreshTokenRepository refreshTokenRepository;

    @InjectMocks RefreshTokenCleanupJob cleanupJob;

    @Test
    void deleteExpiredTokens_callsRepositoryWithCurrentInstant() {
        when(refreshTokenRepository.deleteByExpiresAtBefore(any(Instant.class)))
                .thenReturn(Mono.empty());

        cleanupJob.deleteExpiredTokens();

        verify(refreshTokenRepository).deleteByExpiresAtBefore(any(Instant.class));
    }

    @Test
    void deleteExpiredTokens_doesNotThrowOnRepositoryError() {
        when(refreshTokenRepository.deleteByExpiresAtBefore(any(Instant.class)))
                .thenReturn(Mono.error(new RuntimeException("DB unavailable")));

        cleanupJob.deleteExpiredTokens(); // must not propagate the error
    }
}
