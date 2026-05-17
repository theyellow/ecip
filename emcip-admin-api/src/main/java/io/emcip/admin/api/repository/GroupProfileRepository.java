package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.GroupProfile;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface GroupProfileRepository extends ReactiveCrudRepository<GroupProfile, Long> {

    Mono<GroupProfile> findByTelegramChatId(Long chatId);

    Flux<GroupProfile> findAllByTenantId(UUID tenantId);

    Mono<GroupProfile> findByTelegramChatIdAndTenantId(Long chatId, UUID tenantId);
}
