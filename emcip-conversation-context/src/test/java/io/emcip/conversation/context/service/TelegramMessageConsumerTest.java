package io.emcip.conversation.context.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.TenantContext;
import io.emcip.common.validation.EventValidator;
import io.emcip.conversation.context.entity.Message;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class TelegramMessageConsumerTest {

    @Mock private ConversationContextService contextService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TelegramMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        EventValidator eventValidator = new EventValidator(objectMapper);
        consumer = new TelegramMessageConsumer(objectMapper, eventValidator, contextService);
    }

    private ConsumerRecord<String, String> record(String json, UUID tenantId) {
        ConsumerRecord<String, String> r =
                new ConsumerRecord<>("telegram.raw.messages", 0, 0L, "1", json);
        if (tenantId != null) {
            r.headers()
                    .add(
                            TenantContext.KAFKA_HEADER,
                            tenantId.toString().getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    private String validTelegramMessageJson() {
        return """
               {"eventId":"e1111111-1111-1111-1111-111111111111",
                "timestamp":"2026-01-01T00:00:00Z",
                "schemaVersion":"1.0.0",
                "eventType":"TelegramMessage",
                "telegramMessageId":999,
                "chatId":100,
                "senderId":"555",
                "senderType":"USER",
                "text":"hi there",
                "date":1700000000}
               """;
    }

    @Test
    void consume_rejectsRecordWithoutTenantHeader() {
        consumer.consume(record(validTelegramMessageJson(), null));

        verifyNoInteractions(contextService);
    }

    @Test
    void consume_ignoresMalformedJsonWithoutThrowing() {
        consumer.consume(record("{not json", UUID.randomUUID()));

        verifyNoInteractions(contextService);
    }

    @Test
    void consume_ignoresEventMissingRequiredTelegramFields() {
        // Structurally valid JSON but missing fields required by the TelegramMessage schema
        // (telegramMessageId, chatId, senderId, text, date) — validation must reject it.
        String wrongShapeJson =
                """
                {"eventId":"e4444444-4444-4444-4444-444444444444",
                 "timestamp":"2026-01-01T00:00:00Z",
                 "schemaVersion":"1.0.0",
                 "eventType":"IntentClassified",
                 "sourceEventId":"src-1",
                 "intent":"GREETING",
                 "confidence":0.9}
                """;

        consumer.consume(record(wrongShapeJson, UUID.randomUUID()));

        verifyNoInteractions(contextService);
    }

    @Test
    void consume_happyPath_persistsParsedEvent() {
        Message saved = new Message();
        saved.setEventId("e1111111-1111-1111-1111-111111111111");
        when(contextService.processTelegramMessage(any())).thenReturn(saved);

        consumer.consume(record(validTelegramMessageJson(), UUID.randomUUID()));

        ArgumentCaptor<EventSchemas.TelegramMessageEvent> captor =
                ArgumentCaptor.forClass(EventSchemas.TelegramMessageEvent.class);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> verify(contextService).processTelegramMessage(captor.capture()));

        EventSchemas.TelegramMessageEvent parsed = captor.getValue();
        assertThat(parsed.eventId()).isEqualTo("e1111111-1111-1111-1111-111111111111");
        assertThat(parsed.chatId()).isEqualTo(100L);
        assertThat(parsed.senderId()).isEqualTo("555");
        assertThat(parsed.text()).isEqualTo("hi there");
        assertThat(parsed.telegramMessageId()).isEqualTo(999L);
    }
}
