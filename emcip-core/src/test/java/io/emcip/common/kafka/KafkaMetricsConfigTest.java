package io.emcip.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KafkaMetricsConfigTest {

    private MeterRegistry meterRegistry;
    private KafkaMetricsConfig metricsConfig;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metricsConfig = new KafkaMetricsConfig(meterRegistry);
    }

    @Test
    void createConsumedCounter_registersWithCorrectTags() {
        Counter counter = metricsConfig.createConsumedCounter("my-topic", "my-group");

        assertThat(counter).isNotNull();
        assertThat(counter.getId().getName()).isEqualTo("kafka.messages.consumed");
        assertThat(counter.getId().getTag("topic")).isEqualTo("my-topic");
        assertThat(counter.getId().getTag("consumer.group")).isEqualTo("my-group");
    }

    @Test
    void createFailedCounter_registersWithCorrectTags() {
        Counter counter = metricsConfig.createFailedCounter("fail-topic", "fail-group");

        assertThat(counter.getId().getName()).isEqualTo("kafka.messages.failed");
        assertThat(counter.getId().getTag("topic")).isEqualTo("fail-topic");
        assertThat(counter.getId().getTag("consumer.group")).isEqualTo("fail-group");
    }

    @Test
    void createDeadLetterCounter_registersWithCorrectTags() {
        Counter counter = metricsConfig.createDeadLetterCounter("dlq-topic", "dlq-group");

        assertThat(counter.getId().getName()).isEqualTo("kafka.messages.deadletter");
        assertThat(counter.getId().getTag("topic")).isEqualTo("dlq-topic");
        assertThat(counter.getId().getTag("consumer.group")).isEqualTo("dlq-group");
    }

    @Test
    void createProcessingTimer_registersWithCorrectTags() {
        Timer timer = metricsConfig.createProcessingTimer("timer-topic", "timer-group");

        assertThat(timer.getId().getName()).isEqualTo("kafka.message.processing.duration");
        assertThat(timer.getId().getTag("topic")).isEqualTo("timer-topic");
        assertThat(timer.getId().getTag("consumer.group")).isEqualTo("timer-group");
    }

    @Test
    void createProducedCounter_registersWithTopicTag() {
        Counter counter = metricsConfig.createProducedCounter("prod-topic");

        assertThat(counter.getId().getName()).isEqualTo("kafka.messages.produced");
        assertThat(counter.getId().getTag("topic")).isEqualTo("prod-topic");
    }

    @Test
    void createProduceFailedCounter_registersWithTopicTag() {
        Counter counter = metricsConfig.createProduceFailedCounter("failed-prod-topic");

        assertThat(counter.getId().getName()).isEqualTo("kafka.messages.produce.failed");
        assertThat(counter.getId().getTag("topic")).isEqualTo("failed-prod-topic");
    }

    @Test
    void recordLag_registersGauge() {
        metricsConfig.recordLag("lag-topic", "lag-group", 42L);

        var gauge = meterRegistry.find("kafka.consumer.lag").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(42.0);
    }

    @Test
    void counterIncrement_trackedByRegistry() {
        Counter counter = metricsConfig.createConsumedCounter("t", "g");
        counter.increment();
        counter.increment();

        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void sameTagsReturnSameCounter() {
        Counter c1 = metricsConfig.createConsumedCounter("t", "g");
        Counter c2 = metricsConfig.createConsumedCounter("t", "g");

        c1.increment();
        assertThat(c2.count()).isEqualTo(1.0);
    }
}
