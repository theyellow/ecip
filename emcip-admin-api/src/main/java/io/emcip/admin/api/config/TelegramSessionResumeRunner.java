package io.emcip.admin.api.config;

import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.emcip.admin.api.service.TelegramAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TelegramSessionResumeRunner {

    private final TelegramAccountRepository repository;
    private final TelegramAccountService telegramAccountService;
    private final String adapterId;

    public TelegramSessionResumeRunner(
            TelegramAccountRepository repository,
            TelegramAccountService telegramAccountService,
            @Value("${tdlib.adapters[0].id:default}") String adapterId) {
        this.repository = repository;
        this.telegramAccountService = telegramAccountService;
        this.adapterId = adapterId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeActiveSessions() {
        log.info("Resuming Telegram sessions for adapter_id={}", adapterId);
        repository
                .findByStatusAndAdapterId(TelegramAccountStatus.ACTIVE, adapterId)
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
