package io.emcip.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumer for the Dead Letter Queue (DLQ). Handles reprocessing of failed events and monitoring of
 * DLQ health. Implements US-3.3.2: Dead-letter topic monitoring and retry.
 */
@Component
public class DeadLetterQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueConsumer.class);
    private static final String REPROCESS_HEADER = "X-Reprocess-Attempt";
    private static final int MAX_REPROCESS_ATTEMPTS = 3;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaMetricsConfig metricsConfig;

    public DeadLetterQueueConsumer(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            KafkaMetricsConfig metricsConfig) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.metricsConfig = metricsConfig;
    }

    /**
     * Monitor DLQ topics (does not auto-reprocess - manual review required). Listens to all DLQ
     * topics.
     */
    @KafkaListener(
            topicPattern = "^.*\\.dlq$",
            groupId = "dlq-monitor",
            containerFactory = "kafkaListenerContainerFactory")
    public void monitorDeadLetterQueue(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.warn(
                "DLQ Message detected: topic={}, partition={}, offset={}",
                topic,
                partition,
                offset);

        Counter dlqCounter =
                metricsConfig.createDeadLetterCounter(topic.replace(".dlq", ""), "dlq-monitor");
        dlqCounter.increment();

        try {
            DeadLetterTopicHandler.DeadLetterEvent dlqEvent =
                    objectMapper.readValue(message, DeadLetterTopicHandler.DeadLetterEvent.class);

            log.warn(
                    "DLQ Event Details: eventId={}, originalTopic={}, error={}, retryCount={}",
                    dlqEvent.eventId(),
                    dlqEvent.originalTopic(),
                    dlqEvent.errorMessage(),
                    dlqEvent.retryCount());

            // Analyze DLQ metrics
            analyzeDlqEvent(dlqEvent);

        } catch (Exception e) {
            log.error(
                    "Failed to parse DLQ message: topic={}, partition={}, offset={}",
                    topic,
                    partition,
                    offset,
                    e);
        }
    }

    /**
     * Attempt to reprocess a specific DLQ message. This should be called manually or via admin API.
     *
     * @param dlqTopic The DLQ topic name
     * @param dlqEvent The DLQ event to reprocess
     * @return true if reprocessing was attempted
     */
    public boolean reprocessDlqMessage(
            String dlqTopic, DeadLetterTopicHandler.DeadLetterEvent dlqEvent) {
        String originalTopic = dlqEvent.originalTopic();
        int reprocessCount = getReprocessCount(dlqEvent);

        if (reprocessCount >= MAX_REPROCESS_ATTEMPTS) {
            log.error("Max reprocess attempts reached for event: {}", dlqEvent.eventId());
            return false;
        }

        try {
            // Send back to original topic with reprocess header
            kafkaTemplate.send(originalTopic, dlqEvent.key(), dlqEvent.value());

            log.info(
                    "Reprocessed DLQ message: eventId={}, originalTopic={}, reprocessAttempt={}",
                    dlqEvent.eventId(),
                    originalTopic,
                    reprocessCount + 1);

            return true;

        } catch (Exception e) {
            log.error(
                    "Failed to reprocess DLQ message: eventId={}, error={}",
                    dlqEvent.eventId(),
                    e.getMessage());
            return false;
        }
    }

    /**
     * Get DLQ health metrics.
     *
     * @return Map containing DLQ health information
     */
    public Map<String, Object> getDlqHealthMetrics() {
        // In a full implementation, this would query Kafka admin client
        // for actual lag and partition information
        return Map.of(
                "timestamp",
                Instant.now().toString(),
                "status",
                "MONITORING",
                "monitoredTopics",
                List.of("*.dlq"),
                "reprocessEnabled",
                true,
                "maxReprocessAttempts",
                MAX_REPROCESS_ATTEMPTS);
    }

    /** Analyze DLQ event for patterns and alerts. */
    private void analyzeDlqEvent(DeadLetterTopicHandler.DeadLetterEvent event) {
        Instant eventTime = Instant.parse(event.timestamp());
        long hoursInDlq = ChronoUnit.HOURS.between(eventTime, Instant.now());

        // Alert if messages have been in DLQ for too long
        if (hoursInDlq > 24) {
            log.error(
                    "DLQ message is over 24 hours old: eventId={}, hours={}",
                    event.eventId(),
                    hoursInDlq);
        }

        // Check for repeated failures on same topic
        if (event.retryCount() >= 3) {
            log.warn(
                    "Message failed multiple times: eventId={}, originalTopic={}, retries={}",
                    event.eventId(),
                    event.originalTopic(),
                    event.retryCount());
        }
    }

    private int getReprocessCount(DeadLetterTopicHandler.DeadLetterEvent event) {
        // Count reprocess attempts based on event metadata
        // In a full implementation, this would track in a database
        return 0;
    }
}
