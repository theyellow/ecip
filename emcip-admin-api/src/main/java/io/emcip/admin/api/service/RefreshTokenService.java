package io.emcip.admin.api.service;

import io.emcip.admin.api.entity.RefreshToken;
import io.emcip.admin.api.repository.RefreshTokenRepository;
import io.emcip.admin.api.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository repository;

    public record RotateResult(String newRawToken, Long userId) {}

    public Mono<String> issue(Long userId) {
        String raw = UUID.randomUUID().toString();
        RefreshToken token =
                RefreshToken.builder()
                        .userId(userId)
                        .tokenHash(sha256(raw))
                        .expiresAt(Instant.now().plusMillis(JwtService.REFRESH_EXPIRY_MS))
                        .createdAt(Instant.now())
                        .revoked(false)
                        .build();
        return repository.save(token).thenReturn(raw);
    }

    public Mono<RotateResult> rotate(String rawToken) {
        return repository
                .findByTokenHash(sha256(rawToken))
                .filter(t -> !t.isRevoked() && t.getExpiresAt().isAfter(Instant.now()))
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED,
                                        "Invalid or expired refresh token")))
                .flatMap(
                        old -> {
                            Long userId = old.getUserId();
                            old.setRevoked(true);
                            String newRaw = UUID.randomUUID().toString();
                            RefreshToken newToken =
                                    RefreshToken.builder()
                                            .userId(userId)
                                            .tokenHash(sha256(newRaw))
                                            .expiresAt(
                                                    Instant.now()
                                                            .plusMillis(
                                                                    JwtService.REFRESH_EXPIRY_MS))
                                            .createdAt(Instant.now())
                                            .revoked(false)
                                            .build();
                            return repository
                                    .save(old)
                                    .then(repository.save(newToken))
                                    .thenReturn(new RotateResult(newRaw, userId));
                        });
    }

    public Mono<Void> revoke(String rawToken) {
        return repository
                .findByTokenHash(sha256(rawToken))
                .flatMap(
                        t -> {
                            t.setRevoked(true);
                            return repository.save(t);
                        })
                .then();
    }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
