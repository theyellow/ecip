package io.emcip.common.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for retryable Kafka listeners. Provides automatic retry logic and dead letter
 * queue integration. Implements US-3.3.2: Dead-letter topic handling with retry logic.
 */
public abstract class RetryableKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(RetryableKafkaListener.class);

    private final DeadLetterTopicHandler dlqHandler;
    private final KafkaMetricsConfig metricsConfig;
    private final int maxRetries;
    private final long retryDelayMs;

    protected RetryableKafkaListener(
            DeadLetterTopicHandler dlqHandler,
            KafkaMetricsConfig metricsConfig,
            int maxRetries,
            long retryDelayMs) {
        this.dlqHandler = dlqHandler;
        this.metricsConfig = metricsConfig;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    protected RetryableKafkaListener(
            DeadLetterTopicHandler dlqHandler, KafkaMetricsConfig metricsConfig) {
        this(dlqHandler, metricsConfig, 3, 1000);
    }

    /**
     * Process a Kafka message with retry logic.
     *
     * @param record The Kafka consumer record
     * @param consumerGroup The consumer group ID
     */
    protected void processWithRetry(ConsumerRecord<String, String> record, String consumerGroup) {
        String topic = record.topic();
        int partition = record.partition();
        long offset = record.offset();

        Counter consumedCounter = metricsConfig.createConsumedCounter(topic, consumerGroup);
        Counter failedCounter = metricsConfig.createFailedCounter(topic, consumerGroup);
        Timer processingTimer = metricsConfig.createProcessingTimer(topic, consumerGroup);

        int attempt = 0;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            Timer.Sample sample = Timer.start();

            try {
                log.debug(
                        "Processing message: topic={}, partition={}, offset={}, attempt={}",
                        topic,
                        partition,
                        offset,
                        attempt);

                processMessage(record);

                // Success
                sample.stop(processingTimer);
                consumedCounter.increment();

                log.info(
                        "Successfully processed message: topic={}, partition={}, offset={},"
                                + " attempts={}",
                        topic,
                        partition,
                        offset,
                        attempt + 1);
                return;

            } catch (Exception e) {
                sample.stop(processingTimer);
                lastException = e;
                attempt++;

                log.warn(
                        "Failed to process message: topic={}, partition={}, offset={}, attempt={},"
                                + " error={}",
                        topic,
                        partition,
                        offset,
                        attempt,
                        e.getMessage());

                if (attempt <= maxRetries) {
                    // Retry after delay
                    sleep(retryDelayMs * attempt);
                }
            }
        }

        // All retries exhausted - send to DLQ
        failedCounter.increment();
        log.error(
                "Message processing failed after {} retries: topic={}, partition={}, offset={}",
                maxRetries,
                topic,
                partition,
                offset,
                lastException);

        dlqHandler.sendToDeadLetterQueue(record, lastException, maxRetries, consumerGroup);
    }

    /**
     * Abstract method to process the message. Implementations should throw exceptions on failure to
     * trigger retry logic.
     *
     * @param record The Kafka consumer record
     * @throws Exception if processing fails
     */
    protected abstract void processMessage(ConsumerRecord<String, String> record) throws Exception;

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Retry sleep interrupted");
        }
    }
}
