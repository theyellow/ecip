package io.emcip.common.tenant;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;

public final class TenantAwareKafkaSupport {

    /**
     * Sentinel value for the {@code tenant_id} header on topics whose payload targets a
     * tenant-agnostic (global) resource, i.e. one persisted with {@code tenant_id IS NULL}. Used
     * where a real tenant UUID would otherwise be required but none applies, so that consumers can
     * stay fail-closed (a missing header is always rejected) while still accepting global traffic.
     */
    public static final UUID GLOBAL_TENANT_SENTINEL =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

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

    /**
     * Explicitly sets the {@code tenant_id} header from a caller-supplied tenant id, for producers
     * that do not run inside a {@link TenantContext}-bound thread (e.g. reactive WebFlux services).
     * Use {@link #GLOBAL_TENANT_SENTINEL} when {@code tenantId} is {@code null} so fail-closed
     * consumers can still accept it.
     */
    public static void addTenantHeader(ProducerRecord<?, ?> record, UUID tenantId) {
        UUID effective = tenantId != null ? tenantId : GLOBAL_TENANT_SENTINEL;
        record.headers()
                .add(
                        TenantContext.KAFKA_HEADER,
                        effective.toString().getBytes(StandardCharsets.UTF_8));
    }
}
