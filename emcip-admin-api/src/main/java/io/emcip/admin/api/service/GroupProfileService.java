package io.emcip.admin.api.service;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.common.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class GroupProfileService {

    private final GroupProfileRepository repository;

    public Flux<GroupProfile> findAll() {
        if (TenantContext.isAdminMode()) {
            return repository.findAll();
        }
        return repository.findAllByTenantId(UUID.fromString(TenantContext.getTenantId()));
    }

    public Mono<GroupProfile> findByChatId(long chatId) {
        if (TenantContext.isAdminMode()) {
            return repository.findByTelegramChatId(chatId).switchIfEmpty(notFound(chatId));
        }
        return repository
                .findByTelegramChatIdAndTenantId(
                        chatId, UUID.fromString(TenantContext.getTenantId()))
                .switchIfEmpty(notFound(chatId));
    }

    public Mono<GroupProfile> create(GroupProfile profile) {
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        if (!TenantContext.isAdminMode()) {
            profile.setTenantId(UUID.fromString(TenantContext.getTenantId()));
        }
        return repository.save(profile);
    }

    public Mono<GroupProfile> update(long chatId, GroupProfile patch) {
        return findByChatId(chatId)
                .flatMap(
                        existing -> {
                            existing.setName(patch.getName());
                            existing.setDescription(patch.getDescription());
                            existing.setModerationLevel(patch.getModerationLevel());
                            existing.setAutoRespond(patch.isAutoRespond());
                            existing.setWelcomeMessage(patch.getWelcomeMessage());
                            existing.setUpdatedAt(Instant.now());
                            return repository.save(existing);
                        });
    }

    public Mono<Void> delete(long chatId) {
        return findByChatId(chatId).flatMap(repository::delete);
    }

    private <T> Mono<T> notFound(long chatId) {
        return Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found: " + chatId));
    }
}
