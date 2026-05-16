package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.AccountWatchedGroup;
import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/telegram/accounts")
@Tag(
        name = "Telegram Accounts",
        description = "Manage Telegram account connections, authentication, and group watching")
public class TelegramAccountController {

    private final TelegramAccountRepository repository;
    private final R2dbcEntityTemplate r2dbcEntityTemplate;
    private final WebClient tdlibClient;
    private final AccountWatchedGroupRepository watchedGroupRepository;
    private final GroupProfileRepository groupProfileRepository;
    private final int telegramApiId;
    private final String telegramApiHash;

    public TelegramAccountController(
            TelegramAccountRepository repository,
            R2dbcEntityTemplate r2dbcEntityTemplate,
            @Qualifier("tdlibWebClient") WebClient tdlibClient,
            AccountWatchedGroupRepository watchedGroupRepository,
            GroupProfileRepository groupProfileRepository,
            @Value("${telegram.api-id}") int telegramApiId,
            @Value("${telegram.api-hash}") String telegramApiHash) {
        this.repository = repository;
        this.r2dbcEntityTemplate = r2dbcEntityTemplate;
        this.tdlibClient = tdlibClient;
        this.watchedGroupRepository = watchedGroupRepository;
        this.groupProfileRepository = groupProfileRepository;
        this.telegramApiId = telegramApiId;
        this.telegramApiHash = telegramApiHash;
    }

    @Operation(summary = "List all Telegram accounts")
    @GetMapping
    public Mono<List<Map<String, Object>>> listAccounts() {
        return repository.findAll().map(TelegramAccountController::toSafeMap).collectList();
    }

    @Operation(summary = "Create and connect a new Telegram account")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> createAccount(@RequestBody CreateAccountRequest req) {
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(UUID.randomUUID())
                        .phoneNumber(req.phoneNumber())
                        .apiId(telegramApiId)
                        .apiHash(telegramApiHash)
                        .displayName(req.displayName())
                        .status(TelegramAccountStatus.UNCONFIGURED)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        return r2dbcEntityTemplate.insert(account).map(TelegramAccountController::toSafeMap);
    }

    @Operation(summary = "Delete a Telegram account")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteAccount(@PathVariable("id") UUID id) {
        return repository.deleteById(id);
    }

