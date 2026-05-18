package io.emcip.admin.api.config;

import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.admin.api.service.TelegramAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramSessionResumeRunner {

    private final TelegramAccountRepository repository;
    private final TelegramAccountService telegramAccountService;

    @EventListener(ApplicationReadyEvent.class)
    public void resumeActiveSessions() {
        repository
                .findByStatus(TelegramAccountStatus.ACTIVE)
                .flatMap(
                        account ->
                                telegramAccountService
                                        .initializeAccount(account)
                                        .then(
                                                telegramAccountService.pushWatchedGroups(
                                                        account.getId())))
                .subscribe(null, err -> log.warn("Session resume error: {}", err.getMessage()));
    }
}
