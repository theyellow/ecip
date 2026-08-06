package io.emcip.audit.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.service.AuditService;
import io.emcip.common.events.EventSchemas.ModerationFlagEvent;
import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import io.r2dbc.postgresql.codec.Json;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AuditEventConsumerTest {

    @Mock private AuditService auditService;
    @Mock private Acknowledgment acknowledgment;

    private AuditEventConsumer consumer;
    private ObjectMapper objectMapper;

    private static final String TEST_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new AuditEventConsumer(auditService, objectMapper);
    }

    private static void addTenantHeader(ConsumerRecord<?, ?> record) {
        record.headers()
                .add(
                        new RecordHeader(
                                "tenant_id", TEST_TENANT_ID.getBytes(StandardCharsets.UTF_8)));
    }

    // --- handleTelegramMessage ---

    @Test
    void handleTelegramMessage_validEvent_savesAndAcknowledges() throws Exception {
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "evt-001",
                        "2026-04-21T10:00:00Z",
                        null,
                        null,
                        12345L,
                        67890L,
                        "user-1",
                        "USER",
                        "hello world",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        "",
                        null,
                        null,
                        null);
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telegram.messages", 0, 0L, "key", json);
        addTenantHeader(record);

        when(auditService.serializeDetails(any())).thenReturn(Json.of("{\"detail\":\"value\"}"));
        when(auditService.saveWithChain(any(AuditEventEntity.class)))
                .thenReturn(Mono.just(AuditEventEntity.builder().id(1L).build()));

        consumer.handleTelegramMessage(record, acknowledgment);

        verify(auditService).saveWithChain(any(AuditEventEntity.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void malformedRecord_propagatesForErrorHandler_notAcked() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telegram.raw.messages", 0, 0L, "k", "{ not json");
        addTenantHeader(record);

        assertThatThrownBy(() -> consumer.handleTelegramMessage(record, acknowledgment))
                .isInstanceOf(tools.jackson.core.JacksonException.class);

        verify(acknowledgment, never()).acknowledge();
        verify(auditService, never()).saveWithChain(any());
    }

    @Test
    void handleTelegramMessage_saveThrows_doesNotAcknowledgeAndPropagates() throws Exception {
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "evt-002",
                        "2026-04-21T10:00:00Z",
                        null,
                        null,
                        99L,
                        100L,
                        "user-2",
                        "USER",
                        "test",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        "",
                        null,
                        null,
                        null);
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telegram.messages", 0, 2L, "key", json);
        addTenantHeader(record);

        when(auditService.serializeDetails(any())).thenReturn(Json.of("{}"));
        when(auditService.saveWithChain(any(AuditEventEntity.class)))
                .thenReturn(Mono.error(new RuntimeException("DB unavailable")));

        assertThatThrownBy(() -> consumer.handleTelegramMessage(record, acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void handleTelegramMessage_missingTenantHeader_acksAndSkips() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telegram.raw.messages", 0, 0L, "key", "irrelevant-body");
        // Deliberately no addTenantHeader(record) call — exercises the rejection branch.

        assertThatCode(() -> consumer.handleTelegramMessage(record, acknowledgment))
                .doesNotThrowAnyException();

        verify(acknowledgment, times(1)).acknowledge();
        verify(auditService, never()).saveWithChain(any());
    }

    @Test
    void handleTelegramMessage_duplicateEventId_acksAndSkipsWithoutThrowing() throws Exception {
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "evt-dup",
                        "2026-04-21T10:00:00Z",
                        null,
                        null,
                        12345L,
                        67890L,
                        "user-1",
                        "USER",
                        "hello world",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        "",
                        null,
                        null,
                        null);
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telegram.raw.messages", 0, 0L, "key", json);
        addTenantHeader(record);

        when(auditService.serializeDetails(any())).thenReturn(Json.of("{\"detail\":\"value\"}"));
        when(auditService.saveWithChain(any(AuditEventEntity.class)))
                .thenReturn(Mono.error(new org.springframework.dao.DuplicateKeyException("dup")));

        assertThatCode(() -> consumer.handleTelegramMessage(record, acknowledgment))
                .doesNotThrowAnyException();

        verify(acknowledgment).acknowledge();
    }

    /**
     * The duplicate catch is narrowed to DuplicateKeyException (P2.8-F3), so a
     * non-unique-constraint integrity violation — NOT NULL, FK, check — must propagate to the error
     * handler and reach the DLQ rather than being acked away as "already audited".
     */
    @Test
    void handleTelegramMessage_nonDuplicateIntegrityViolation_propagatesAndDoesNotAck()
            throws Exception {
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "evt-integrity",
                        "2026-04-21T10:00:00Z",
                        null,
                        null,
                        12345L,
                        67890L,
                        "user-1",
                        "USER",
                        "hello world",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        "",
                        null,
                        null,
                        null);
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telegram.raw.messages", 0, 0L, "key", json);
        addTenantHeader(record);

        when(auditService.serializeDetails(any())).thenReturn(Json.of("{\"detail\":\"value\"}"));
        when(auditService.saveWithChain(any(AuditEventEntity.class)))
                .thenReturn(
                        Mono.error(
                                new org.springframework.dao.DataIntegrityViolationException(
                                        "null value in column \"event_type\"")));

        assertThatThrownBy(() -> consumer.handleTelegramMessage(record, acknowledgment))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        verify(acknowledgment, never()).acknowledge();
    }

    // --- handleIntentClassified ---

    @Test
    void handleIntentClassified_validEvent_savesWithCorrectSourceService() throws Exception {
        io.emcip.common.events.EventSchemas.IntentClassifiedEvent event =
                new io.emcip.common.events.EventSchemas.IntentClassifiedEvent(
                        "cls-001",
                        "2026-05-19T10:00:00Z",
                        null,
                        null,
                        "evt-001",
                        "GREETING",
                        0.95,
                        null,
                        java.util.List.of("greeting-rule"));
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("messages.classified", 0, 0L, "key", json);
        addTenantHeader(record);

        when(auditService.serializeDetails(any())).thenReturn(Json.of("{\"detail\":\"value\"}"));
        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        when(auditService.saveWithChain(captor.capture()))
                .thenReturn(Mono.just(AuditEventEntity.builder().id(3L).build()));

        consumer.handleIntentClassified(record, acknowledgment);

        assertThat(captor.getValue().getSourceService()).isEqualTo("emcip-intent-classifier");
        assertThat(captor.getValue().getResourceType()).isEqualTo("Intent");
        assertThat(captor.getValue().getResourceId()).isEqualTo("evt-001");
        assertThat(captor.getValue().getActorId()).isNull();
        verify(acknowledgment).acknowledge();
    }

    // --- correlationId ---

    @Test
    void handleTelegramMessage_setsCorrelationIdToOwnEventId() throws Exception {
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "evt-root",
                        "2026-06-17T10:00:00Z",
                        null,
                        null,
                        1L,
                        100L,
                        "user-1",
                        "USER",
                        "hello",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        "",
                        null,
                        null,
                        null);
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telegram.raw.messages", 0, 0L, "key", json);
        addTenantHeader(record);
        when(auditService.serializeDetails(any())).thenReturn(Json.of("{}"));
        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        when(auditService.saveWithChain(captor.capture()))
                .thenReturn(Mono.just(AuditEventEntity.builder().id(1L).build()));

        consumer.handleTelegramMessage(record, acknowledgment);

        assertThat(captor.getValue().getCorrelationId()).isEqualTo("evt-root");
    }

    @Test
    void handleIntentClassified_setsCorrelationIdToSourceEventId() throws Exception {
        io.emcip.common.events.EventSchemas.IntentClassifiedEvent event =
                new io.emcip.common.events.EventSchemas.IntentClassifiedEvent(
                        "cls-001",
                        "2026-06-17T10:00:00Z",
                        null,
                        null,
                        "evt-root", // sourceEventId — this should become correlationId
                        "SPAM",
                        0.95,
                        null,
                        java.util.List.of("SPAM"));
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("messages.classified", 0, 0L, "key", json);
        addTenantHeader(record);
        when(auditService.serializeDetails(any())).thenReturn(Json.of("{}"));
        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        when(auditService.saveWithChain(captor.capture()))
                .thenReturn(Mono.just(AuditEventEntity.builder().id(2L).build()));

        consumer.handleIntentClassified(record, acknowledgment);

        assertThat(captor.getValue().getCorrelationId()).isEqualTo("evt-root");
    }

    // --- handleModerationFlag ---

    @Test
    void handleModerationFlag_validEvent_savesWithCorrectSourceService() throws Exception {
        ModerationFlagEvent event =
                new ModerationFlagEvent(
                        "flag-001",
                        "2026-04-21T10:00:00Z",
                        null,
                        null,
                        "evt-001",
                        "KEYWORD",
                        "HIGH",
                        "Rule matched: keyword-spam",
                        Map.of("action", "FLAG", "ruleName", "keyword-spam"));
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("moderation.flags", 0, 0L, "key", json);
        addTenantHeader(record);

        when(auditService.serializeDetails(any())).thenReturn(Json.of("{\"detail\":\"value\"}"));
        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        when(auditService.saveWithChain(captor.capture()))
                .thenReturn(Mono.just(AuditEventEntity.builder().id(2L).build()));

        consumer.handleModerationFlag(record, acknowledgment);

        verify(auditService).saveWithChain(any(AuditEventEntity.class));
        assertThat(captor.getValue().getSourceService()).isEqualTo("emcip-moderation-service");
        assertThat(captor.getValue().getEventId()).isEqualTo("flag-001");
        verify(acknowledgment).acknowledge();
    }
}
