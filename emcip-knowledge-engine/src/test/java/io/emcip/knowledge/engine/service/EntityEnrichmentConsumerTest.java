package io.emcip.knowledge.engine.service;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.emcip.common.tenant.TenantContext;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.entity.TriggerType;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class EntityEnrichmentConsumerTest {

    @Mock private EnrichmentSourceRepository sourceRepo;
    @Mock private EnrichmentRunRepository runRepo;
    @Mock private EnrichmentPipelineService pipelineService;

    private EntityEnrichmentConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new EntityEnrichmentConsumer(sourceRepo, runRepo, pipelineService, objectMapper);
    }

    private ConsumerRecord<String, String> record(String json, UUID tenantId) {
        ConsumerRecord<String, String> r =
                new ConsumerRecord<>("knowledge.events", 0, 0L, "ENTITY_CREATED", json);
        if (tenantId != null) {
            r.headers()
                    .add(
                            TenantContext.KAFKA_HEADER,
                            tenantId.toString().getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    private String entityCreatedJson(String entityName) {
        return """
               {"eventId":"e1","eventType":"ENTITY_CREATED","timestamp":"2026-01-01T00:00:00Z",
                "payload":{"entityName":"%s"}}
               """
                .formatted(entityName);
    }

    @Test
    void consume_rejectsRecordWithoutTenantHeader() {
        consumer.consume(record(entityCreatedJson("Acme"), null));

        verifyNoInteractions(sourceRepo, runRepo, pipelineService);
    }

    @Test
    void consume_ignoresNonEntityCreatedEvents() {
        String json =
                """
                {"eventId":"e1","eventType":"BACKFILL_PROGRESS","payload":{"chatId":"c1"}}
                """;

        consumer.consume(record(json, UUID.randomUUID()));

        verifyNoInteractions(sourceRepo, runRepo, pipelineService);
    }

    @Test
    void consume_ignoresMalformedJsonWithoutThrowing() {
        consumer.consume(record("{not json", UUID.randomUUID()));

        verifyNoInteractions(sourceRepo, runRepo, pipelineService);
    }

    @Test
    void consume_createsRunPerEnabledSourceAndDispatchesPipeline() {
        UUID tenantId = UUID.randomUUID();
        EnrichmentSource sourceOne = new EnrichmentSource();
        sourceOne.setId(UUID.randomUUID());
        EnrichmentSource sourceTwo = new EnrichmentSource();
        sourceTwo.setId(UUID.randomUUID());
        when(sourceRepo.findAllByEnabledTrueAndTenantId(tenantId))
                .thenReturn(List.of(sourceOne, sourceTwo));

        EnrichmentRun savedOne = new EnrichmentRun();
        savedOne.setId(UUID.randomUUID());
        EnrichmentRun savedTwo = new EnrichmentRun();
        savedTwo.setId(UUID.randomUUID());
        when(runRepo.save(any(EnrichmentRun.class))).thenReturn(savedOne, savedTwo);

        consumer.consume(record(entityCreatedJson("Acme"), tenantId));

        ArgumentCaptor<EnrichmentRun> runCaptor = ArgumentCaptor.forClass(EnrichmentRun.class);
        verify(runRepo, org.mockito.Mockito.times(2)).save(runCaptor.capture());
        for (EnrichmentRun run : runCaptor.getAllValues()) {
            org.assertj.core.api.Assertions.assertThat(run.getTriggerType())
                    .isEqualTo(TriggerType.TOPIC_DRIVEN);
            org.assertj.core.api.Assertions.assertThat(run.getStatus())
                    .isEqualTo(RunStatus.RUNNING);
        }

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                verify(pipelineService)
                                        .execute(
                                                eq(sourceOne),
                                                eq(savedOne),
                                                eq(TriggerMode.TOPIC_DRIVEN),
                                                eq("Acme"),
                                                eq(null),
                                                eq(tenantId)));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                verify(pipelineService)
                                        .execute(
                                                eq(sourceTwo),
                                                eq(savedTwo),
                                                eq(TriggerMode.TOPIC_DRIVEN),
                                                eq("Acme"),
                                                eq(null),
                                                eq(tenantId)));
    }

    @Test
    void consume_doesNothingWhenNoSourcesEnabled() {
        UUID tenantId = UUID.randomUUID();
        when(sourceRepo.findAllByEnabledTrueAndTenantId(tenantId)).thenReturn(List.of());

        consumer.consume(record(entityCreatedJson("Acme"), tenantId));

        verify(runRepo, never()).save(any());
        verifyNoInteractions(pipelineService);
    }
}
