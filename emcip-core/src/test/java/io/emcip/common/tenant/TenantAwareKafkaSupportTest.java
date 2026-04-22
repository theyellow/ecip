package io.emcip.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantAwareKafkaSupportTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void extractsTenantIdFromHeader() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(TenantContext.KAFKA_HEADER, "tenant-xyz".getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(
                        "topic",
                        0,
                        0L,
                        -1L,
                        TimestampType.NO_TIMESTAMP_TYPE,
                        -1,
                        -1,
                        "key",
                        "value",
                        headers,
                        Optional.empty());
        TenantAwareKafkaSupport.bindTenantFromRecord(record);
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-xyz");
    }

    @Test
    void doesNotFailWhenHeaderAbsent() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("topic", 0, 0L, "key", "value");
        TenantAwareKafkaSupport.bindTenantFromRecord(record);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void addsTenantHeaderToProducerRecord() {
        TenantContext.setTenantId("tenant-abc");
        ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");
        TenantAwareKafkaSupport.addTenantHeader(record);
        byte[] headerValue = record.headers().lastHeader(TenantContext.KAFKA_HEADER).value();
        assertThat(new String(headerValue, StandardCharsets.UTF_8)).isEqualTo("tenant-abc");
    }
}
