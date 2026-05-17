package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface TelegramAccountRepository extends ReactiveCrudRepository<TelegramAccount, UUID> {

    Flux<TelegramAccount> findByStatus(TelegramAccountStatus status);

    Flux<TelegramAccount> findAllByTenantId(UUID tenantId);

    Flux<TelegramAccount> findByStatusAndTenantId(TelegramAccountStatus status, UUID tenantId);
}
