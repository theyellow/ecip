package io.emcip.admin.api.config;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class TelegramSessionResumeRunner {

    private final TelegramAccountRepository repository;
    private final WebClient tdlibClient;
    private final AccountWatchedGroupRepository watchedGroupRepository;
    private final GroupProfileRepository groupProfileRepository;

    public TelegramSessionResumeRunner(
            TelegramAccountRepository repository,
            @Qualifier("tdlibWebClient") WebClient tdlibClient,
            AccountWatchedGroupRepository watchedGroupRepository,
            GroupProfileRepository groupProfileRepository) {
        this.repository = repository;
        this.tdlibClient = tdlibClient;
        this.watchedGroupRepository = watchedGroupRepository;
        this.groupProfileRepository = groupProfileRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeActiveSessions() {
        repository
                .findByStatus(TelegramAccountStatus.ACTIVE)
                .flatMap(
                        account ->
                                initializeAccount(account).then(pushWatchedGroups(account.getId())))
                .subscribe(null, err -> log.warn("Session resume error: {}", err.getMessage()));
    }

    private Mono<Void> initializeAccount(TelegramAccount account) {
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

    private Mono<Void> pushWatchedGroups(UUID accountId) {
        return watchedGroupRepository
                .findByAccountId(accountId)
                .flatMap(awg -> groupProfileRepository.findById(awg.getGroupProfileId()))
                .map(profile -> profile.getTelegramChatId())
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
                                                            "[{}] Failed to push watched groups on"
                                                                    + " startup after retries: {}",
                                                            accountId,
                                                            e.getMessage());
                                                    return Mono.empty();
                                                }))
                .then();
    }
}
