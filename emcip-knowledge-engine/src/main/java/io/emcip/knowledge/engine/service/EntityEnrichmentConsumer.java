package io.emcip.knowledge.engine.service;

import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.entity.TriggerType;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class EntityEnrichmentConsumer {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final EnrichmentSourceRepository sourceRepo;
    private final EnrichmentRunRepository runRepo;
    private final EnrichmentPipelineService pipelineService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "knowledge.events", groupId = "knowledge-engine-entity-enrichment")
    public void consume(ConsumerRecord<String, String> record) {
        UUID tenantId;
        try {
            tenantId = TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting record: {}", e.getMessage());
            return;
        }

        try {
            Map<String, Object> event =
                    objectMapper.readValue(
                            record.value(), new TypeReference<Map<String, Object>>() {});

            String eventType = (String) event.get("eventType");
            if (!"ENTITY_CREATED".equals(eventType)) return;

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
            String entityName = (String) payload.get("entityName");

            List<EnrichmentSource> sources = sourceRepo.findAllByEnabledTrueAndTenantId(tenantId);

            for (EnrichmentSource source : sources) {
                EnrichmentRun run = new EnrichmentRun();
                run.setSourceId(source.getId());
                run.setTriggerType(TriggerType.TOPIC_DRIVEN);
                run.setStatus(RunStatus.RUNNING);
                EnrichmentRun saved = runRepo.save(run);
                EXECUTOR.submit(
                        () -> {
                            try {
                                pipelineService.execute(
                                        source,
                                        saved,
                                        TriggerMode.TOPIC_DRIVEN,
                                        entityName,
                                        null,
                                        tenantId);
                            } catch (Exception e) {
                                log.error(
                                        "Topic-driven enrichment failed for source {}: {}",
                                        source.getId(),
                                        e.getMessage(),
                                        e);
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Failed to process knowledge event: {}", e.getMessage(), e);
        }
    }
}
