package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.model.DuplicateSourceException;
import io.emcip.knowledge.engine.model.IngestionJobDetailDto;
import io.emcip.knowledge.engine.model.IngestionJobDto;
import io.emcip.knowledge.engine.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Document Ingestion", description = "Ingest documents into the knowledge base")
@RestController
@RequestMapping("/api/knowledge/ingest")
@RequiredArgsConstructor
public class DocumentIngestionController {

    private final DocumentIngestionService ingestionService;

    @Operation(summary = "Submit a URL for async ingestion")
    @PostMapping("/url")
    public ResponseEntity<Map<String, Object>> ingestUrl(@RequestBody UrlRequest req) {
        if (req.url() == null || req.url().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL must not be blank"));
        }
        String scheme;
        try {
            scheme = new java.net.URI(req.url()).getScheme();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid URL"));
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only http and https URLs are allowed"));
        }
        String jobId = ingestionService.submitUrlIngestion(req.url(), req.tenantId());
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @Operation(summary = "Upload a document for async ingestion")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> ingestUpload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID tenantId) {
        try {
            String filename =
                    file.getOriginalFilename() != null
                            ? file.getOriginalFilename()
                            : "upload-" + UUID.randomUUID();
            String jobId =
                    ingestionService.submitFileIngestion(file.getInputStream(), filename, tenantId);
            return ResponseEntity.accepted().body(Map.of("jobId", jobId));
        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Failed to read file: " + e.getMessage()));
        }
    }

    @Operation(summary = "Get ingestion job status")
    @GetMapping("/{jobId}")
    public IngestionJobDto getJob(@PathVariable UUID jobId) {
        return IngestionJobDto.from(ingestionService.getJob(jobId));
    }

    @Operation(summary = "List ingestion jobs")
    @GetMapping
    public Page<IngestionJobDto> listJobs(
            @RequestParam(required = false) UUID tenantId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return ingestionService.listJobs(tenantId, pageable).map(IngestionJobDto::from);
    }

    @Operation(summary = "Get ingestion job details with chunks and entities")
    @GetMapping("/{jobId}/details")
    public IngestionJobDetailDto getJobDetails(@PathVariable UUID jobId) {
        return ingestionService.getJobDetails(jobId);
    }

    @Operation(summary = "Delete an ingestion job and its chunks")
    @DeleteMapping("/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteJob(@PathVariable UUID jobId) {
        ingestionService.deleteJob(jobId);
    }

    @Operation(summary = "Re-ingest a URL job (re-fetches content, replaces old chunks)")
    @PostMapping("/{jobId}/reingest")
    public ResponseEntity<Map<String, Object>> reingestJob(@PathVariable UUID jobId) {
        try {
            String newJobId = ingestionService.reingestJob(jobId);
            return ResponseEntity.accepted().body(Map.of("jobId", newJobId));
        } catch (IllegalArgumentException e) {
            if ("REUPLOAD_REQUIRED".equals(e.getMessage())) {
                IngestionJob oldJob = ingestionService.getJob(jobId);
                return ResponseEntity.badRequest()
                        .body(
                                Map.of(
                                        "error",
                                        "REUPLOAD_REQUIRED",
                                        "sourceRef",
                                        oldJob.getSourceRef()));
            }
            throw e;
        }
    }

    @ExceptionHandler(DuplicateSourceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateSourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        Map.of(
                                "error",
                                "DUPLICATE_SOURCE",
                                "existingJobId",
                                ex.getExistingJobId().toString(),
                                "message",
                                ex.getMessage()));
    }

    public record UrlRequest(String url, UUID tenantId) {}
}
