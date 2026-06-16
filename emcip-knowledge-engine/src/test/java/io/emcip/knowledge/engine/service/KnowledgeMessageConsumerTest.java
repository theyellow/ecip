package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KnowledgeMessageConsumerTest {

    @Mock private KnowledgeExtractionService extractionService;
    @Mock private KnowledgeEventPublisher eventPublisher;

    private KnowledgeMessageConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new KnowledgeMessageConsumer(extractionService, eventPublisher, objectMapper);
    }

    @Test
    void shouldProcessTelegramMessageEvent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String eventJson =
                """
                {
                  "eventId": "evt-1",
                  "timestamp": "2026-06-13T10:00:00Z",
                  "schemaVersion": "1.0.0",
                  "eventType": "TelegramMessage",
                  "telegramMessageId": 42,
                  "chatId": 100,
                  "senderId": "999",
                  "senderType": "USER",
                  "text": "AI is transforming everything",
                  "date": 1718272800,
                  "isOutgoing": false,
                  "senderDisplayName": "TestUser",
                  "chatTitle": "TestGroup"
                }
                """;

        var record = new ConsumerRecord<>("knowledge.raw.messages", 0, 0L, "100", eventJson);
        record.headers().add("tenant_id", tenantId.toString().getBytes());

        consumer.consume(record);

        verify(extractionService)
                .processMessage(
                        eq("AI is transforming everything"),
                        eq("tg:100:42"),
                        eq(tenantId),
                        eq(100L),
                        eq("999"),
                        eq("TestUser"),
                        eq("TestGroup"),
                        eq(1718272800));
    }

    @Test
    void shouldPropagateExceptionFromExtractionService() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String eventJson =
                """
                {
                  "eventId": "evt-err",
                  "timestamp": "2026-06-16T10:00:00Z",
                  "schemaVersion": "1.0.0",
                  "eventType": "TelegramMessage",
                  "telegramMessageId": 99,
                  "chatId": 200,
                  "senderId": "111",
                  "senderType": "USER",
                  "text": "trigger failure",
                  "date": 1718272800,
                  "isOutgoing": false,
                  "senderDisplayName": "FailUser",
                  "chatTitle": "FailGroup"
                }
                """;
        var record = new ConsumerRecord<>("knowledge.raw.messages", 0, 0L, "200", eventJson);
        record.headers().add("tenant_id", tenantId.toString().getBytes());

        doThrow(new RuntimeException("LLM failure"))
                .when(extractionService)
                .processMessage(any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("LLM failure");
    }
}
