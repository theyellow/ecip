package io.emcip.conversation.context.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.TenantContext;
import io.emcip.common.validation.EventValidator;
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
class IntentClassificationConsumerTest {

    @Mock private ConversationContextService contextService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IntentClassificationConsumer consumer;

    @BeforeEach
    void setUp() {
        EventValidator eventValidator = new EventValidator(objectMapper);
        consumer = new IntentClassificationConsumer(objectMapper, eventValidator, contextService);
    }

    private ConsumerRecord<String, String> record(String json, UUID tenantId) {
        ConsumerRecord<String, String> r =
                new ConsumerRecord<>("messages.classified", 0, 0L, "1", json);
        if (tenantId != null) {
            r.headers()
                    .add(
                            TenantContext.KAFKA_HEADER,
                            tenantId.toString().getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    private String validIntentClassifiedJson() {
        return """
               {"eventId":"e2222222-2222-2222-2222-222222222222",
                "timestamp":"2026-01-01T00:00:00Z",
                "schemaVersion":"1.0.0",
                "eventType":"IntentClassified",
                "sourceEventId":"src-1",
                "intent":"GREETING",
                "confidence":0.87}
               """;
    }

    @Test
    void consume_rejectsRecordWithoutTenantHeader() {
        consumer.consume(record(validIntentClassifiedJson(), null));

        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> verifyNoInteractions(contextService));
    }

    @Test
    void consume_ignoresMalformedJsonWithoutThrowing() {
        consumer.consume(record("{not json", UUID.randomUUID()));

        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> verifyNoInteractions(contextService));
    }

    @Test
    void consume_ignoresEventMissingRequiredIntentFields() {
        // Structurally valid JSON but missing fields required by the IntentClassified schema
        // (sourceEventId, intent, confidence) — validation must reject it.
        String wrongShapeJson =
                """
                {"eventId":"e5555555-5555-5555-5555-555555555555",
                 "timestamp":"2026-01-01T00:00:00Z",
                 "schemaVersion":"1.0.0",
                 "eventType":"TelegramMessage",
                 "telegramMessageId":999,
                 "chatId":100,
                 "senderId":"555",
                 "text":"hi",
                 "date":1700000000}
                """;

        consumer.consume(record(wrongShapeJson, UUID.randomUUID()));

        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> verifyNoInteractions(contextService));
    }

    @Test
    void consume_happyPath_updatesIntentClassification() {
        consumer.consume(record(validIntentClassifiedJson(), UUID.randomUUID()));

        ArgumentCaptor<EventSchemas.IntentClassifiedEvent> captor =
                ArgumentCaptor.forClass(EventSchemas.IntentClassifiedEvent.class);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> verify(contextService).updateIntentClassification(captor.capture()));

        EventSchemas.IntentClassifiedEvent parsed = captor.getValue();
        assertThat(parsed.sourceEventId()).isEqualTo("src-1");
        assertThat(parsed.intent()).isEqualTo("GREETING");
        assertThat(parsed.confidence()).isEqualTo(0.87);
    }
}
