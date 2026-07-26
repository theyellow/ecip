package io.emcip.audit.service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private AuditEventRepository repository;
    @Mock private DatabaseClient databaseClient;
    @Mock private TransactionalOperator transactionalOperator;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        auditService =
                new AuditService(repository, objectMapper, databaseClient, transactionalOperator);
    }

    @Test
    void save_persistsEntityAndReturnsIt() {
        AuditEventEntity entity =
                AuditEventEntity.builder()
                        .eventId("evt-001")
                        .eventType("TelegramMessage")
                        .sourceService("emcip-tdlib-adapter")
                        .action("TelegramMessage")
                        .actorType("SYSTEM")
                        .outcome("PROCESSED")
                        .createdAt(Instant.now())
                        .build();

        AuditEventEntity saved =
                AuditEventEntity.builder()
                        .id(1L)
                        .eventId("evt-001")
                        .eventType("TelegramMessage")
                        .sourceService("emcip-tdlib-adapter")
                        .action("TelegramMessage")
                        .actorType("SYSTEM")
                        .outcome("PROCESSED")
                        .createdAt(entity.getCreatedAt())
                        .build();

        when(repository.save(any(AuditEventEntity.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(auditService.save(entity))
                .expectNextMatches(
                        result ->
                                result.getId().equals(1L) && result.getEventId().equals("evt-001"))
                .verifyComplete();
    }

    @Test
    void findByEventType_returnsMatchingEntities() {
        AuditEventEntity entity1 =
                AuditEventEntity.builder()
                        .id(1L)
                        .eventId("evt-001")
                        .eventType("TelegramMessage")
                        .build();
        AuditEventEntity entity2 =
                AuditEventEntity.builder()
                        .id(2L)
                        .eventId("evt-002")
                        .eventType("TelegramMessage")
                        .build();

        when(repository.findByEventType("TelegramMessage")).thenReturn(Flux.just(entity1, entity2));

        StepVerifier.create(auditService.findByEventType("TelegramMessage"))
                .expectNextMatches(e -> e.getEventId().equals("evt-001"))
                .expectNextMatches(e -> e.getEventId().equals("evt-002"))
                .verifyComplete();
    }

    @Test
    void findByDateRange_returnsEntitiesInWindow() {
        Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant to = Instant.now();

        AuditEventEntity entity =
                AuditEventEntity.builder()
                        .id(1L)
                        .eventId("evt-100")
                        .eventType("PolicyDecision")
                        .createdAt(Instant.now().minus(30, ChronoUnit.MINUTES))
                        .build();

        when(repository.findByCreatedAtBetween(from, to)).thenReturn(Flux.just(entity));

        StepVerifier.create(auditService.findByDateRange(from, to))
                .expectNextMatches(e -> e.getEventId().equals("evt-100"))
                .verifyComplete();
    }

    @Test
    void findByEventTypeAndDateRange_returnsFilteredEntities() {
        Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant to = Instant.now();

        AuditEventEntity entity =
                AuditEventEntity.builder()
                        .id(1L)
                        .eventId("evt-200")
                        .eventType("IntentClassified")
                        .createdAt(Instant.now().minus(15, ChronoUnit.MINUTES))
                        .build();

        when(repository.findByEventTypeAndCreatedAtBetween("IntentClassified", from, to))
                .thenReturn(Flux.just(entity));

        StepVerifier.create(auditService.findByEventTypeAndDateRange("IntentClassified", from, to))
                .expectNextMatches(e -> e.getEventId().equals("evt-200"))
                .verifyComplete();
    }

    @Test
    void findByEventId_returnsEntityWhenPresent() {
        AuditEventEntity entity =
                AuditEventEntity.builder()
                        .id(1L)
                        .eventId("evt-300")
                        .eventType("ModerationFlag")
                        .build();

        when(repository.findByEventId("evt-300")).thenReturn(Mono.just(entity));

        StepVerifier.create(auditService.findByEventId("evt-300"))
                .expectNextMatches(e -> e.getEventId().equals("evt-300"))
                .verifyComplete();
    }

    @Test
    void findByEventId_returnsEmptyWhenAbsent() {
        when(repository.findByEventId("not-found")).thenReturn(Mono.empty());

        StepVerifier.create(auditService.findByEventId("not-found")).verifyComplete();
    }

    // --- hash chaining ---
    //
    // saveWithChain is no longer unit-tested here: it now runs the read-tail -> compute -> insert
    // sequence inside a single TransactionalOperator transaction guarded by a Postgres advisory
    // lock (pg_advisory_xact_lock), which requires a real database connection to exercise
    // meaningfully. See AuditChainConcurrencyIT for behavioral coverage (linear chain, no forks
    // under concurrency).

    @Test
    void deleteRecordsOlderThan_noRecords_returnsZero() {
        Instant cutoff = Instant.now().minus(3650, ChronoUnit.DAYS);
        when(repository.findOldestBeforeCutoff(cutoff)).thenReturn(Mono.empty());

        StepVerifier.create(auditService.deleteRecordsOlderThan(cutoff))
                .expectNext(0L)
                .verifyComplete();
    }

    @Test
    void deleteRecordsOlderThan_withRecords_deletesAndReturnsCount() {
        Instant cutoff = Instant.now().minus(3650, ChronoUnit.DAYS);
        AuditEventEntity oldest =
                AuditEventEntity.builder()
                        .id(1L)
                        .integrityHash("oldhash")
                        .createdAt(Instant.parse("2010-01-01T00:00:00Z"))
                        .build();

        when(repository.findOldestBeforeCutoff(cutoff)).thenReturn(Mono.just(oldest));
        when(repository.deleteByCreatedAtBefore(cutoff)).thenReturn(Mono.just(42L));

        StepVerifier.create(auditService.deleteRecordsOlderThan(cutoff))
                .expectNext(42L)
                .verifyComplete();
    }

    @Test
    void verifyChain_emptyDatabase_returnsValidWithZeroRecords() {
        when(repository.findTopNByOrderByIdDesc(100)).thenReturn(Flux.empty());

        StepVerifier.create(auditService.verifyChain(100))
                .expectNextMatches(r -> r.valid() && r.recordsChecked() == 0)
                .verifyComplete();
    }

    @Test
    void verifyChain_validChain_returnsValid() {
        // records returned newest-first: record2 then record1
        AuditEventEntity record1 = withId(row("evt-1", null), 1L);
        record1.setIntegrityHash(AuditService.computeIntegrityHash(record1));
        AuditEventEntity record2 = withId(row("evt-2", record1.getIntegrityHash()), 2L);
        record2.setIntegrityHash(AuditService.computeIntegrityHash(record2));

        when(repository.findTopNByOrderByIdDesc(100)).thenReturn(Flux.just(record2, record1));

        StepVerifier.create(auditService.verifyChain(100))
                .expectNextMatches(r -> r.valid() && r.recordsChecked() == 2)
                .verifyComplete();
    }

    @Test
    void verifyChain_brokenChain_returnsBrokenResult() {
        AuditEventEntity record1 = withId(row("evt-1", null), 1L);
        record1.setIntegrityHash(AuditService.computeIntegrityHash(record1));
        // record2's own content hash is self-consistent, but it points at the wrong predecessor.
        AuditEventEntity record2 = withId(row("evt-2", "WRONG_HASH"), 2L);
        record2.setIntegrityHash(AuditService.computeIntegrityHash(record2));

        when(repository.findTopNByOrderByIdDesc(100)).thenReturn(Flux.just(record2, record1));

        StepVerifier.create(auditService.verifyChain(100))
                .expectNextMatches(
                        r ->
                                !r.valid()
                                        && r.brokenAtId().equals(2L)
                                        && record1.getIntegrityHash().equals(r.expectedHash())
                                        && "WRONG_HASH".equals(r.actualHash()))
                .verifyComplete();
    }

    @Test
    void computeIntegrityHash_foldsPrevHash_soContentTamperingIsDetectable() {
        AuditEventEntity a = row("evt-1", null);
        a.setIntegrityHash(AuditService.computeIntegrityHash(a));
        AuditEventEntity b = row("evt-2", a.getIntegrityHash());
        b.setIntegrityHash(AuditService.computeIntegrityHash(b));

        when(repository.findTopNByOrderByIdDesc(10))
                .thenReturn(Flux.just(withId(b, 2L), withId(a, 1L)));

        StepVerifier.create(auditService.verifyChain(10))
                .assertNext(r -> assertThat(r.valid()).isTrue())
                .verifyComplete();
    }

    @Test
    void verifyChain_contentTampered_reportsContentTamperedReason() {
        AuditEventEntity a = row("evt-1", null);
        a.setIntegrityHash(AuditService.computeIntegrityHash(a));
        AuditEventEntity b = row("evt-2", a.getIntegrityHash());
        b.setIntegrityHash(AuditService.computeIntegrityHash(b));
        // Tamper b's content AFTER its hash was stored -> recompute won't match stored hash.
        b.setResourceId("tampered-resource");

        when(repository.findTopNByOrderByIdDesc(10))
                .thenReturn(Flux.just(withId(b, 2L), withId(a, 1L)));

        StepVerifier.create(auditService.verifyChain(10))
                .assertNext(
                        r -> {
                            assertThat(r.valid()).isFalse();
                            assertThat(r.reason())
                                    .isEqualTo(AuditService.ChainFailureReason.CONTENT_TAMPERED);
                            assertThat(r.brokenAtId()).isEqualTo(2L);
                        })
                .verifyComplete();
    }

    @Test
    void verifyChain_brokenLinkage_reportsBrokenLinkageReason() {
        AuditEventEntity a = row("evt-1", null);
        a.setIntegrityHash(AuditService.computeIntegrityHash(a));
        // b points at the wrong predecessor hash but its own content hash is self-consistent.
        AuditEventEntity b =
                row("evt-2", "0000000000000000000000000000000000000000000000000000000000000000");
        b.setIntegrityHash(AuditService.computeIntegrityHash(b));

        when(repository.findTopNByOrderByIdDesc(10))
                .thenReturn(Flux.just(withId(b, 2L), withId(a, 1L)));

        StepVerifier.create(auditService.verifyChain(10))
                .assertNext(
                        r -> {
                            assertThat(r.valid()).isFalse();
                            assertThat(r.reason())
                                    .isEqualTo(AuditService.ChainFailureReason.BROKEN_LINKAGE);
                        })
                .verifyComplete();
    }

    private static AuditEventEntity row(String eventId, String prevHash) {
        AuditEventEntity e =
                AuditEventEntity.builder()
                        .eventId(eventId)
                        .eventType("TelegramMessage")
                        .sourceService("emcip-tdlib-adapter")
                        .action("TelegramMessage")
                        .actorType("SYSTEM")
                        .actorId("actor-1")
                        .resourceType("TelegramMessage")
                        .resourceId("res-1")
                        .outcome("PROCESSED")
                        .createdAt(Instant.parse("2026-07-25T10:00:00Z"))
                        .build();
        e.setPrevHash(prevHash);
        return e;
    }

    private static AuditEventEntity withId(AuditEventEntity e, long id) {
        e.setId(id);
        return e;
    }
}
