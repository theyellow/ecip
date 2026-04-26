package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class TelegramAccountController {

    private final TelegramAccountRepository repository;
    private final WebClient tdlibClient;

    public TelegramAccountController(
            TelegramAccountRepository repository,
            @Qualifier("tdlibWebClient") WebClient tdlibClient) {
        this.repository = repository;
        this.tdlibClient = tdlibClient;
    }

    @GetMapping
    public Mono<List<Map<String, Object>>> listAccounts() {
        return repository.findAll().map(TelegramAccountController::toSafeMap).collectList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> createAccount(@RequestBody CreateAccountRequest req) {
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(UUID.randomUUID())
                        .phoneNumber(req.phoneNumber())
                        .apiId(req.apiId())
                        .apiHash(req.apiHash())
                        .displayName(req.displayName())
                        .status(TelegramAccountStatus.UNCONFIGURED)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        return repository.save(account).map(TelegramAccountController::toSafeMap);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteAccount(@PathVariable UUID id) {
        return repository.deleteById(id);
    }

    @GetMapping("/{id}/status")
    public Mono<Map<String, Object>> getStatus(@PathVariable UUID id) {
        return repository
                .findById(id)
                .flatMap(
                        account ->
                                tdlibClient
                                        .get()
                                        .uri("/api/auth/{id}/status", id)
                                        .retrieve()
                                        .bodyToMono(TdlibStatusResponse.class)
                                        .map(
                                                r -> {
                                                    Map<String, Object> m = new LinkedHashMap<>();
                                                    m.put("id", id.toString());
                                                    m.put("status", r.getStatus());
                                                    m.put("lastError", r.getLastError());
                                                    return m;
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

    @PostMapping("/{id}/reconnect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> reconnect(@PathVariable UUID id) {
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

    @PostMapping("/{id}/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> submitCode(@PathVariable UUID id, @RequestBody CodeRequest req) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/code", id)
                .bodyValue(Map.of("code", req.code()))
                .retrieve()
                .bodyToMono(Void.class);
    }

    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> submitPassword(@PathVariable UUID id, @RequestBody PasswordRequest req) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/password", id)
                .bodyValue(Map.of("password", req.password()))
                .retrieve()
                .bodyToMono(Void.class);
    }

    @PostMapping("/{id}/logout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> logout(@PathVariable UUID id) {
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

    public record CreateAccountRequest(
            String phoneNumber, Integer apiId, String apiHash, String displayName) {}

    public record CodeRequest(String code) {}

    public record PasswordRequest(String password) {}

    @Data
    public static class TdlibStatusResponse {
        private String status;
        private String lastError;
    }
}
