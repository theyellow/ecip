package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.AccountWatchedGroup;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface AccountWatchedGroupRepository
        extends ReactiveCrudRepository<AccountWatchedGroup, Long> {

    Flux<AccountWatchedGroup> findByAccountId(UUID accountId);

    Mono<Void> deleteByAccountIdAndGroupProfileId(UUID accountId, Long groupProfileId);

    Mono<Boolean> existsByAccountIdAndGroupProfileId(UUID accountId, Long groupProfileId);
}
