package io.emcip.policy.engine.controller;

import io.emcip.policy.engine.dto.DryRunRequest;
import io.emcip.policy.engine.dto.DryRunResult;
import io.emcip.policy.engine.service.DryRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Tag(name = "Policy Rules")
@RestController
@RequestMapping("/api/policy-rules")
@RequiredArgsConstructor
public class DryRunController {

    private final DryRunService dryRunService;

    @Operation(summary = "Evaluate an unsaved rule against a test context — no side effects")
    @PostMapping("/dry-run")
    public Mono<DryRunResult> dryRun(@RequestBody DryRunRequest request) {
        return Mono.fromCallable(() -> dryRunService.evaluate(request.rule(), request.context()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
