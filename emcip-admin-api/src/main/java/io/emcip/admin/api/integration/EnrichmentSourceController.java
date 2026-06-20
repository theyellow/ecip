package io.emcip.admin.api.integration;

import io.emcip.admin.api.integration.dto.EnrichmentSourceResponse;
import io.emcip.admin.api.integration.dto.RunStatusResponse;
import io.emcip.admin.api.integration.dto.TriggerResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/admin/integrations/sources")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('INTEGRATIONS_GLOBAL_MANAGE')")
@Tag(name = "Integrations — Sources", description = "Manage enrichment sources and run history")
public class EnrichmentSourceController {

    private final EnrichmentSourceService service;

    @GetMapping
    public Flux<EnrichmentSourceResponse> list() {
        return service.listAll();
    }

    @PostMapping("/{id}/trigger")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<TriggerResponse> trigger(@PathVariable UUID id) {
        return service.triggerManual(id);
    }

    @GetMapping("/{id}/runs")
    public Flux<RunStatusResponse> listRuns(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listRuns(id, page, size);
    }

    @GetMapping("/{id}/runs/{runId}")
    public Mono<RunStatusResponse> getRun(@PathVariable UUID id, @PathVariable UUID runId) {
        return service.getRun(runId);
    }
}
