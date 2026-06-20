package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.TriggerType;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.EnrichmentSourceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnrichmentSchedulerTest {

    @Mock private EnrichmentSourceRepository sourceRepo;
    @Mock private EnrichmentRunRepository runRepo;
    @Mock private EnrichmentPipelineService pipelineService;

    private EnrichmentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EnrichmentScheduler(sourceRepo, runRepo, pipelineService);
    }

    @Test
    void dispatchesDueSources() {
        EnrichmentSource dueSource = new EnrichmentSource();
        dueSource.setId(UUID.randomUUID());
        dueSource.setVendorId("wikipedia");
        dueSource.setScheduleCron("0 * * * * *"); // every minute — always due

        when(sourceRepo.findAllByEnabledTrue()).thenReturn(List.of(dueSource));
        EnrichmentRun savedRun = new EnrichmentRun();
        savedRun.setId(UUID.randomUUID());
        when(runRepo.save(any())).thenReturn(savedRun);

        scheduler.tick();

        ArgumentCaptor<EnrichmentRun> runCaptor = ArgumentCaptor.forClass(EnrichmentRun.class);
        verify(runRepo).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getTriggerType()).isEqualTo(TriggerType.SCHEDULED);
        // pipelineService.execute() is dispatched to a virtual thread — verified by integration
        // test
    }

    @Test
    void skipsSourceWithNoScheduleCron() {
        EnrichmentSource noSchedule = new EnrichmentSource();
        noSchedule.setVendorId("exa");
        noSchedule.setScheduleCron(null);

        when(sourceRepo.findAllByEnabledTrue()).thenReturn(List.of(noSchedule));

        scheduler.tick();

        verify(pipelineService, never()).execute(any(), any(), any(), any(), any(), any());
    }
}
