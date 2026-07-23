package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Regression coverage for the P1 tenant-isolation fix. In production, {@code
 * EnrichmentTriggerPublisher} is the only producer for {@code knowledge.enrichment.trigger}, and
 * every seeded {@link EnrichmentSource} is global ({@code tenant_id IS NULL}, see
 * 014-seed-enrichment-sources.xml). The "global source" test below is the case that was completely
 * broken before this fix: a valid manual trigger for a global source was rejected 100% of the time.
 */
@ExtendWith(MockitoExtension.class)
class ManualEnrichmentConsumerTest {

    private static final String GLOBAL_TENANT_SENTINEL = "00000000-0000-0000-0000-000000000000";

    @Mock private EnrichmentSourceRepository sourceRepo;
    @Mock private EnrichmentRunRepository runRepo;
    @Mock private EnrichmentPipelineService pipelineService;

    private ManualEnrichmentConsumer consumer;
    private UUID sourceId;
    private UUID runId;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        consumer = new ManualEnrichmentConsumer(sourceRepo, runRepo, pipelineService, objectMapper);
        sourceId = UUID.randomUUID();
        runId = UUID.randomUUID();
    }

    private ConsumerRecord<String, String> recordWithHeader(String headerValue) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String payload =
                mapper.writeValueAsString(
                        Map.of("sourceId", sourceId.toString(), "runId", runId.toString()));
        var record =
                new ConsumerRecord<>(
                        "knowledge.enrichment.trigger", 0, 0L, sourceId.toString(), payload);
        if (headerValue != null) {
            record.headers().add("tenant_id", headerValue.getBytes());
        }
        return record;
    }

    @Test
    void missingHeader_rejected() throws Exception {
        var record = recordWithHeader(null);

        consumer.consume(record);

        verify(pipelineService, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void malformedHeader_rejected() throws Exception {
        var record = recordWithHeader("not-a-uuid");

        consumer.consume(record);

        verify(pipelineService, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void headerDisagreesWithTenantScopedSource_rejected() throws Exception {
        UUID sourceTenantId = UUID.randomUUID();
        UUID headerTenantId = UUID.randomUUID(); // deliberately different
        EnrichmentSource source = new EnrichmentSource();
        source.setId(sourceId);
        source.setTenantId(sourceTenantId);
        EnrichmentRun run = new EnrichmentRun();
        run.setId(runId);

        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        var record = recordWithHeader(headerTenantId.toString());

        consumer.consume(record);

        verify(pipelineService, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void globalSource_acceptedAndPipelineInvoked() throws Exception {
        EnrichmentSource source = new EnrichmentSource();
        source.setId(sourceId);
        source.setTenantId(null); // global source, as every seeded source is
        EnrichmentRun run = new EnrichmentRun();
        run.setId(runId);

        when(sourceRepo.findById(sourceId)).thenReturn(Optional.of(source));
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        CountDownLatch latch = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            latch.countDown();
                            return null;
                        })
                .when(pipelineService)
                .execute(any(), any(), any(), any(), any(), any());

        var record = recordWithHeader(GLOBAL_TENANT_SENTINEL);

        consumer.consume(record);

        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("pipelineService.execute should be invoked for a global source")
                .isTrue();
        verify(pipelineService, times(1))
                .execute(source, run, TriggerMode.MANUAL, null, null, null);
    }
}
