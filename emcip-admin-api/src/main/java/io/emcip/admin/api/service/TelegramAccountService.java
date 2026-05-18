package io.emcip.admin.api.service;

import io.emcip.admin.api.entity.AccountWatchedGroup;
import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.common.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class TelegramAccountService {

    private final TelegramAccountRepository repository;
    private final AccountWatchedGroupRepository watchedGroupRepository;
    private final GroupProfileRepository groupProfileRepository;
    private final R2dbcEntityTemplate r2dbcEntityTemplate;
    private final WebClient tdlibClient;

    @Value("${telegram.api-id}")
    private int telegramApiId;

    @Value("${telegram.api-hash}")
    private String telegramApiHash;

    public TelegramAccountService(
            TelegramAccountRepository repository,
            AccountWatchedGroupRepository watchedGroupRepository,
            GroupProfileRepository groupProfileRepository,
            R2dbcEntityTemplate r2dbcEntityTemplate,
            @Qualifier("tdlibWebClient") WebClient tdlibClient) {
        this.repository = repository;
        this.watchedGroupRepository = watchedGroupRepository;
        this.groupProfileRepository = groupProfileRepository;
        this.r2dbcEntityTemplate = r2dbcEntityTemplate;
        this.tdlibClient = tdlibClient;
    }

    public Flux<TelegramAccount> findAll() {
        if (TenantContext.isAdminMode()) {
            return repository.findAll();
        }
        return repository.findAllByTenantId(UUID.fromString(TenantContext.getTenantId()));
    }

    public Mono<TelegramAccount> getById(UUID id) {
        return repository
                .findById(id)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Account not found: " + id)));
    }

    public Mono<TelegramAccount> create(String phoneNumber, String displayName, UUID tenantId) {
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(UUID.randomUUID())
                        .phoneNumber(phoneNumber)
                        .apiId(telegramApiId)
                        .apiHash(telegramApiHash)
                        .displayName(displayName)
                        .status(TelegramAccountStatus.UNCONFIGURED)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        if (tenantId != null) {
            account.setTenantId(tenantId);
        } else if (!TenantContext.isAdminMode()) {
            account.setTenantId(UUID.fromString(TenantContext.getTenantId()));
        }
        return r2dbcEntityTemplate.insert(account);
    }

    public Mono<Void> delete(UUID id) {
        if (TenantContext.isAdminMode()) {
            return repository.deleteById(id);
        }
        return repository
                .findByIdAndTenantId(id, UUID.fromString(TenantContext.getTenantId()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(account -> repository.deleteById(id));
    }

    public Mono<TelegramAccount> getStatus(UUID id) {
        return repository
                .findById(id)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Account not found: " + id)))
                .flatMap(
                        account ->
                                tdlibClient
                                        .get()
                                        .uri("/api/auth/{id}/status", id)
                                        .retrieve()
                                        .bodyToMono(TdlibStatusResponse.class)
                                        .flatMap(
                                                r -> {
                                                    TelegramAccountStatus adapterStatus =
                                                            TelegramAccountStatus.valueOf(
                                                                    r.status());
                                                    if (adapterStatus != account.getStatus()
                                                            || (r.lastError() != null
                                                                    && !r.lastError()
                                                                            .equals(
                                                                                    account
                                                                                            .getLastError()))) {
                                                        return repository.save(
                                                                updateAccount(
                                                                        account,
                                                                        adapterStatus,
                                                                        r.lastError()));
                                                    }
                                                    return Mono.just(account);
                                                })
                                        .onErrorResume(e -> Mono.just(account)));
    }

    public Mono<TelegramAccount> reconnect(UUID id) {
        return repository
                .findById(id)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Account not found: " + id)))
                .flatMap(
                        account -> {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("phoneNumber", account.getPhoneNumber());
                            payload.put("apiId", account.getApiId());
                            payload.put("apiHash", account.getApiHash());
                            payload.put("sessionString", account.getSessionString());
                            return tdlibClient
                                    .post()
                                    .uri("/api/auth/{id}/initialize", id)
                                    .bodyValue(payload)
                                    .retrieve()
                                    .bodyToMono(Void.class)
                                    .then(pushWatchedGroups(id))
                                    .then(
                                            repository.save(
                                                    updateAccount(
                                                            account,
                                                            TelegramAccountStatus.AWAITING_CODE,
                                                            null)));
                        });
    }

    public Mono<Void> submitCode(UUID id, String code) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/code", id)
                .bodyValue(Map.of("code", code))
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Mono<Void> submitPassword(UUID id, String password) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/password", id)
                .bodyValue(Map.of("password", password))
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Mono<Void> logout(UUID id) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/logout", id)
                .retrieve()
                .bodyToMono(Void.class)
                .then(
                        repository
                                .findById(id)
                                .flatMap(
                                        a ->
                                                repository.save(
                                                        updateAccount(
                                                                a,
                                                                TelegramAccountStatus.DISCONNECTED,
                                                                null)))
                                .then());
    }

    public Mono<Void> sync() {
        return repository
                .findByStatus(TelegramAccountStatus.ACTIVE)
                .flatMap(account -> pushWatchedGroups(account.getId()))
                .then();
    }

    public Flux<GroupProfile> findWatchedGroups(UUID accountId) {
        return watchedGroupRepository
                .findByAccountId(accountId)
                .flatMap(awg -> groupProfileRepository.findById(awg.getGroupProfileId()));
    }

    public Mono<GroupProfile> watchGroup(UUID accountId, long chatId, String title) {
        return groupProfileRepository
                .findByTelegramChatId(chatId)
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        r2dbcEntityTemplate.insert(
                                                GroupProfile.builder()
                                                        .telegramChatId(chatId)
                                                        .name(
                                                                title != null
                                                                        ? title
                                                                        : "Chat " + chatId)
                                                        .rulesEnabled("[]")
                                                        .autoRespond(false)
                                                        .moderationLevel("MEDIUM")
                                                        .createdAt(Instant.now())
                                                        .updatedAt(Instant.now())
                                                        .build())))
                .flatMap(
                        profile ->
                                watchedGroupRepository
                                        .existsByAccountIdAndGroupProfileId(
                                                accountId, profile.getId())
                                        .flatMap(
                                                exists ->
                                                        exists
                                                                ? Mono.just(profile)
                                                                : r2dbcEntityTemplate
                                                                        .insert(
                                                                                AccountWatchedGroup
                                                                                        .builder()
                                                                                        .accountId(
                                                                                                accountId)
                                                                                        .groupProfileId(
                                                                                                profile
                                                                                                        .getId())
                                                                                        .createdAt(
                                                                                                Instant
                                                                                                        .now())
                                                                                        .build())
                                                                        .thenReturn(profile)))
                .flatMap(profile -> pushWatchedGroups(accountId).thenReturn(profile));
    }

    public Mono<Void> unwatchGroup(UUID accountId, long chatId) {
        return groupProfileRepository
                .findByTelegramChatId(chatId)
                .flatMap(
                        profile ->
                                watchedGroupRepository.deleteByAccountIdAndGroupProfileId(
                                        accountId, profile.getId()))
                .then(pushWatchedGroups(accountId));
    }

    public Mono<Void> pushWatchedGroups(UUID accountId) {
        return watchedGroupRepository
                .findByAccountId(accountId)
                .flatMap(awg -> groupProfileRepository.findById(awg.getGroupProfileId()))
                .map(GroupProfile::getTelegramChatId)
                .collectList()
                .flatMap(
                        chatIds ->
                                tdlibClient
                                        .post()
                                        .uri("/internal/watched-groups/{id}", accountId)
                                        .bodyValue(Map.of("chatIds", chatIds))
                                        .retrieve()
                                        .bodyToMono(Void.class)
                                        .retryWhen(
                                                reactor.util.retry.Retry.backoff(
                                                                5, Duration.ofSeconds(2))
                                                        .maxBackoff(Duration.ofSeconds(30))
                                                        .doBeforeRetry(
                                                                signal ->
                                                                        log.warn(
                                                                                "[{}] Retrying"
                                                                                    + " watched-groups"
                                                                                    + " push"
                                                                                    + " (attempt"
                                                                                    + " {}): {}",
                                                                                accountId,
                                                                                signal
                                                                                                .totalRetries()
                                                                                        + 1,
                                                                                signal.failure()
                                                                                        .getMessage())))
                                        .onErrorResume(
                                                e -> {
                                                    log.error(
                                                            "[{}] Failed to push watched groups"
                                                                    + " after retries: {}",
                                                            accountId,
                                                            e.getMessage());
                                                    return Mono.empty();
                                                }))
                .then();
    }

    public Mono<Void> initializeAccount(TelegramAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phoneNumber", account.getPhoneNumber());
        payload.put("apiId", account.getApiId());
        payload.put("apiHash", account.getApiHash());
        payload.put("sessionString", account.getSessionString());

        return tdlibClient
                .post()
                .uri("/api/auth/{id}/initialize", account.getId())
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Failed to resume session for account {}: {}",
                                    account.getId(),
                                    e.getMessage());
                            account.setStatus(TelegramAccountStatus.DISCONNECTED);
                            account.setLastError("Session resume failed: " + e.getMessage());
                            account.setUpdatedAt(Instant.now());
                            return repository.save(account).then();
                        });
    }

    public Mono<List<Map<String, Object>>> discoverChats(UUID accountId) {
        return tdlibClient
                .get()
                .uri("/internal/chats/{id}", accountId)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {})
                .collectList()
                .onErrorReturn(List.of());
    }

    private static TelegramAccount updateAccount(
            TelegramAccount a, TelegramAccountStatus status, String lastError) {
        a.setStatus(status);
        a.setLastError(lastError);
        a.setUpdatedAt(Instant.now());
        return a;
    }

    public record TdlibStatusResponse(String status, String lastError) {}
}
