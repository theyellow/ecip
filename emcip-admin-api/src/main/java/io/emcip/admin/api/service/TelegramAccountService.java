package io.emcip.admin.api.service;

import io.emcip.admin.api.entity.AccountWatchedGroup;
import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.common.crypto.SecretCipher;
import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
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
    private final CircuitBreaker tdlibCircuitBreaker;
    private final SecretCipher cipher;

    private static final String API_HASH_LOCATION = "telegram_accounts.api_hash";
    private static final String SESSION_LOCATION = "telegram_accounts.session_string";

    @Value("${telegram.api-id}")
    private int telegramApiId;

    @Value("${telegram.api-hash}")
    private String telegramApiHash;

    public TelegramAccountService(
            TelegramAccountRepository repository,
            AccountWatchedGroupRepository watchedGroupRepository,
            GroupProfileRepository groupProfileRepository,
            R2dbcEntityTemplate r2dbcEntityTemplate,
            @Qualifier("tdlibWebClient") WebClient tdlibClient,
            CircuitBreakerRegistry registry,
            SecretCipher cipher) {
        this.repository = repository;
        this.watchedGroupRepository = watchedGroupRepository;
        this.groupProfileRepository = groupProfileRepository;
        this.r2dbcEntityTemplate = r2dbcEntityTemplate;
        this.tdlibClient = tdlibClient;
        this.tdlibCircuitBreaker = registry.circuitBreaker("tdlib-adapter");
        this.cipher = cipher;
    }

    public Flux<TelegramAccount> findAll() {
        return Flux.deferContextual(
                ctx -> {
                    if (ReactorTenantContext.isAdminMode(ctx)) {
                        return repository.findAll();
                    }
                    return repository.findAllByTenantId(
                            UUID.fromString(ReactorTenantContext.getTenantId(ctx)));
                });
    }

    public Mono<TelegramAccount> getById(UUID id) {
        return Mono.deferContextual(
                ctx -> {
                    Mono<TelegramAccount> query =
                            ReactorTenantContext.isAdminMode(ctx)
                                    ? repository.findById(id)
                                    : repository.findByIdAndTenantId(
                                            id,
                                            UUID.fromString(ReactorTenantContext.getTenantId(ctx)));
                    return query.switchIfEmpty(
                            Mono.error(
                                    new ResponseStatusException(
                                            HttpStatus.NOT_FOUND, "Account not found: " + id)));
                });
    }

    public Mono<TelegramAccount> create(
            String phoneNumber, String displayName, UUID tenantId, Integer apiId, String apiHash) {
        return Mono.deferContextual(
                ctx -> {
                    TelegramAccount account =
                            TelegramAccount.builder()
                                    .id(UUID.randomUUID())
                                    .phoneNumber(phoneNumber)
                                    .apiId(apiId != null ? apiId : telegramApiId)
                                    .apiHash(
                                            cipher.encrypt(
                                                    apiHash != null ? apiHash : telegramApiHash))
                                    .displayName(displayName)
                                    .status(TelegramAccountStatus.UNCONFIGURED)
                                    .adapterId("default")
                                    .createdAt(Instant.now())
                                    .updatedAt(Instant.now())
                                    .build();
                    if (tenantId != null) {
                        account.setTenantId(tenantId);
                    } else if (!ReactorTenantContext.isAdminMode(ctx)) {
                        account.setTenantId(UUID.fromString(ReactorTenantContext.getTenantId(ctx)));
                    }
                    return r2dbcEntityTemplate.insert(account);
                });
    }

    public Mono<Void> delete(UUID id) {
        return Mono.deferContextual(
                ctx -> {
                    if (ReactorTenantContext.isAdminMode(ctx)) {
                        return repository.deleteById(id);
                    }
                    return repository
                            .findByIdAndTenantId(
                                    id, UUID.fromString(ReactorTenantContext.getTenantId(ctx)))
                            .switchIfEmpty(
                                    Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                            .flatMap(account -> repository.deleteById(id));
                });
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
                                        .transformDeferred(
                                                CircuitBreakerOperator.of(tdlibCircuitBreaker))
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
                            payload.put(
                                    "apiHash",
                                    cipher.decrypt(account.getApiHash(), API_HASH_LOCATION));
                            payload.put(
                                    "sessionString",
                                    cipher.decrypt(account.getSessionString(), SESSION_LOCATION));
                            if (account.getTenantId() != null) {
                                payload.put("tenantId", account.getTenantId().toString());
                            }
                            return tdlibClient
                                    .post()
                                    .uri("/api/auth/{id}/initialize", id)
                                    .bodyValue(payload)
                                    .retrieve()
                                    .bodyToMono(Void.class)
                                    .transformDeferred(
                                            CircuitBreakerOperator.of(tdlibCircuitBreaker))
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
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(tdlibCircuitBreaker));
    }

    public Mono<Void> submitPassword(UUID id, String password) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/password", id)
                .bodyValue(Map.of("password", password))
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(tdlibCircuitBreaker));
    }

    public Mono<Void> logout(UUID id) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/logout", id)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(tdlibCircuitBreaker))
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
        return repository
                .findById(accountId)
                .flatMap(
                        account ->
                                watchedGroupRepository
                                        .findByAccountId(accountId)
                                        .flatMap(
                                                awg ->
                                                        groupProfileRepository.findById(
                                                                awg.getGroupProfileId()))
                                        .collectList()
                                        .flatMap(
                                                profiles -> {
                                                    List<Long> chatIds =
                                                            profiles.stream()
                                                                    .map(
                                                                            GroupProfile
                                                                                    ::getTelegramChatId)
                                                                    .toList();
                                                    List<Long> knowledgeChatIds =
                                                            profiles.stream()
                                                                    .filter(
                                                                            GroupProfile
                                                                                    ::isKnowledgeForkEnabled)
                                                                    .map(
                                                                            GroupProfile
                                                                                    ::getTelegramChatId)
                                                                    .toList();
                                                    Map<String, Object> payload =
                                                            new LinkedHashMap<>();
                                                    payload.put("chatIds", chatIds);
                                                    payload.put(
                                                            "knowledgeChatIds", knowledgeChatIds);
                                                    if (account.getTenantId() != null) {
                                                        payload.put(
                                                                "tenantId",
                                                                account.getTenantId().toString());
                                                    }
                                                    return tdlibClient
                                                            .post()
                                                            .uri(
                                                                    "/internal/watched-groups/{id}",
                                                                    accountId)
                                                            .bodyValue(payload)
                                                            .retrieve()
                                                            .bodyToMono(Void.class)
                                                            .transformDeferred(
                                                                    CircuitBreakerOperator.of(
                                                                            tdlibCircuitBreaker))
                                                            .retryWhen(
                                                                    reactor.util.retry.Retry
                                                                            .backoff(
                                                                                    5,
                                                                                    Duration
                                                                                            .ofSeconds(
                                                                                                    2))
                                                                            .maxBackoff(
                                                                                    Duration
                                                                                            .ofSeconds(
                                                                                                    30))
                                                                            .doBeforeRetry(
                                                                                    signal ->
                                                                                            log
                                                                                                    .warn(
                                                                                                            "[{}] Retrying"
                                                                                                                + " watched-groups"
                                                                                                                + " push"
                                                                                                                + " (attempt"
                                                                                                                + " {}):"
                                                                                                                + " {}",
                                                                                                            accountId,
                                                                                                            signal
                                                                                                                            .totalRetries()
                                                                                                                    + 1,
                                                                                                            signal.failure()
                                                                                                                    .getMessage())))
                                                            .onErrorResume(
                                                                    e -> {
                                                                        log.error(
                                                                                "[{}] Failed to"
                                                                                    + " push"
                                                                                    + " watched"
                                                                                    + " groups"
                                                                                    + " after"
                                                                                    + " retries:"
                                                                                    + " {}",
                                                                                accountId,
                                                                                e.getMessage());
                                                                        return Mono.empty();
                                                                    });
                                                }))
                .then();
    }

    public Mono<Void> initializeAccount(TelegramAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phoneNumber", account.getPhoneNumber());
        payload.put("apiId", account.getApiId());
        payload.put("apiHash", cipher.decrypt(account.getApiHash(), API_HASH_LOCATION));
        payload.put("sessionString", cipher.decrypt(account.getSessionString(), SESSION_LOCATION));
        if (account.getTenantId() != null) {
            payload.put("tenantId", account.getTenantId().toString());
        }

        return tdlibClient
                .post()
                .uri("/api/auth/{id}/initialize", account.getId())
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(tdlibCircuitBreaker))
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
                .transformDeferred(CircuitBreakerOperator.of(tdlibCircuitBreaker))
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
