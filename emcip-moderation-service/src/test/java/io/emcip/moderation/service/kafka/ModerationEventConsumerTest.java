package io.emcip.moderation.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import io.emcip.moderation.service.service.RuleEvaluationService;
import io.emcip.moderation.service.service.RuleEvaluationService.EvaluationResult;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ModerationEventConsumerTest {

    @Mock private RuleEvaluationService ruleEvaluationService;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private Acknowledgment acknowledgment;

    private ModerationEventConsumer consumer;
    private ObjectMapper objectMapper;

    private static final String TENANT_ID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        consumer = new ModerationEventConsumer(ruleEvaluationService, kafkaTemplate);
        objectMapper = new ObjectMapper();
    }

    private ConsumerRecord<String, String> toRecord(String key, String value) {
        return new ConsumerRecord<>("telegram.raw.messages", 0, 0L, key, value);
    }

    @Test
    void consume_messageMatchingKeywordRule_sendsModificationFlagAndAcknowledges()
            throws Exception {
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "evt-001",
                        "2026-04-21T10:00:00Z",
                        null,
                        null,
                        100L,
                        200L,
                        "user-1",
                        "USER",
                        "this message contains spam",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        null);
        String message = objectMapper.writeValueAsString(event);

        EvaluationResult matchResult =
                new EvaluationResult("keyword-spam", "HIGH", "FLAG", "KEYWORD");
        when(ruleEvaluationService.evaluate(eq("this message contains spam"), any()))
                .thenReturn(Optional.of(matchResult));

        consumer.consume(toRecord("evt-001", message), acknowledgment);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        assertThat(recordCaptor.getValue().topic()).isEqualTo("moderation.flags");
        assertThat(recordCaptor.getValue().key()).isEqualTo("evt-001");
        assertThat(recordCaptor.getValue().value()).contains("ModerationFlag");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_messageMatchingNoRules_doesNotSendAndAcknowledges() throws Exception {
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "evt-002",
                        "2026-04-21T10:00:00Z",
                        null,
                        null,
                        101L,
                        200L,
                        "user-2",
                        "USER",
                        "a perfectly clean message",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        null);
        String message = objectMapper.writeValueAsString(event);

        when(ruleEvaluationService.evaluate(eq("a perfectly clean message"), any()))
                .thenReturn(Optional.empty());

        consumer.consume(toRecord("evt-002", message), acknowledgment);

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_malformedJson_propagatesException() {
        String badMessage = "{ not valid json %%% }";

        assertThatThrownBy(() -> consumer.consume(toRecord("bad-key", badMessage), acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void consume_kafkaTemplateSendFails_propagatesExceptionWithoutAck() throws Exception {
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "evt-003",
                        "2026-04-21T10:00:00Z",
                        null,
                        null,
                        102L,
                        200L,
                        "user-3",
                        "USER",
                        "spam content here",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        null);
        String message = objectMapper.writeValueAsString(event);

        EvaluationResult matchResult =
                new EvaluationResult("keyword-spam", "HIGH", "FLAG", "KEYWORD");
        when(ruleEvaluationService.evaluate(eq("spam content here"), any()))
                .thenReturn(Optional.of(matchResult));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new RuntimeException("Kafka unavailable"));

        assertThatThrownBy(() -> consumer.consume(toRecord("evt-003", message), acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(acknowledgment, never()).acknowledge();
    }
}
