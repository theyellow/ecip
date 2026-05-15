package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.audit.service.service.RetentionService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

class RetentionServiceIT extends AbstractAuditIntegrationTest {

    @Autowired private RetentionService retentionService;

    @Autowired private AuditEventRepository auditEventRepository;

    @BeforeEach
    void cleanDatabase() {
        auditEventRepository.deleteAll().block();
    }

    @Test
    void purgeOldEvents_deletesRecordsOlderThan90Days_retainsRecentOnes() {
        AuditEventEntity old =
                AuditEventEntity.builder()
                        .eventId("retention-old-001")
                        .eventType("TEST")
                        .correlationId("retention-old-001")
                        .sourceService("test")
                        .action("TEST")
                        .actorType("SYSTEM")
                        .outcome("PROCESSED")
                        .createdAt(Instant.now().minus(100, ChronoUnit.DAYS))
                        .build();

        AuditEventEntity recent =
                AuditEventEntity.builder()
                        .eventId("retention-recent-001")
                        .eventType("TEST")
                        .correlationId("retention-recent-001")
                        .sourceService("test")
                        .action("TEST")
                        .actorType("SYSTEM")
                        .outcome("PROCESSED")
                        .createdAt(Instant.now().minus(1, ChronoUnit.DAYS))
                        .build();

        Flux.concat(auditEventRepository.save(old), auditEventRepository.save(recent)).blockLast();

        // Act: purgeOldEvents() uses .subscribe() internally (fire-and-forget)
        retentionService.purgeOldEvents();

        // Assert: only the recent record remains
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            Long count = auditEventRepository.count().block();
                            assertThat(count).isEqualTo(1L);
                        });

        AuditEventEntity remaining = auditEventRepository.findAll().blockFirst();
        assertThat(remaining).isNotNull();
        assertThat(remaining.getEventId()).isEqualTo("retention-recent-001");
    }
}
