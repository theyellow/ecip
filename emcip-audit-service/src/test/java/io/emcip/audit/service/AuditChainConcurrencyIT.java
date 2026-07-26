package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.audit.service.service.AuditService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class AuditChainConcurrencyIT extends AbstractAuditIntegrationTest {

    @Autowired private AuditService auditService;
    @Autowired private AuditEventRepository repository;
    @Autowired private DatabaseClient databaseClient;

    @BeforeEach
    void clean() {
        // TRUNCATE bypasses the row-level DELETE trigger (added in Task 4).
        databaseClient.sql("TRUNCATE audit_events").fetch().rowsUpdated().block();
    }

    @Test
    void concurrentSaveWithChain_producesLinearChain_noForks() {
        int n = 200;
        Flux.range(0, n)
                .parallel(8)
                .runOn(Schedulers.boundedElastic())
                .flatMap(i -> auditService.saveWithChain(newEntity("evt-" + i)))
                .sequential()
                .blockLast();

        List<AuditEventEntity> rows =
                repository
                        .findAll()
                        .sort((a, b) -> Long.compare(a.getId(), b.getId()))
                        .collectList()
                        .block();

        assertThat(rows).hasSize(n);
        // Genesis row has no predecessor.
        assertThat(rows.get(0).getPrevHash()).isNull();
        Set<String> seenPrev = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            String prev = rows.get(i).getPrevHash();
            // Linear: each row points at exactly its immediate predecessor's hash.
            assertThat(prev).isEqualTo(rows.get(i - 1).getIntegrityHash());
            // No fork: no two rows share a predecessor hash.
            assertThat(seenPrev.add(prev)).as("duplicate prev_hash = fork").isTrue();
        }
    }

    private static AuditEventEntity newEntity(String eventId) {
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
                .createdAt(Instant.now())
                .build();
    }
}
