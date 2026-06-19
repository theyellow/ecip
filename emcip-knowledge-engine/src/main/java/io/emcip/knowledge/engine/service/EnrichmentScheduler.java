package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.TriggerType;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class EnrichmentScheduler {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final EnrichmentSourceRepository sourceRepo;
    private final EnrichmentRunRepository runRepo;
    private final EnrichmentPipelineService pipelineService;

    /** Master tick fires at second :17 of every minute to avoid exact round times. */
    @Scheduled(cron = "17 * * * * *")
    public void tick() {
        List<EnrichmentSource> enabled = sourceRepo.findAllByEnabledTrue();
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime oneMinuteAgo = now.minusMinutes(1);

        for (EnrichmentSource source : enabled) {
            if (source.getScheduleCron() == null) continue;

            try {
                CronExpression expr = CronExpression.parse(source.getScheduleCron());
                ZonedDateTime next = expr.next(oneMinuteAgo);
                if (next != null && !next.isAfter(now)) {
                    dispatch(source);
                }
            } catch (Exception e) {
                log.warn("Invalid cron for source {}: {}", source.getId(), e.getMessage());
            }
        }
    }

    private void dispatch(EnrichmentSource source) {
        EnrichmentRun run = new EnrichmentRun();
        run.setSourceId(source.getId());
        run.setTriggerType(TriggerType.SCHEDULED);
        EnrichmentRun saved = runRepo.save(run);

        EXECUTOR.submit(
                () -> {
                    try {
                        pipelineService.execute(
                                source,
                                saved,
                                TriggerMode.SCHEDULED,
                                null,
                                null,
                                source.getTenantId());
                    } catch (Exception e) {
                        log.error(
                                "Scheduled enrichment failed for source {}: {}",
                                source.getId(),
                                e.getMessage(),
                                e);
                    }
                });
    }
}
