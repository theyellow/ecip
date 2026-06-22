package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.entity.ResearchReport;
import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.model.ResearchEvidenceDto;
import io.emcip.knowledge.engine.model.ResearchReportDto;
import io.emcip.knowledge.engine.model.ResearchRequest;
import io.emcip.knowledge.engine.model.ResearchSessionDto;
import io.emcip.knowledge.engine.repository.ResearchEvidenceRepository;
import io.emcip.knowledge.engine.repository.ResearchReportRepository;
import io.emcip.knowledge.engine.repository.ResearchSessionRepository;
import io.emcip.knowledge.engine.service.ResearchAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Deep Research", description = "Manage deep research sessions")
@Slf4j
@RestController
@RequestMapping("/api/knowledge/research")
@RequiredArgsConstructor
public class ResearchController {

    private final ResearchAgentService agentService;
    private final ResearchSessionRepository sessionRepository;
    private final ResearchEvidenceRepository evidenceRepository;
    private final ResearchReportRepository reportRepository;

    @Operation(summary = "Start a new deep research session")
    @PostMapping
    public ResponseEntity<ResearchSessionDto> startResearch(
            @Valid @RequestBody ResearchRequest request) {
        log.info(
                "Starting research session for tenant {}: {}",
                request.tenantId(),
                request.question());
        ResearchSession session = agentService.startResearch(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(session));
    }

    @Operation(summary = "Get a research session by ID")
    @GetMapping("/{id}")
    public ResponseEntity<ResearchSessionDto> getSession(@PathVariable UUID id) {
        return sessionRepository
                .findById(id)
                .map(s -> ResponseEntity.ok(toDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "List research sessions for a tenant")
    @GetMapping
    public ResponseEntity<List<ResearchSessionDto>> listSessions(@RequestParam UUID tenantId) {
        List<ResearchSessionDto> sessions =
                sessionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                        .map(this::toDto)
                        .toList();
        return ResponseEntity.ok(sessions);
    }

    @Operation(summary = "Pause a running research session")
    @PostMapping("/{id}/pause")
    public ResponseEntity<ResearchSessionDto> pauseSession(@PathVariable UUID id) {
        return agentService
                .pauseSession(id)
                .map(s -> ResponseEntity.ok(toDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Resume a paused research session")
    @PostMapping("/{id}/resume")
    public ResponseEntity<ResearchSessionDto> resumeSession(@PathVariable UUID id) {
        return agentService
                .resumeSession(id)
                .map(s -> ResponseEntity.ok(toDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get the research report for a session")
    @GetMapping("/{id}/report")
    public ResponseEntity<ResearchReportDto> getReport(@PathVariable UUID id) {
        return reportRepository
                .findBySessionId(id)
                .map(r -> ResponseEntity.ok(ResearchReportDto.from(r)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Download the research report as Markdown")
    @GetMapping("/{id}/report/markdown")
    public ResponseEntity<String> getReportMarkdown(@PathVariable UUID id) {
        return reportRepository
                .findBySessionId(id)
                .map(
                        r ->
                                ResponseEntity.ok()
                                        .header("Content-Type", "text/markdown; charset=UTF-8")
                                        .header(
                                                "Content-Disposition",
                                                "attachment; filename=\"report-" + id + ".md\"")
                                        .body(r.getContent()))
                .orElse(ResponseEntity.notFound().build());
    }

    private ResearchSessionDto toDto(ResearchSession session) {
        List<ResearchEvidenceDto> evidence =
                evidenceRepository
                        .findBySessionIdOrderByIterationAscCreatedAtAsc(session.getId())
                        .stream()
                        .map(
                                e ->
                                        new ResearchEvidenceDto(
                                                e.getId(),
                                                e.getSubQuestion(),
                                                e.getQueryStrategy(),
                                                e.getFinding(),
                                                e.getSourceType(),
                                                e.getSourceRef(),
                                                e.getConfidenceScore(),
                                                e.getIteration(),
                                                e.getCreatedAt()))
                        .toList();
        UUID reportId =
                reportRepository
                        .findBySessionId(session.getId())
                        .map(ResearchReport::getId)
                        .orElse(null);
        return ResearchSessionDto.from(session, evidence, reportId);
    }
}
