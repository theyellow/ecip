package io.emcip.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Handler for dead letter topic (DLQ) operations. Manages failed events by sending them to DLQ with
 * metadata about the failure. Implements US-3.3.2: Dead-letter topic handling for failed events.
 */
@Component
public class DeadLetterTopicHandler {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterTopicHandler.class);
    private static final String DLQ_SUFFIX = ".dlq";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaMetricsConfig metricsConfig;

    public DeadLetterTopicHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            KafkaMetricsConfig metricsConfig) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.metricsConfig = metricsConfig;
    }

    /**
     * Send a failed message to the dead letter queue.
     *
     * @param record The original consumer record that failed processing
     * @param errorMessage Description of the error
     * @param retryCount Number of retry attempts made
     * @param consumerGroup The consumer group ID
     */
    public void sendToDeadLetterQueue(
            ConsumerRecord<String, String> record,
            String errorMessage,
            int retryCount,
            String consumerGroup) {

        String dlqTopic = record.topic() + DLQ_SUFFIX;

        try {
            DeadLetterEvent dlqEvent =
                    new DeadLetterEvent(
                            UUID.randomUUID().toString(),
                            Instant.now().toString(),
                            record.topic(),
                            record.partition(),
                            record.offset(),
                            record.key(),
                            record.value(),
                            errorMessage,
                            retryCount,
                            consumerGroup,
                            getStackTrace());

            String dlqMessage = objectMapper.writeValueAsString(dlqEvent);
            kafkaTemplate.send(dlqTopic, record.key(), dlqMessage);

            // Record metric
            Counter dlqCounter =
                    metricsConfig.createDeadLetterCounter(record.topic(), consumerGroup);
            dlqCounter.increment();

            log.warn(
                    "Sent message to DLQ: topic={}, dlqTopic={}, partition={}, offset={}, error={}",
                    record.topic(),
                    dlqTopic,
                    record.partition(),
                    record.offset(),
                    errorMessage);

        } catch (Exception e) {
            log.error(
                    "Failed to send message to DLQ: topic={}, partition={}, offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    e);
        }
    }

    /** Send a failed message to the dead letter queue with exception details. */
    public void sendToDeadLetterQueue(
            ConsumerRecord<String, String> record,
            Exception exception,
            int retryCount,
            String consumerGroup) {

        String errorMessage = exception.getMessage();
        String fullError = exception.getClass().getName() + ": " + errorMessage;
        sendToDeadLetterQueue(record, fullError, retryCount, consumerGroup);
    }

    /**
     * Determine if a message should be retried based on retry count.
     *
     * @param retryCount Current retry count
     * @param maxRetries Maximum allowed retries
     * @return true if message should be retried
     */
    public boolean shouldRetry(int retryCount, int maxRetries) {
        return retryCount < maxRetries;
    }

    /** Extract retry count from message headers if available. */
    public int getRetryCount(ConsumerRecord<String, String> record) {
        // Check for retry count in headers (if implemented in the future)
        // For now, return 0 as default
        return 0;
    }

    private String getStackTrace() {
        // Get truncated stack trace for context
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i < Math.min(8, stackTrace.length); i++) {
            sb.append(stackTrace[i].toString()).append("\n");
        }
        return sb.toString();
    }

    /** Record for dead letter events. */
    public record DeadLetterEvent(
            String eventId,
            String timestamp,
            String originalTopic,
            int partition,
            long offset,
            String key,
            String value,
            String errorMessage,
            int retryCount,
            String consumerGroup,
            String stackTrace) {}
}
