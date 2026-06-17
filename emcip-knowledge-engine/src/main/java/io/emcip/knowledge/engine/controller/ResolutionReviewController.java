package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.service.ResolutionReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Resolution Review", description = "Operator review queue for entity resolution flags")
@RestController
@RequestMapping("/api/resolution-review")
@RequiredArgsConstructor
@Slf4j
public class ResolutionReviewController {

    private final ResolutionReviewService service;

    @Operation(summary = "List resolution flags with optional filters")
    @GetMapping
    public Page<ResolutionFlag> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String conceptType,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(
                status,
                conceptType,
                tenantId,
                PageRequest.of(page, Math.min(size, 200), Sort.by("createdAt").descending()));
    }

    @Operation(summary = "Merge candidate node into similar node and mark flag MERGED")
    @PatchMapping("/{id}/merge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void merge(@PathVariable UUID id) {
        service.merge(id);
    }

    @Operation(summary = "Dismiss flag without graph changes, mark flag DISMISSED")
    @PatchMapping("/{id}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable UUID id) {
        service.dismiss(id);
    }
}
