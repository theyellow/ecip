package io.emcip.admin.api.config;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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

    public TelegramSessionResumeRunner(
            TelegramAccountRepository repository,
            @Qualifier("tdlibWebClient") WebClient tdlibClient) {
        this.repository = repository;
        this.tdlibClient = tdlibClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeActiveSessions() {
        repository
                .findByStatus(TelegramAccountStatus.ACTIVE)
                .flatMap(this::initializeAccount)
                .subscribe(
                        id -> log.info("Session resume triggered for account {}", id),
                        err -> log.warn("Session resume error: {}", err.getMessage()));
    }

    private Mono<String> initializeAccount(TelegramAccount account) {
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
                .thenReturn(account.getId().toString())
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Failed to resume session for account {}: {}",
                                    account.getId(),
                                    e.getMessage());
                            account.setStatus(TelegramAccountStatus.DISCONNECTED);
                            account.setLastError("Session resume failed: " + e.getMessage());
                            account.setUpdatedAt(Instant.now());
                            return repository.save(account).thenReturn(account.getId().toString());
                        });
    }
}
