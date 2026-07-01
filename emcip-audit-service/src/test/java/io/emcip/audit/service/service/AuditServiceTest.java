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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private AuditEventRepository repository;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        auditService = new AuditService(repository, objectMapper);
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

    @Test
    void saveWithChain_firstRecord_setsNullPrevHashAndComputesIntegrityHash() {
        AuditEventEntity entity =
                AuditEventEntity.builder()
                        .eventId("evt-chain-001")
                        .eventType("AUTH")
                        .actorId("user-1")
                        .resourceType("SESSION")
                        .resourceId("sess-1")
                        .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                        .build();
        AuditEventEntity saved = AuditEventEntity.builder().id(1L).eventId("evt-chain-001").build();

        when(repository.findTopByOrderByIdDesc()).thenReturn(Mono.empty());
        when(repository.save(any(AuditEventEntity.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(auditService.saveWithChain(entity))
                .expectNextMatches(r -> r.getId().equals(1L))
                .verifyComplete();

        // entity should have had prevHash=null and integrityHash set
        assertThat(entity.getPrevHash()).isNull();
        assertThat(entity.getIntegrityHash()).isNotNull().hasSize(64);
    }

    @Test
    void saveWithChain_subsequentRecord_linksPrevHash() {
        AuditEventEntity previous =
                AuditEventEntity.builder()
                        .id(1L)
                        .integrityHash(
                                "aabbccddeeff0011aabbccddeeff0011aabbccddeeff0011aabbccddeeff0011")
                        .build();
        AuditEventEntity entity =
                AuditEventEntity.builder()
                        .eventId("evt-chain-002")
                        .eventType("AUTH")
                        .createdAt(Instant.parse("2026-01-01T00:01:00Z"))
                        .build();
        AuditEventEntity saved = AuditEventEntity.builder().id(2L).eventId("evt-chain-002").build();

        when(repository.findTopByOrderByIdDesc()).thenReturn(Mono.just(previous));
        when(repository.save(any(AuditEventEntity.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(auditService.saveWithChain(entity))
                .expectNextMatches(r -> r.getId().equals(2L))
                .verifyComplete();

        assertThat(entity.getPrevHash())
                .isEqualTo("aabbccddeeff0011aabbccddeeff0011aabbccddeeff0011aabbccddeeff0011");
        assertThat(entity.getIntegrityHash()).isNotNull().hasSize(64);
    }

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
        String hash1 = "aaaa";
        String hash2 = "bbbb";
        // records returned newest-first: record2 then record1
        AuditEventEntity record2 =
                AuditEventEntity.builder().id(2L).integrityHash(hash2).prevHash(hash1).build();
        AuditEventEntity record1 =
                AuditEventEntity.builder().id(1L).integrityHash(hash1).prevHash(null).build();

        when(repository.findTopNByOrderByIdDesc(100)).thenReturn(Flux.just(record2, record1));

        StepVerifier.create(auditService.verifyChain(100))
                .expectNextMatches(r -> r.valid() && r.recordsChecked() == 2)
                .verifyComplete();
    }

    @Test
    void verifyChain_brokenChain_returnsBrokenResult() {
        AuditEventEntity record2 =
                AuditEventEntity.builder()
                        .id(2L)
                        .integrityHash("bbbb")
                        .prevHash("WRONG_HASH")
                        .build();
        AuditEventEntity record1 =
                AuditEventEntity.builder().id(1L).integrityHash("aaaa").prevHash(null).build();

        when(repository.findTopNByOrderByIdDesc(100)).thenReturn(Flux.just(record2, record1));

        StepVerifier.create(auditService.verifyChain(100))
                .expectNextMatches(
                        r ->
                                !r.valid()
                                        && r.brokenAtId().equals(2L)
                                        && "aaaa".equals(r.expectedHash())
                                        && "WRONG_HASH".equals(r.actualHash()))
                .verifyComplete();
    }
}