    @Operation(summary = "Get connection status of a Telegram account")
    @GetMapping("/{id}/status")
    public Mono<Map<String, Object>> getStatus(@PathVariable("id") UUID id) {
        return repository
                .findById(id)
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
                                                                    r.getStatus());
                                                    Map<String, Object> m = new LinkedHashMap<>();
                                                    m.put("id", id.toString());
                                                    m.put("status", r.getStatus());
                                                    m.put("lastError", r.getLastError());
                                                    if (adapterStatus != account.getStatus()
                                                            || (r.getLastError() != null
                                                                    && !r.getLastError()
                                                                            .equals(
                                                                                    account
                                                                                            .getLastError()))) {
                                                        return repository
                                                                .save(
                                                                        update(
                                                                                account,
                                                                                adapterStatus,
                                                                                r.getLastError()))
                                                                .thenReturn(m);
                                                    }
                                                    return Mono.just(m);
                                                })
                                        .onErrorResume(
                                                e -> {
                                                    Map<String, Object> m = new LinkedHashMap<>();
                                                    m.put("id", id.toString());
                                                    m.put("status", account.getStatus().name());
                                                    m.put("lastError", account.getLastError());
                                                    return Mono.just(m);
                                                }))
                .switchIfEmpty(
                        Mono.error(new IllegalArgumentException("Account not found: " + id)));
    }

    @Operation(summary = "Reconnect a disconnected Telegram account")
    @PostMapping("/{id}/reconnect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> reconnect(@PathVariable("id") UUID id) {
        return repository
                .findById(id)
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
                                                    update(
                                                            account,
                                                            TelegramAccountStatus.AWAITING_CODE,
                                                            null)))
                                    .thenReturn(Map.<String, Object>of("accepted", true))
                                    .onErrorResume(
                                            e -> {
                                                log.warn(
                                                        "reconnect failed for {}: {}",
                                                        id,
                                                        e.getMessage());
                                                return Mono.just(
                                                        Map.of(
                                                                "accepted",
                                                                false,
                                                                "reason",
                                                                e.getMessage()));
                                            });
                        })
                .switchIfEmpty(Mono.just(Map.of("accepted", false, "reason", "Account not found")));
    }

    @Operation(summary = "Submit authentication code for a Telegram account")
    @PostMapping("/{id}/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> submitCode(@PathVariable("id") UUID id, @RequestBody CodeRequest req) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/code", id)
                .bodyValue(Map.of("code", req.code()))
                .retrieve()
                .bodyToMono(Void.class);
    }

    @Operation(summary = "Submit 2FA password for a Telegram account")
    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> submitPassword(
            @PathVariable("id") UUID id, @RequestBody PasswordRequest req) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/password", id)
                .bodyValue(Map.of("password", req.password()))
                .retrieve()
                .bodyToMono(Void.class);
    }

    @Operation(summary = "Log out a Telegram account")
    @PostMapping("/{id}/logout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> logout(@PathVariable("id") UUID id) {
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
                                                        update(
                                                                a,
                                                                TelegramAccountStatus.DISCONNECTED,
                                                                null)))
                                .then());
    }

    @Operation(summary = "Sync watched groups across all accounts")
    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> syncWatchedGroups() {
        return repository
                .findByStatus(TelegramAccountStatus.ACTIVE)
                .flatMap(account -> pushWatchedGroups(account.getId()))
                .then();
    }

    @Operation(summary = "Discover available Telegram chats for an account")
    @GetMapping("/{id}/chats")
    public Mono<List<Map<String, Object>>> discoverChats(@PathVariable("id") UUID id) {
        return tdlibClient
                .get()
                .uri("/internal/chats/{id}", id)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {})
                .collectList()
                .onErrorReturn(List.of());
    }

    @Operation(summary = "List watched groups for an account")
    @GetMapping("/{id}/watched")
    public Mono<List<Map<String, Object>>> listWatched(@PathVariable("id") UUID id) {
        return watchedGroupRepository
                .findByAccountId(id)
                .flatMap(
                        awg ->
                                groupProfileRepository
                                        .findById(awg.getGroupProfileId())
                                        .map(this::toWatchedMap))
                .collectList();
    }

    @Operation(summary = "Start watching a Telegram group")
    @PostMapping("/{id}/watch")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> watchGroup(
            @PathVariable("id") UUID accountId, @RequestBody WatchRequest req) {
        return groupProfileRepository
                .findByTelegramChatId(req.chatId())
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        r2dbcEntityTemplate.insert(
                                                GroupProfile.builder()
                                                        .telegramChatId(req.chatId())
                                                        .name(
                                                                req.title() != null
                                                                        ? req.title()
                                                                        : "Chat " + req.chatId())
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
                .flatMap(profile -> pushWatchedGroups(accountId).thenReturn(profile))
                .map(this::toWatchedMap);
    }

    @Operation(summary = "Stop watching a Telegram group")
    @DeleteMapping("/{id}/watch/{chatId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unwatchGroup(
            @PathVariable("id") UUID accountId, @PathVariable("chatId") Long chatId) {
        return groupProfileRepository
                .findByTelegramChatId(chatId)
                .flatMap(
                        profile ->
                                watchedGroupRepository.deleteByAccountIdAndGroupProfileId(
                                        accountId, profile.getId()))
                .then(pushWatchedGroups(accountId));
    }

    private Mono<Void> pushWatchedGroups(UUID accountId) {
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
                                        .onErrorResume(
                                                e -> {
                                                    log.warn(
                                                            "[{}] Failed to push watched groups:"
                                                                    + " {}",
                                                            accountId,
                                                            e.getMessage());
                                                    return Mono.empty();
                                                }));
    }

    private Map<String, Object> toWatchedMap(GroupProfile profile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chatId", profile.getTelegramChatId());
        m.put("groupProfileId", profile.getId());
        m.put("name", profile.getName());
        m.put("moderationLevel", profile.getModerationLevel());
        return m;
    }

    private static TelegramAccount update(
            TelegramAccount a, TelegramAccountStatus status, String lastError) {
        a.setStatus(status);
        a.setLastError(lastError);
        a.setUpdatedAt(Instant.now());
        return a;
    }

    private static Map<String, Object> toSafeMap(TelegramAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId().toString());
        m.put("displayName", a.getDisplayName() != null ? a.getDisplayName() : "");
        m.put("phoneNumber", a.getPhoneNumber());
        m.put("apiId", a.getApiId());
        m.put("status", a.getStatus().name());
        m.put("lastError", a.getLastError());
        m.put("sessionStringSet", a.getSessionString() != null && !a.getSessionString().isEmpty());
        m.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        return m;
    }

    public record CreateAccountRequest(String phoneNumber, String displayName) {}

    public record CodeRequest(String code) {}

    public record PasswordRequest(String password) {}

    public record WatchRequest(long chatId, String title) {}

    @Data
    public static class TdlibStatusResponse {
        private String status;
        private String lastError;
    }
}
