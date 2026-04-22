package io.emcip.audit.service.service;

import io.emcip.audit.service.entity.AuditEventEntity;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class RetentionService {

    private final R2dbcEntityTemplate r2dbcEntityTemplate;

    @Value("${audit.retention.days:90}")
    private int retentionDays;

    /** Runs daily at 02:00 and deletes audit events older than the configured retention window. */
    @Scheduled(cron = "0 0 2 * * *")
    public void purgeOldEvents() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        log.info("Starting audit event retention purge. Cutoff: {}", cutoff);

        r2dbcEntityTemplate
                .delete(
                        Query.query(Criteria.where("created_at").lessThan(cutoff)),
                        AuditEventEntity.class)
                .doOnSuccess(
                        deleted ->
                                log.info(
                                        "Audit event retention purge complete. Deleted {} rows"
                                                + " older than {}",
                                        deleted,
                                        cutoff))
                .doOnError(
                        e -> log.error("Audit event retention purge failed: {}", e.getMessage(), e))
                .subscribe();
    }
}
