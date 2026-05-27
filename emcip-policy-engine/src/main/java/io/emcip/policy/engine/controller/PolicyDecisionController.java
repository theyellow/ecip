package io.emcip.policy.engine.controller;

import io.emcip.common.pagination.PageResponse;
import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Tag(name = "Policy Decisions", description = "Query and update policy decision records")
@RestController
@RequestMapping("/api/policy-decisions")
@RequiredArgsConstructor
public class PolicyDecisionController {

    private final PolicyDecisionRepository repository;

    @Operation(summary = "Get a single policy decision by ID")
    @GetMapping("/{id}")
    public Mono<PolicyDecision> getById(@PathVariable String id) {
        return Mono.fromCallable(
                        () ->
                                repository
                                        .findById(id)
                                        .orElseThrow(
                                                () ->
                                                        new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND,
                                                                "Decision not found: " + id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "List recent policy decisions")
    @GetMapping
    public Mono<PageResponse<PolicyDecision>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String decision) {
        int effectiveSize = Math.min(size, 200);
        org.springframework.data.domain.Pageable pageable =
                PageRequest.of(page, effectiveSize, Sort.by(Sort.Direction.DESC, "timestamp"));
        return Mono.fromCallable(
                        () -> {
                            Page<PolicyDecision> p =
                                    (decision != null && !decision.isBlank())
                                            ? repository.findByDecision(decision, pageable)
                                            : repository.findAll(pageable);
                            return new PageResponse<>(
                                    p.getContent(),
                                    p.getTotalElements(),
                                    p.getNumber(),
                                    p.getSize());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Update decision signal status")
    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> updateStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        String status = body.get("signalStatus");
        if (status == null || status.isBlank()) {
            return Mono.error(new IllegalArgumentException("status is required"));
        }
        return Mono.fromRunnable(() -> repository.updateSignalStatus(id, status))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
