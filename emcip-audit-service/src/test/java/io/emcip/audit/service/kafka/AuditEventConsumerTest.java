package io.emcip.audit.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.service.AuditService;
import io.emcip.common.events.EventSchemas.ModerationFlagEvent;
import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import io.r2dbc.postgresql.codec.Json;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new AuditEventConsumer(auditService, objectMapper);
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
                        "");
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telegram.messages", 0, 0L, "key", json);

        when(auditService.serializeDetails(any())).thenReturn(Json.of("{\"detail\":\"value\"}"));
        when(auditService.save(any(AuditEventEntity.class)))
                .thenReturn(Mono.just(AuditEventEntity.builder().id(1L).build()));

        consumer.handleTelegramMessage(record, acknowledgment);

        verify(auditService).save(any(AuditEventEntity.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void handleTelegramMessage_malformedJson_skipsAndAcknowledges() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telegram.messages", 0, 1L, "key", "{ not valid json %%% }");

        consumer.handleTelegramMessage(record, acknowledgment);

        verify(auditService, never()).save(any());
        verify(acknowledgment).acknowledge();
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
                        "");
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telegram.messages", 0, 2L, "key", json);

        when(auditService.serializeDetails(any())).thenReturn(Json.of("{}"));
        when(auditService.save(any(AuditEventEntity.class)))
                .thenReturn(Mono.error(new RuntimeException("DB unavailable")));

        assertThatThrownBy(() -> consumer.handleTelegramMessage(record, acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(acknowledgment, never()).acknowledge();
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

        when(auditService.serializeDetails(any())).thenReturn(Json.of("{\"detail\":\"value\"}"));
        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        when(auditService.save(captor.capture()))
                .thenReturn(Mono.just(AuditEventEntity.builder().id(2L).build()));

        consumer.handleModerationFlag(record, acknowledgment);

        verify(auditService).save(any(AuditEventEntity.class));
        assertThat(captor.getValue().getSourceService()).isEqualTo("emcip-moderation-service");
        assertThat(captor.getValue().getEventId()).isEqualTo("flag-001");
        verify(acknowledgment).acknowledge();
    }
}
