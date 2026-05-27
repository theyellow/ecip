package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TelegramAccountRepository extends ReactiveCrudRepository<TelegramAccount, UUID> {

    Flux<TelegramAccount> findByStatus(TelegramAccountStatus status);

    Flux<TelegramAccount> findByStatusAndAdapterId(TelegramAccountStatus status, String adapterId);

    Flux<TelegramAccount> findAllByTenantId(UUID tenantId);

    Flux<TelegramAccount> findByStatusAndTenantId(TelegramAccountStatus status, UUID tenantId);

    Mono<TelegramAccount> findByIdAndTenantId(UUID id, UUID tenantId);
}
