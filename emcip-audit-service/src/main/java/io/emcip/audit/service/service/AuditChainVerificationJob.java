package io.emcip.audit.service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditChainVerificationJob {

    private final AuditService auditService;

    @Value("${audit.chain-verification.batch-size:1000}")
    private int batchSize;

    @Scheduled(cron = "${audit.chain-verification.cron:0 17 3 * * *}")
    public void verifyRecentChain() {
        log.info("Starting audit chain verification for last {} records", batchSize);

        auditService
                .verifyChain(batchSize)
                .doOnSuccess(
                        result -> {
                            if (result.valid()) {
                                log.info(
                                        "Audit chain verification passed: {} records verified",
                                        result.recordsChecked());
                            } else {
                                log.error(
                                        "CRITICAL: Audit chain integrity violation detected at"
                                                + " record {}! Expected prevHash={}, found={}",
                                        result.brokenAtId(),
                                        result.expectedHash(),
                                        result.actualHash());
                            }
                        })
                .doOnError(e -> log.error("Audit chain verification failed: {}", e.getMessage()))
                .subscribe();
    }
}
