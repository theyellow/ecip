package io.emcip.common.tenant;

import java.nio.charset.StandardCharsets;
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

    public static void addTenantHeader(ProducerRecord<?, ?> record) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            record.headers()
                    .add(TenantContext.KAFKA_HEADER, tenantId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
