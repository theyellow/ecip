package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.GroupProfile;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface GroupProfileRepository extends ReactiveCrudRepository<GroupProfile, Long> {

    Mono<GroupProfile> findByTelegramChatId(Long chatId);
}
