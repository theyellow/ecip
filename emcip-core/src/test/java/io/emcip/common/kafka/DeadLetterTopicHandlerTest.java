package io.emcip.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DeadLetterTopicHandlerTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaMetricsConfig metricsConfig;
    private DeadLetterTopicHandler handler;

    @BeforeEach
    void setUp() {
        metricsConfig = new KafkaMetricsConfig(new SimpleMeterRegistry());
        handler = new DeadLetterTopicHandler(kafkaTemplate, new ObjectMapper(), metricsConfig);
    }

    private ConsumerRecord<String, String> record(String topic) {
        return new ConsumerRecord<>(topic, 0, 0L, "key-1", "value-1");
    }

    @Test
    void sendToDeadLetterQueue_sendsToCorrectDlqTopic() {
        var record = record("my.topic");

        handler.sendToDeadLetterQueue(record, "error msg", 2, "my-group");

        verify(kafkaTemplate).send(eq("my.topic.dlq"), eq("key-1"), anyString());
    }

    @Test
    void sendToDeadLetterQueue_serializedPayloadContainsErrorMessage() throws Exception {
        var record = record("test.topic");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        handler.sendToDeadLetterQueue(record, "something went wrong", 1, "g");

        verify(kafkaTemplate).send(anyString(), anyString(), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("something went wrong");
        assertThat(payload).contains("test.topic");
    }

    @Test
    void sendToDeadLetterQueue_withException_includesClassNameInError() throws Exception {
        var record = record("ex.topic");
        var exception = new IllegalArgumentException("bad argument");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        handler.sendToDeadLetterQueue(record, exception, 3, "g");

        verify(kafkaTemplate).send(eq("ex.topic.dlq"), anyString(), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("IllegalArgumentException");
        assertThat(payloadCaptor.getValue()).contains("bad argument");
    }

    @Test
    void sendToDeadLetterQueue_incrementsDlqCounter() {
        var registry = new SimpleMeterRegistry();
        var metrics = new KafkaMetricsConfig(registry);
        var h = new DeadLetterTopicHandler(kafkaTemplate, new ObjectMapper(), metrics);

        h.sendToDeadLetterQueue(record("counted.topic"), "err", 0, "grp");

        Counter counter = registry.find("kafka.messages.deadletter").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void shouldRetry_trueWhenBelowMax() {
        assertThat(handler.shouldRetry(0, 3)).isTrue();
        assertThat(handler.shouldRetry(2, 3)).isTrue();
    }

    @Test
    void shouldRetry_falseWhenAtOrAboveMax() {
        assertThat(handler.shouldRetry(3, 3)).isFalse();
        assertThat(handler.shouldRetry(5, 3)).isFalse();
    }

    @Test
    void getRetryCount_returnsZero() {
        var record = record("any.topic");
        assertThat(handler.getRetryCount(record)).isEqualTo(0);
    }

    @Test
    void deadLetterEvent_recordAccessors() {
        var event =
                new DeadLetterTopicHandler.DeadLetterEvent(
                        "id1", "ts", "original.topic", 0, 100L, "k", "v", "err", 2, "grp", "st");

        assertThat(event.eventId()).isEqualTo("id1");
        assertThat(event.originalTopic()).isEqualTo("original.topic");
        assertThat(event.errorMessage()).isEqualTo("err");
        assertThat(event.retryCount()).isEqualTo(2);
    }
}
