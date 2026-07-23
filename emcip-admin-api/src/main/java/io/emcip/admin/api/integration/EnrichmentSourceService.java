package io.emcip.admin.api.integration;

import io.emcip.admin.api.integration.dto.EnrichmentSourceResponse;
import io.emcip.admin.api.integration.dto.RunStatusResponse;
import io.emcip.admin.api.integration.dto.TriggerResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@RequiredArgsConstructor
public class EnrichmentSourceService {

    private final EnrichmentSourceRowRepository sourceRepo;
    private final EnrichmentRunRowRepository runRepo;
    private final EnrichmentTriggerPublisher triggerPublisher;

    public Flux<EnrichmentSourceResponse> listAll() {
        return sourceRepo.findAllByTenantIdIsNull().map(EnrichmentSourceResponse::from);
    }

    public Mono<TriggerResponse> triggerManual(UUID sourceId) {
        return sourceRepo
                .findById(sourceId)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Source not found")))
                .flatMap(
                        source -> {
                            EnrichmentRunRow run = new EnrichmentRunRow();
                            run.setSourceId(sourceId);
                            run.setTriggerType("MANUAL");
                            run.setStatus("RUNNING");
                            run.setStartedAt(Instant.now());
                            run.setItemsFetched(0);
                            run.setItemsIngested(0);
                            return runRepo.save(run)
                                    .map(
                                            savedRun -> {
                                                triggerPublisher.publish(
                                                        sourceId,
                                                        savedRun.getId(),
                                                        source.getTenantId());
                                                return new TriggerResponse(savedRun.getId());
                                            });
                        });
    }

    public Flux<RunStatusResponse> listRuns(UUID sourceId, int page, int size) {
        return runRepo.findBySourceIdOrderByStartedAtDesc(sourceId, PageRequest.of(page, size))
                .map(RunStatusResponse::from);
    }

    public Mono<RunStatusResponse> getRun(UUID runId) {
        return runRepo.findById(runId)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found")))
                .map(RunStatusResponse::from);
    }
}
