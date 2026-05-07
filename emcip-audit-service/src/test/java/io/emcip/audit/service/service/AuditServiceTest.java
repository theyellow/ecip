package io.emcip.audit.service.service;

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
}
