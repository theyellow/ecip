package io.emcip.admin.api.service;

import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.common.tenant.ReactorTenantContext;
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
    private final AccountWatchedGroupRepository watchedGroupRepository;
    private final TelegramAccountRepository accountRepository;

    public Flux<GroupProfile> findAll() {
        return Flux.deferContextual(
                ctx -> {
                    if (ReactorTenantContext.isAdminMode(ctx)) {
                        return repository.findAll();
                    }
                    return repository.findAllByTenantId(
                            UUID.fromString(ReactorTenantContext.getTenantId(ctx)));
                });
    }

    public Mono<GroupProfile> findByChatId(long chatId) {
        return Mono.deferContextual(
                ctx -> {
                    if (ReactorTenantContext.isAdminMode(ctx)) {
                        return repository
                                .findByTelegramChatId(chatId)
                                .switchIfEmpty(notFound(chatId));
                    }
                    return repository
                            .findByTelegramChatIdAndTenantId(
                                    chatId, UUID.fromString(ReactorTenantContext.getTenantId(ctx)))
                            .switchIfEmpty(notFound(chatId));
                });
    }

    public Mono<GroupProfile> create(GroupProfile profile) {
        return Mono.deferContextual(
                ctx -> {
                    profile.setCreatedAt(Instant.now());
                    profile.setUpdatedAt(Instant.now());
                    if (!ReactorTenantContext.isAdminMode(ctx)) {
                        profile.setTenantId(UUID.fromString(ReactorTenantContext.getTenantId(ctx)));
                    }
                    return repository.save(profile);
                });
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
                            existing.setKnowledgeForkEnabled(patch.isKnowledgeForkEnabled());
                            existing.setUpdatedAt(Instant.now());
                            return repository.save(existing);
                        });
    }

    public Mono<Void> delete(long chatId) {
        return findByChatId(chatId).flatMap(repository::delete);
    }

    public Flux<java.util.Map<String, Object>> findWatchersByChatId(long chatId) {
        return findByChatId(chatId)
                .flatMapMany(
                        profile -> watchedGroupRepository.findByGroupProfileId(profile.getId()))
                .flatMap(
                        awg ->
                                accountRepository
                                        .findById(awg.getAccountId())
                                        .map(
                                                acc ->
                                                        java.util.Map.<String, Object>of(
                                                                "accountId",
                                                                acc.getId().toString(),
                                                                "displayName",
                                                                acc.getDisplayName() != null
                                                                        ? acc.getDisplayName()
                                                                        : "",
                                                                "phoneNumber",
                                                                acc.getPhoneNumber())));
    }

    private <T> Mono<T> notFound(long chatId) {
        return Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found: " + chatId));
    }
}
