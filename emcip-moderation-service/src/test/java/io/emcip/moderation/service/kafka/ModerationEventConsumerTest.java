package io.emcip.moderation.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import io.emcip.moderation.service.service.RuleEvaluationService;
import io.emcip.moderation.service.service.RuleEvaluationService.EvaluationResult;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class ModerationEventConsumerTest {

    @Mock private RuleEvaluationService ruleEvaluationService;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private Acknowledgment acknowledgment;

    private ModerationEventConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        consumer = new ModerationEventConsumer(ruleEvaluationService, kafkaTemplate);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
        when(ruleEvaluationService.evaluate("this message contains spam"))
                .thenReturn(Optional.of(matchResult));

        consumer.consume(message, acknowledgment);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate)
                .send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("moderation.flags");
        assertThat(keyCaptor.getValue()).isEqualTo("evt-001");
        assertThat(valueCaptor.getValue()).contains("ModerationFlag");
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

        when(ruleEvaluationService.evaluate("a perfectly clean message"))
                .thenReturn(Optional.empty());

        consumer.consume(message, acknowledgment);

        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_malformedJson_propagatesException() {
        String badMessage = "{ not valid json %%% }";

        assertThatThrownBy(() -> consumer.consume(badMessage, acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(kafkaTemplate, never()).send(any(), any(), any());
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
        when(ruleEvaluationService.evaluate("spam content here"))
                .thenReturn(Optional.of(matchResult));
        when(kafkaTemplate.send(eq("moderation.flags"), any(), any()))
                .thenThrow(new RuntimeException("Kafka unavailable"));

        assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(acknowledgment, never()).acknowledge();
    }
}
