package io.emcip.policy.engine.controller;

import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/policy-decisions")
@RequiredArgsConstructor
public class PolicyDecisionController {

    private final PolicyDecisionRepository repository;

    @GetMapping
    public Flux<PolicyDecision> list(
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String decision) {
        return Mono.fromCallable(
                        () -> {
                            if (decision != null && !decision.isBlank()) {
                                return repository.findByDecisionOrderByTimestampDesc(
                                        decision, size);
                            }
                            return repository.findTopByDecisionNotOrderByTimestampDesc(
                                    "ALLOW", size);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> updateStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            return Mono.error(new IllegalArgumentException("status is required"));
        }
        return Mono.fromRunnable(() -> repository.updateSignalStatus(id, status))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
