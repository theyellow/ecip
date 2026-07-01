package io.emcip.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
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

    @Test
    void validateTenantHeader_returnsTenantUuid() {
        UUID tenantId = UUID.randomUUID();
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("test-topic", 0, 0, "key", "value");
        record.headers()
                .add(
                        new RecordHeader(
                                "tenant_id", tenantId.toString().getBytes(StandardCharsets.UTF_8)));

        UUID result = TenantAwareKafkaSupport.validateTenantHeader(record);

        assertThat(result).isEqualTo(tenantId);
    }

    @Test
    void validateTenantHeader_missingHeader_throws() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("test-topic", 0, 0, "key", "value");

        assertThatThrownBy(() -> TenantAwareKafkaSupport.validateTenantHeader(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void validateTenantHeader_invalidUuid_throws() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("test-topic", 0, 0, "key", "value");
        record.headers()
                .add(new RecordHeader("tenant_id", "not-a-uuid".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> TenantAwareKafkaSupport.validateTenantHeader(record))
                .isInstanceOf(IllegalStateException.class);
    }
}
