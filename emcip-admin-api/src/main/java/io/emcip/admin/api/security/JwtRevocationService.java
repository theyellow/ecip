package io.emcip.admin.api.security;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class JwtRevocationService {

    private final ConcurrentHashMap<String, Instant> revokedTokens = new ConcurrentHashMap<>();

    public void revoke(String jti, Instant expiresAt) {
        revokedTokens.put(jti, expiresAt);
        log.info("Revoked JWT jti={}", jti);
    }

    public boolean isRevoked(String jti) {
        return revokedTokens.containsKey(jti);
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 300_000) // every 5 minutes
    public void cleanup() {
        Instant now = Instant.now();
        int before = revokedTokens.size();
        revokedTokens.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        int removed = before - revokedTokens.size();
        if (removed > 0) {
            log.debug("Cleaned up {} expired revocation entries", removed);
        }
    }
}
