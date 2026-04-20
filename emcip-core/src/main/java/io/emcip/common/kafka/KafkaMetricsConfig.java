package io.emcip.common.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Kafka metrics using Micrometer. Provides counters and timers for monitoring
 * Kafka consumer/producer performance.
 */
@Configuration
public class KafkaMetricsConfig {

    private final MeterRegistry meterRegistry;

    public KafkaMetricsConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Create a counter for successful message consumptions. */
    public Counter createConsumedCounter(String topic, String consumerGroup) {
        return Counter.builder("kafka.messages.consumed")
                .description("Total messages consumed from Kafka")
                .tag("topic", topic)
                .tag("consumer.group", consumerGroup)
                .register(meterRegistry);
    }

    /** Create a counter for failed message consumptions. */
    public Counter createFailedCounter(String topic, String consumerGroup) {
        return Counter.builder("kafka.messages.failed")
                .description("Total failed message consumptions from Kafka")
                .tag("topic", topic)
                .tag("consumer.group", consumerGroup)
                .register(meterRegistry);
    }

    /** Create a counter for messages sent to dead letter queue. */
    public Counter createDeadLetterCounter(String topic, String consumerGroup) {
        return Counter.builder("kafka.messages.deadletter")
                .description("Total messages sent to dead letter queue")
                .tag("topic", topic)
                .tag("consumer.group", consumerGroup)
                .register(meterRegistry);
    }

    /** Create a timer for message processing duration. */
    public Timer createProcessingTimer(String topic, String consumerGroup) {
        return Timer.builder("kafka.message.processing.duration")
                .description("Time taken to process Kafka messages")
                .tag("topic", topic)
                .tag("consumer.group", consumerGroup)
                .register(meterRegistry);
    }

    /** Create a counter for successful message productions. */
    public Counter createProducedCounter(String topic) {
        return Counter.builder("kafka.messages.produced")
                .description("Total messages produced to Kafka")
                .tag("topic", topic)
                .register(meterRegistry);
    }

    /** Create a counter for failed message productions. */
    public Counter createProduceFailedCounter(String topic) {
        return Counter.builder("kafka.messages.produce.failed")
                .description("Total failed message productions to Kafka")
                .tag("topic", topic)
                .register(meterRegistry);
    }

    /** Record message lag metric. */
    public void recordLag(String topic, String consumerGroup, long lag) {
        meterRegistry.gauge(
                "kafka.consumer.lag",
                java.util.List.of(
                        io.micrometer.core.instrument.Tag.of("topic", topic),
                        io.micrometer.core.instrument.Tag.of("consumer.group", consumerGroup)),
                lag);
    }
}
