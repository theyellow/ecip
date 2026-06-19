package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.connector.TestStubConnector;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.entity.TriggerType;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Import(EnrichmentPipelineIntegrationTest.TestConfig.class)
@Transactional
class EnrichmentPipelineIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        TestStubConnector testStubConnector() {
            return new TestStubConnector();
        }
    }

    @Autowired EnrichmentPipelineService pipelineService;
    @Autowired EnrichmentSourceRepository sourceRepo;
    @Autowired EnrichmentRunRepository runRepo;
    @Autowired KnowledgeDocumentRepository docRepo;

    @Test
    void pipeline_storesDocumentAndUpdatesRun() {
        EnrichmentSource source = new EnrichmentSource();
        source.setVendorId(TestStubConnector.VENDOR_ID);
        source.setConfig(Map.of());
        source = sourceRepo.save(source);

        EnrichmentRun run = new EnrichmentRun();
        run.setSourceId(source.getId());
        run.setTriggerType(TriggerType.MANUAL);
        run.setStatus(RunStatus.RUNNING);
        run = runRepo.save(run);

        UUID tenantId = UUID.randomUUID();
        pipelineService.execute(
                source, run, TriggerMode.MANUAL, "quantum computing", null, tenantId);

        EnrichmentRun updated = runRepo.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(updated.getItemsFetched()).isEqualTo(1);
        assertThat(updated.getItemsIngested()).isEqualTo(1);
        assertThat(updated.getCompletedAt()).isNotNull();

        // Document stored
        boolean stored =
                docRepo.existsBySourceRefAndSourceType("stub-ext-001", TestStubConnector.VENDOR_ID);
        assertThat(stored).isTrue();
    }

    @Test
    void pipeline_skipsDuplicate_onSecondRun() {
        EnrichmentSource source = new EnrichmentSource();
        source.setVendorId(TestStubConnector.VENDOR_ID);
        source.setConfig(Map.of());
        source = sourceRepo.save(source);

        UUID tenantId = UUID.randomUUID();

        EnrichmentRun run1 = new EnrichmentRun();
        run1.setSourceId(source.getId());
        run1.setTriggerType(TriggerType.MANUAL);
        run1.setStatus(RunStatus.RUNNING);
        run1 = runRepo.save(run1);
        pipelineService.execute(source, run1, TriggerMode.MANUAL, "topic", null, tenantId);

        EnrichmentRun run2 = new EnrichmentRun();
        run2.setSourceId(source.getId());
        run2.setTriggerType(TriggerType.MANUAL);
        run2.setStatus(RunStatus.RUNNING);
        run2 = runRepo.save(run2);
        pipelineService.execute(source, run2, TriggerMode.MANUAL, "topic", null, tenantId);

        EnrichmentRun updated2 = runRepo.findById(run2.getId()).orElseThrow();
        assertThat(updated2.getItemsFetched()).isEqualTo(1);
        assertThat(updated2.getItemsIngested()).isEqualTo(0); // duplicate skipped
        assertThat(updated2.getStatus()).isEqualTo(RunStatus.SUCCESS);
    }
}
