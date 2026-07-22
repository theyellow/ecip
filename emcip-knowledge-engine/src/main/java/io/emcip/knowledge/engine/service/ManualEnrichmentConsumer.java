package io.emcip.knowledge.engine.service;

import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import java.util.Map;
import java.util.Optional;
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
public class ManualEnrichmentConsumer {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final EnrichmentSourceRepository sourceRepo;
    private final EnrichmentRunRepository runRepo;
    private final EnrichmentPipelineService pipelineService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "knowledge.enrichment.trigger", groupId = "knowledge-engine")
    public void consume(ConsumerRecord<String, String> record) {
        UUID tenantId;
        try {
            tenantId = TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting enrichment trigger: {}", e.getMessage());
            return;
        }

        try {
            Map<String, String> payload =
                    objectMapper.readValue(
                            record.value(), new TypeReference<Map<String, String>>() {});

            UUID sourceId = UUID.fromString(payload.get("sourceId"));
            UUID runId = UUID.fromString(payload.get("runId"));

            Optional<EnrichmentSource> source = sourceRepo.findById(sourceId);
            Optional<EnrichmentRun> run = runRepo.findById(runId);

            if (source.isEmpty() || run.isEmpty()) {
                log.warn("Manual trigger: source {} or run {} not found", sourceId, runId);
                return;
            }

            if (!tenantId.equals(source.get().getTenantId())) {
                log.error(
                        "Tenant mismatch on enrichment trigger: header={} source={}",
                        tenantId,
                        source.get().getTenantId());
                return;
            }

            EXECUTOR.submit(
                    () -> {
                        try {
                            pipelineService.execute(
                                    source.get(),
                                    run.get(),
                                    TriggerMode.MANUAL,
                                    null,
                                    null,
                                    source.get().getTenantId());
                        } catch (Exception e) {
                            log.error(
                                    "Manual enrichment failed for run {}: {}",
                                    runId,
                                    e.getMessage(),
                                    e);
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to process manual trigger event: {}", e.getMessage(), e);
        }
    }
}
