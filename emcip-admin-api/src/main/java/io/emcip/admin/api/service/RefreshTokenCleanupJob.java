package io.emcip.admin.api.service;

import io.emcip.admin.api.repository.RefreshTokenRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 0 3 * * *") // 03:00 daily
    public void deleteExpiredTokens() {
        Instant cutoff = Instant.now();
        refreshTokenRepository
                .deleteByExpiresAtBefore(cutoff)
                .doOnSuccess(v -> log.info("Refresh token cleanup complete (cutoff={})", cutoff))
                .doOnError(e -> log.error("Refresh token cleanup failed", e))
                .subscribe();
    }
}
