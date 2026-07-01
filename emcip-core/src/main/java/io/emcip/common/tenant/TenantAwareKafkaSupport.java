package io.emcip.common.tenant;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;

public final class TenantAwareKafkaSupport {

    private TenantAwareKafkaSupport() {}

    public static void bindTenantFromRecord(ConsumerRecord<?, ?> record) {
        var header = record.headers().lastHeader(TenantContext.KAFKA_HEADER);
        if (header != null) {
            TenantContext.setTenantId(new String(header.value(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Extracts and validates the tenant_id header from a Kafka record.
     *
     * @return parsed tenant UUID
     * @throws IllegalStateException if header is missing or not a valid UUID
     */
    public static UUID validateTenantHeader(ConsumerRecord<?, ?> record) {
        var header = record.headers().lastHeader(TenantContext.KAFKA_HEADER);
        if (header == null) {
            throw new IllegalStateException(
                    "Missing required tenant_id header on topic "
                            + record.topic()
                            + " offset "
                            + record.offset());
        }
        String raw = new String(header.value(), StandardCharsets.UTF_8);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid tenant_id header '"
                            + raw
                            + "' on topic "
                            + record.topic()
                            + " offset "
                            + record.offset(),
                    e);
        }
    }

    public static void addTenantHeader(ProducerRecord<?, ?> record) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            record.headers()
                    .add(TenantContext.KAFKA_HEADER, tenantId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
