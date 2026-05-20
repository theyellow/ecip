package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.RefreshToken;
import java.time.Instant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface RefreshTokenRepository extends ReactiveCrudRepository<RefreshToken, Long> {

    Mono<RefreshToken> findByTokenHash(String tokenHash);

    Mono<Void> deleteByExpiresAtBefore(Instant cutoff);
}
