package io.emcip.admin.api.integration;

import io.emcip.common.tenant.TenantAwareKafkaSupport;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class EnrichmentTriggerPublisher {

    private static final String TOPIC = "knowledge.enrichment.trigger";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Publishes a manual enrichment trigger, always setting the {@code tenant_id} header the
     * consumer requires.
     *
     * @param sourceId the enrichment source to run
     * @param runId the already-persisted run record
     * @param sourceTenantId the source's tenant id, or {@code null} for a global source (the normal
     *     case — see 014-seed-enrichment-sources.xml). {@code null} is sent as {@link
     *     TenantAwareKafkaSupport#GLOBAL_TENANT_SENTINEL}.
     */
    public void publish(UUID sourceId, UUID runId, UUID sourceTenantId) {
        try {
            String payload =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "sourceId", sourceId.toString(),
                                    "runId", runId.toString()));
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(TOPIC, sourceId.toString(), payload);
            TenantAwareKafkaSupport.addTenantHeader(record, sourceTenantId);
            kafkaTemplate.send(record);
            log.debug(
                    "Published enrichment trigger: sourceId={} runId={} tenantId={}",
                    sourceId,
                    runId,
                    sourceTenantId);
        } catch (Exception e) {
            log.error("Failed to publish enrichment trigger: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to publish enrichment trigger", e);
        }
    }
}
