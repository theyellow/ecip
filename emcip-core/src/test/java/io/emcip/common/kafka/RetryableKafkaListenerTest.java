package io.emcip.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RetryableKafkaListenerTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaMetricsConfig metricsConfig;
    private DeadLetterTopicHandler dlqHandler;

    @BeforeEach
    void setUp() {
        metricsConfig = new KafkaMetricsConfig(new SimpleMeterRegistry());
        dlqHandler = new DeadLetterTopicHandler(kafkaTemplate, new ObjectMapper(), metricsConfig);
    }

    private ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("test.topic", 0, 0L, "k", "v");
    }

    @Test
    void processWithRetry_successOnFirstAttempt_doesNotSendToDlq() {
        var listener =
                new RetryableKafkaListener(dlqHandler, metricsConfig, 3, 0) {
                    @Override
                    protected void processMessage(ConsumerRecord<String, String> record) {}
                };

        listener.processWithRetry(record(), "test-group");

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void processWithRetry_failsAllAttempts_sendsToDlq() {
        var listener =
                new RetryableKafkaListener(dlqHandler, metricsConfig, 2, 0) {
                    @Override
                    protected void processMessage(ConsumerRecord<String, String> record)
                            throws Exception {
                        throw new RuntimeException("processing error");
                    }
                };

        listener.processWithRetry(record(), "test-group");

        verify(kafkaTemplate).send(eq("test.topic.dlq"), anyString(), anyString());
    }

    @Test
    void processWithRetry_succeedsOnRetry_doesNotSendToDlq() {
        var callCount = new int[] {0};
        var listener =
                new RetryableKafkaListener(dlqHandler, metricsConfig, 3, 0) {
                    @Override
                    protected void processMessage(ConsumerRecord<String, String> record)
                            throws Exception {
                        callCount[0]++;
                        if (callCount[0] < 2) {
                            throw new RuntimeException("transient error");
                        }
                    }
                };

        listener.processWithRetry(record(), "test-group");

        verifyNoInteractions(kafkaTemplate);
        assertThat(callCount[0]).isEqualTo(2);
    }

    @Test
    void processWithRetry_defaultConstructor_usesThreeMaxRetries() {
        var callCount = new int[] {0};
        var listener =
                new RetryableKafkaListener(dlqHandler, metricsConfig) {
                    @Override
                    protected void processMessage(ConsumerRecord<String, String> record)
                            throws Exception {
                        callCount[0]++;
                        throw new RuntimeException("always fails");
                    }
                };

        listener.processWithRetry(record(), "test-group");

        // initial attempt + 3 retries = 4 total calls
        assertThat(callCount[0]).isEqualTo(4);
        verify(kafkaTemplate).send(eq("test.topic.dlq"), anyString(), anyString());
    }

    @Test
    void processWithRetry_zeroMaxRetries_sendsDirectlyToDlqOnFirstFailure() {
        var callCount = new int[] {0};
        var listener =
                new RetryableKafkaListener(dlqHandler, metricsConfig, 0, 0) {
                    @Override
                    protected void processMessage(ConsumerRecord<String, String> record)
                            throws Exception {
                        callCount[0]++;
                        throw new RuntimeException("immediate fail");
                    }
                };

        listener.processWithRetry(record(), "g");

        assertThat(callCount[0]).isEqualTo(1);
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }
}
