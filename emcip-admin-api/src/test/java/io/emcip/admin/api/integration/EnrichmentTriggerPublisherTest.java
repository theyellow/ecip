package io.emcip.admin.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import io.emcip.common.tenant.TenantAwareKafkaSupport;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Regression coverage for the P1 tenant-isolation fix: this publisher is the only producer for
 * {@code knowledge.enrichment.trigger}, and it previously sent no {@code tenant_id} header at all,
 * which the consumer now requires unconditionally.
 */
@ExtendWith(MockitoExtension.class)
class EnrichmentTriggerPublisherTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private EnrichmentTriggerPublisher publisher;

    @Captor private ArgumentCaptor<ProducerRecord<String, String>> recordCaptor;

    @Test
    void publish_setsGlobalSentinelHeader_whenSourceTenantIsNull() {
        UUID sourceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        publisher.publish(sourceId, runId, null);

        verify(kafkaTemplate).send(recordCaptor.capture());
        var header = recordCaptor.getValue().headers().lastHeader("tenant_id");
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8))
                .isEqualTo(TenantAwareKafkaSupport.GLOBAL_TENANT_SENTINEL.toString());
    }

    @Test
    void publish_setsSourceTenantHeader_whenSourceIsTenantScoped() {
        UUID sourceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        publisher.publish(sourceId, runId, tenantId);

        verify(kafkaTemplate).send(recordCaptor.capture());
        var header = recordCaptor.getValue().headers().lastHeader("tenant_id");
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8))
                .isEqualTo(tenantId.toString());
    }

    @Test
    void publish_neverOmitsHeader() {
        publisher.publish(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }
}
