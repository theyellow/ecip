package io.emcip.audit.service.service;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRetentionJob {

    private final AuditService auditService;

    @Value("${audit.retention:P10Y}")
    private String retentionPeriod;

    @Scheduled(cron = "${audit.retention-cleanup.cron:0 42 4 1 * *}")
    public void cleanupExpiredRecords() {
        Period retention = Period.parse(retentionPeriod);
        Instant cutoff = ZonedDateTime.now(ZoneOffset.UTC).minus(retention).toInstant();

        log.info("Starting audit retention cleanup, deleting records before {}", cutoff);

        auditService
                .deleteRecordsOlderThan(cutoff)
                .doOnSuccess(
                        count ->
                                log.info(
                                        "Audit retention cleanup complete, deleted {} records",
                                        count))
                .doOnError(e -> log.error("Audit retention cleanup failed: {}", e.getMessage()))
                .subscribe();
    }
}
