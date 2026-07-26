package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.audit.service.service.AuditService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

class AuditDeletePreventionIT extends AbstractAuditIntegrationTest {

    @Autowired private AuditService auditService;
    @Autowired private AuditEventRepository repository;
    @Autowired private DatabaseClient databaseClient;

    @BeforeEach
    void clean() {
        databaseClient.sql("TRUNCATE audit_events").fetch().rowsUpdated().block();
    }

    @Test
    void directDelete_withoutPurgeFlag_isBlockedByTrigger() {
        AuditEventEntity saved =
                auditService.saveWithChain(row("evt-del-1", Instant.now())).block();
        assertThat(saved.getId()).isNotNull();

        assertThatThrownBy(() -> repository.deleteById(saved.getId()).block())
                .hasMessageContaining("audit_events rows cannot be deleted");

        assertThat(repository.count().block()).isEqualTo(1L);
    }

    @Test
    void retention_withPurgeFlag_deletesExpiredRows() {
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);
        Instant recent = Instant.now();
        auditService.saveWithChain(row("evt-old", old)).block();
        auditService.saveWithChain(row("evt-recent", recent)).block();

        Long deleted =
                auditService
                        .deleteRecordsOlderThan(Instant.now().minus(365, ChronoUnit.DAYS))
                        .block();

        assertThat(deleted).isEqualTo(1L);
        assertThat(repository.count().block()).isEqualTo(1L);
        assertThat(repository.findByEventId("evt-recent").block()).isNotNull();
    }

    private static AuditEventEntity row(String eventId, Instant createdAt) {
        return AuditEventEntity.builder()
                .eventId(eventId)
                .eventType("TelegramMessage")
                .sourceService("emcip-tdlib-adapter")
                .action("TelegramMessage")
                .actorType("SYSTEM")
                .actorId("actor")
                .resourceType("TelegramMessage")
                .resourceId("res")
                .outcome("PROCESSED")
                .createdAt(createdAt)
                .build();
    }
}
