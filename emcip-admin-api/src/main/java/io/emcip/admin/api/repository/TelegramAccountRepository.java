package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface TelegramAccountRepository extends ReactiveCrudRepository<TelegramAccount, UUID> {
    Flux<TelegramAccount> findByStatus(TelegramAccountStatus status);
}
