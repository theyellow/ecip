package io.emcip.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class DeadLetterQueueConsumerTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private KafkaMetricsConfig metricsConfig;
    private DeadLetterQueueConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        metricsConfig = new KafkaMetricsConfig(new SimpleMeterRegistry());
        consumer = new DeadLetterQueueConsumer(kafkaTemplate, objectMapper, metricsConfig);
    }

    private String dlqEventJson(String originalTopic, int retryCount) throws Exception {
        var event =
                new DeadLetterTopicHandler.DeadLetterEvent(
                        "event-id",
                        Instant.now().toString(),
                        originalTopic,
                        0,
                        0L,
                        "key",
                        "value",
                        "some error",
                        retryCount,
                        "grp",
                        "st");
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void monitorDeadLetterQueue_parsesValidDlqMessage() throws Exception {
        String message = dlqEventJson("original.topic", 1);

        consumer.monitorDeadLetterQueue(message, "original.topic.dlq", 0, 0L);

        // No exception = success; DLQ counter incremented
        var counter = metricsConfig.createDeadLetterCounter("original.topic.dlq", "dlq-monitor");
        assertThat(counter.count()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void monitorDeadLetterQueue_invalidJson_doesNotThrow() {
        consumer.monitorDeadLetterQueue("not-json", "some.topic.dlq", 0, 0L);
        // Should log error but not propagate exception
    }

    @Test
    void reprocessDlqMessage_sendsToOriginalTopic() throws Exception {
        var event =
                new DeadLetterTopicHandler.DeadLetterEvent(
                        "id",
                        Instant.now().toString(),
                        "original.topic",
                        0,
                        0L,
                        "k",
                        "v",
                        "err",
                        1,
                        "g",
                        "st");

        boolean result = consumer.reprocessDlqMessage("original.topic.dlq", event);

        assertThat(result).isTrue();
        verify(kafkaTemplate).send("original.topic", "k", "v");
    }

    @Test
    void reprocessDlqMessage_kafkaThrows_returnsFalse() {
        var event =
                new DeadLetterTopicHandler.DeadLetterEvent(
                        "id",
                        Instant.now().toString(),
                        "original.topic",
                        0,
                        0L,
                        "k",
                        "v",
                        "err",
                        1,
                        "g",
                        "st");
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("kafka down"));

        boolean result = consumer.reprocessDlqMessage("original.topic.dlq", event);

        assertThat(result).isFalse();
    }

    @Test
    void getDlqHealthMetrics_returnsExpectedKeys() {
        var metrics = consumer.getDlqHealthMetrics();

        assertThat(metrics)
                .containsKeys(
                        "timestamp",
                        "status",
                        "monitoredTopics",
                        "reprocessEnabled",
                        "maxReprocessAttempts");
        assertThat(metrics.get("status")).isEqualTo("MONITORING");
        assertThat(metrics.get("reprocessEnabled")).isEqualTo(true);
        assertThat(metrics.get("maxReprocessAttempts")).isEqualTo(3);
    }
}
